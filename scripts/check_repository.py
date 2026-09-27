"""Check repository boundaries without printing potentially private contents."""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys


PRIVATE_COMPONENTS = {".codex", ".local", "research", "local-tools", "captures"}
PRIVATE_NAMES = {"agents.md", "agents.override.md", "local.properties"}
ROOT_RUNTIME_DIRS = {"mods", "resourcepacks", "saves", "run", "runs", "logs", "crash-reports"}
PRIVATE_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".log", ".class"}
BINARY_SUFFIXES = {".jar", ".exe", ".dll", ".so", ".dylib", ".db", ".sqlite", ".sqlite3"}
CONTENT_RULES = {
    "personal absolute path": re.compile(
        r"(?i)(?:\b[A-Z]:[\\/]|/(?:Users|home)/[A-Za-z0-9_.-]+/)"
    ),
    "private key header": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "GitHub credential": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b"),
    "API credential": re.compile(r"\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}\b"),
    "AWS access key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
}


def git(root: pathlib.Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)


def path_problem(name: str) -> str | None:
    path = pathlib.PurePosixPath(name)
    # Local boundaries must also hold on case-insensitive filesystems.
    parts = tuple(part.casefold() for part in path.parts)
    basename = path.name.casefold()
    if set(parts) & PRIVATE_COMPONENTS or basename in PRIVATE_NAMES:
        return "local-only path"
    if parts[0] in ROOT_RUNTIME_DIRS:
        return "local runtime data"
    if basename == ".env" or (basename.startswith(".env.") and path.name != ".env.example"):
        return "private environment file"
    if path.suffix.lower() in PRIVATE_SUFFIXES:
        return "private or generated file"
    if path.suffix.lower() in BINARY_SUFFIXES and name != "gradle/wrapper/gradle-wrapper.jar":
        return "unexpected binary artifact"
    return None


def check(root: pathlib.Path, staged: bool) -> int:
    if staged:
        raw = git(root, "diff", "--cached", "--name-only", "--diff-filter=ACMRT", "-z")
    else:
        raw = git(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
    names = sorted({n.decode("utf-8", errors="strict") for n in raw.split(b"\0") if n})
    failures: list[tuple[str, str]] = []
    checked = 0
    for name in names:
        problem = path_problem(name)
        if problem:
            failures.append((name, problem))
            continue
        if staged:
            index = git(root, "ls-files", "--stage", "--", name)
            if index.startswith(b"120000 ") or index.startswith(b"160000 "):
                failures.append((name, "symlink or submodule requires explicit review"))
                continue
            data = git(root, "show", ":" + name)
        else:
            file = root / name
            if file.is_symlink():
                failures.append((name, "symlink requires explicit review"))
                continue
            if not file.exists():
                continue  # A working-tree deletion has no content to publish.
            if not file.is_file():
                failures.append((name, "non-file entry requires explicit review"))
                continue
            data = file.read_bytes()
        checked += 1
        if name == "gradle/wrapper/gradle-wrapper.jar":
            continue
        if b"\0" in data:
            failures.append((name, "binary content requires explicit review"))
            continue
        try:
            content = data.decode("utf-8-sig")
        except UnicodeDecodeError:
            failures.append((name, "non-UTF-8 content requires explicit review"))
            continue
        for label, pattern in CONTENT_RULES.items():
            if pattern.search(content):
                failures.append((name, label))
    for name, reason in failures:
        print(f"FAIL: {name}: {reason}")
    scope = "staged" if staged else "tracked and non-ignored working"
    if failures:
        print(f"Repository check failed: {len(failures)} finding(s); {checked} {scope} file(s) inspected.")
        return 1
    print(f"Repository check passed: {checked} {scope} file(s) inspected.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", action="store_true", help="Inspect staged paths and index blobs only.")
    args = parser.parse_args()
    try:
        root = pathlib.Path(git(pathlib.Path.cwd(), "rev-parse", "--show-toplevel").decode().strip())
        return check(root, args.staged)
    except (OSError, subprocess.CalledProcessError, UnicodeError) as error:
        print(f"Repository check could not complete ({type(error).__name__}).", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

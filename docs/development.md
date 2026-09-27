# Development

## Current state

The repository includes a Fabric client bootstrap, a pure Java discovery and project-storage core, synthetic tests, and an offline audit tool. The editor has been observed opening and saving projects. Runtime translation application and revised Mod Menu hooks are implemented but await in-game verification. Automated results do not establish in-game compatibility.

The build pins Minecraft 26.2, Fabric Loader 0.19.5, Loom 1.16.2, Gradle 9.4.1, Java 25, Gson 2.14.0, and JUnit 5.14.1. Direct versions and the Gradle distribution checksum are pinned; transitive dependency locking is not yet configured. Fabric API is not required. Mod Menu 20.0.3 is a compile-only optional integration; its JAR is not bundled. Internal screen hooks allow the inspected 20.0.2 and 20.0.3 versions.

The bootstrap follows the [official Fabric example](https://github.com/FabricMC/fabric-example-mod/tree/1ce1c77a77ddbf7587e0d171ea369051668a67a6) for the unobfuscated 26.2 toolchain and the [Loom documentation](https://docs.fabricmc.net/develop/loom/). Stable Loom 1.16.2 is used instead of the example snapshot. References checked on 2026-09-27.

## Layout

| Path | Purpose | Versioned |
| --- | --- | --- |
| `src/main/` | Discovery, project storage, synchronization, and English resources | Yes |
| `src/client/` | Client startup, editor screens, and optional menu hooks | Yes |
| `src/test/` | Behavioral tests and synthetic fixtures | Yes |
| `src/tools/` | Offline audit CLI, excluded from the mod JAR | Yes |
| `docs/` | Reviewed architecture, format, development, and roadmap documents | Yes |
| `scripts/` | Reusable repository tools | Yes |
| `gradle/` | Pinned Gradle wrapper | Yes |
| `.github/` | Future automation and issue templates | When needed |
| `.local/research/` | Raw research and downloaded references | No |
| `.local/tools/` | Machine-specific helpers | No |
| `.local/reports/` | Local validation output | No |
| `.local/captures/` | Screenshots and runtime evidence | No |
| `.local/fixtures/` | Private real-mod reference data | No |
| `.local/scratch/` | Temporary experiments | No |

Do not create empty public directories solely to match a diagram. Add them when they have a purpose.

## Local reference mods

Use an external, user-supplied mods directory for discovery research. Keep its location in ignored local configuration, such as `.local/settings.json`, never in source code or public documentation. Read the original JARs without editing them. Do not copy installed mods, player data, or extracted resources into public test fixtures.

Record version-specific findings in local reports. Convert important behavior into small, synthetic test cases that can be shared and reproduced independently of a private modpack.

## Build and discovery checks

Install JDK 25 and set `JAVA_HOME` for your shell. On Windows use `gradlew.bat`; on other systems use `sh ./gradlew` until executable file mode is committed.

```sh
sh ./gradlew build
sh ./gradlew auditReferenceMods -PreferenceModsDir=/path/to/reference/mods
```

Alternatively, store `referenceModsDirectory` in ignored `.local/settings.json`. The audit writes `.local/reports/discovery-audit.json`, containing counts, editor availability, and diagnostics rather than translation text. It examines top-level JARs only; Fabric Loader selects nested mods during client startup. Skipped or unreadable JARs are explicitly recorded and must be reviewed even if the task exits successfully.

The distributable is `build/libs/modlang-collective-0.1.0-dev.11.jar`. On client startup it discovers source text, creates `mods/modlangcollective/<modId>/` folders, and updates existing target projects. No target language is selected automatically. The editor is available through Mod Menu. Confirm saves the selected entry; Save asks before confirming pending text in bulk. Both refresh the active language after saving. Version-two projects retain unconfirmed text, and the reader migrates version-one review states without rewriting files on load. `build` runs synthetic discovery, storage, synchronization, editor-session, and runtime-projection tests plus `verifyClientIntegration`; reports are under `build/reports/tests/test/`.

Discovery bounds each language file to 4 MiB and each mod scan to 64 MiB of parsed resource data, with additional root, directory, resource, entry, and diagnostic limits. Partial or ambiguous evidence is not a valid automatic translation source.

The integration audit reads class files to check the pinned Minecraft/Mod Menu hook signatures and header layout injection sites, validates mixin configuration, checks the optional hook is disabled with no loaded Mod Menu, and exercises Minecraft's actual language parser for numeric placeholder normalization. It does not run mixin transformation, render a screen, or prove game startup.

The optional screen integration was inspected against [Mod Menu 20.0.2 source](https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/20.0.2/modmenu-20.0.2-sources.jar) and matching compiled signatures on 2026-09-27. The same screen source was compared with [20.0.3 source](https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/20.0.3/modmenu-20.0.3-sources.jar); no screen-source changes were found. The official Mod Menu configuration entry for ModLang Collective is the fallback when the internal version gate disables the per-mod button.

For a read-only check of a saved project against the reference JARs:

```sh
sh ./gradlew auditRuntimeProject -PauditModId=examplemod -PauditLocale=pt_br
```

This verifies accepted entries against the runtime projection and checks the file revision remains unchanged. It does not prove in-game rendering.

## Repository checks

Python 3.11 or newer and Git are required for the repository checker. From the repository root:

```sh
python scripts/check_repository.py
git diff --check
```

Before a commit, also check the exact staged content:

```sh
python scripts/check_repository.py --staged
git diff --cached --check
git diff --cached --stat
```

The checker flags prohibited paths, unexpected binary artifacts, common personal absolute paths, and selected high-confidence credential patterns. It reads staged blobs when using `--staged`, rather than assuming working files match the index. It never prints matching file contents.

These checks are defense in depth, not a guarantee against every sensitive value. Review the full diff. Ignore rules do not protect files already tracked by Git, and forced additions can bypass ignore rules.

The Gradle wrapper JAR is an intentional exception to the JAR restriction. Do not ignore project `gradle.properties`, source language files, or synthetic fixtures just because local equivalents can contain private data.

## Validation after implementation

Test merge preservation, source changes, namespace collisions, malformed files, conflict resolution, archive traversal, and placeholder handling with synthetic fixtures. Tests cover discovery, merge preservation, format validation, external edits, safe replacement, backups, and independent synchronization of multiple targets. Runtime tests also cover accepted states, source freshness, placeholder compatibility, key collisions, and global limits. Import/export and interactive UI acceptance remain outstanding.

Use a separate development game directory before checking the full reference modpack. Record build results, automated tests, and in-game observations separately. Successful compilation alone does not prove a working editor or resource reload.

## Public language

Write documentation, code comments, default UI text, and release metadata in English. The default UI language file will be `en_us.json`. User-created translations remain multilingual.

# Roadmap

## Stage 0: Project foundation

- Establish Kito Mods branding and the English project-language policy.
- Document architecture, translation-preservation rules, and repository boundaries.
- Add repository hygiene checks and local development organization.

Acceptance: documentation links resolve, the repository checker passes, and local reference data stays outside version control.

## Stage 1: Fabric bootstrap and read-only discovery

Implemented; build, synthetic tests, and offline reference auditing are available. Client startup still requires user-observed in-game validation.

- Pin the Minecraft 26.2 Fabric toolchain and add the Gradle wrapper.
- Add mod metadata and English default UI resources.
- Enumerate mods and language resources without modifying their JARs.
- Report missing English, multiple namespaces, nested resources, and malformed data clearly.

Acceptance: build and focused discovery tests pass; findings are checked against a small representative set of real mods.

## Stage 2: Safe translation projects

Implemented and covered by automated merge, persistence, and synchronization tests. Client startup is wired to create per-mod directories and update existing targets; this behavior still requires in-game observation. Entirely missing namespaces are preserved without automatic reconciliation until discovery can establish their removal reliably.

- Finalize a versioned schema and synthetic fixtures.
- Add local persistence, backups, and update merging.
- Preserve all existing work; track pending, changed, and archived entries.

Acceptance: automated tests cover source additions, changes, removals, external edits, and interrupted or invalid writes.

## Stage 3: In-game editor

The editor, Mod Menu 20.0.2/20.0.3 button, official configuration entry point, source selection, and safe save/removal workflows are implemented. Accepted local translations now overlay the active language at load and after saving, with source and placeholder validation. Automated session/projection tests and static client checks are available. Opening and saving were observed in the previous build; the revised integration and runtime application still require in-game acceptance.

- Provide editor access through Mod Menu only.
- Provide readable local-language names, a combined scrolling text list, one original reference, and translation editing.
- Provide Confirm and Save with explicit bulk confirmation of pending text, automatic application, and clear errors.

Acceptance: the complete workflow is observed in-game, including resizing, long text, Unicode, keyboard navigation, and Mod Menu absence.

## Editor simplification

Implemented for automated validation: local languages plus a pinned read-only Original view, one original reference, text-based entry labels, fewer primary actions, and pending-to-confirmed saving. Version-one files remain readable and retain previous bytes on the first version-two save. Revised UI behavior requires user-observed acceptance.

Automatic translation remains planning only. Reserve a future action beside the translation field; generated text must remain pending. No API provider, network integration, or service availability is promised. LibreTranslate is a candidate, not an adopted dependency.

## Stage 4: Sharing

- Add project ZIP import/export with previews and conflict handling.
- Add namespace-correct resource-pack export.
- Verify path safety, metadata compatibility, and preservation of local translations.

Acceptance: export/import round trips pass; an exported resource pack is checked in-game without the editor installed.

## Future work

- Optional automatic translation providers, with disclosure and pending confirmation.
- Additional Minecraft versions and loaders after demand and compatibility research.
- Support for additional text formats where technically feasible.
- Community distribution workflows if needed; no hosted backend is required by the initial design.

No release date or unsupported compatibility is promised. Update this document as capabilities are implemented and validated.

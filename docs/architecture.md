# Architecture

Status: Fabric bootstrap, catalog, read-only discovery, and local project storage/reconciliation are implemented. The editor and version-gated Mod Menu integration are implemented; runtime overrides are implemented and exchange remains planned. The revised integration still requires in-game verification.

Discovery reads canonical `assets/<namespace>/lang/<locale>.json` resources. The client adapter uses loaded Fabric mod containers, including nested mods selected by the loader; the offline audit intentionally scans only top-level JARs. Discovery itself never writes. After discovery, the client creates per-mod project directories and synchronizes existing targets through the separate storage service. The offline audit does not invoke storage. Source resources are never modified. Duplicate locales across roots are reported without guessing resource-pack precedence. A sole non-English locale is a fallback candidate, not proof of the author's original language. Invalid, partial, conflicting, or incompletely scanned sources are not marked usable.

## Initial platform

Start with a single Fabric client mod targeting Minecraft 26.2. Internal Mod Menu screen hooks support the inspected 20.0.2 and 20.0.3 versions. Do not declare broader compatibility before testing it. Avoid a multi-loader build until there is a concrete second target.

## Components

| Component | Responsibility |
| --- | --- |
| Discovery | Enumerate loaded mods and locate their language resources, including relevant nested resources |
| Catalog | Associate source entries with their originating mod, resource namespace, locale, and version |
| Storage | Read, merge, back up, and safely write local translation projects |
| Editor | Language list, searchable entry list, source text, translation input, and progress states |
| Integration | Mod Menu per-mod Translate button and official configuration entry point |
| Runtime resources | Overlay validated local translations after Minecraft loads the active language |
| Exchange | Import/export projects and export standard resource packs |

Keep discovery, storage, merge rules, and validation independent of screen widgets. This allows meaningful tests without starting Minecraft and reduces the cost of future platform changes.

## User flow

1. On startup, create `mods/modlangcollective/` if needed and identify installed mods and language resources. Never modify the source JARs.
2. Maintain per-mod directories using stable mod IDs rather than display names.
3. Selecting Translate for a mod opens an editor with languages on the left and a searchable entry list plus source and target text on the right.
4. Add Language selects a target from Minecraft's available languages. Removing a language only removes local work, requires confirmation, and never edits bundled translations.
5. Show the source language by name. Prefer English if present. If the original cannot be identified reliably among other languages, ask the user to select it.
6. Editing leaves text pending. Confirm saves the selected text as translated; Save asks before confirming all pending text. Untouched entries remain untranslated.
7. Save and apply are separate responsibilities: a valid save must survive a failed resource reload. Report both outcomes accurately.

A version-gated mixin adds a Translate button beside the Mod Menu 20.0.2/20.0.3 configuration button and reserves header space for it. It does not replace other mods' configuration factories. ModLang Collective also exposes its editor through its own official Mod Menu configuration factory. There are no title or pause menu buttons.

A ClientLanguage return hook replaces the completed language map with an immutable copy containing eligible local overrides. Source catalogs are initialized before the initial resource reload. Save and confirmed removal reload only the language manager, after persistence succeeds. Minecraft's own JSON language parser normalizes numeric placeholders. A failed reload leaves saved files intact. Local accepted overrides take precedence over bundled language resources and resource packs; conflicting local values for a global key are all excluded. Only the active target locale participates. See the editor guide for current workflows and validation limits.

## Preservation contracts

- Never overwrite existing local translations during automatic synchronization.
- Add newly discovered source keys to every existing local target project as pending.
- Return a changed source to pending confirmation while retaining its previous translation and reference history.
- Archive removed source entries instead of silently deleting them.
- Detect concurrent external edits before writing. Use validated temporary writes and recoverable replacement with backups.
- Treat missing translations differently from deliberately empty translations. Never use empty placeholders to blank out in-game text.
- Preserve runtime placeholders, formatting, and resource namespace information.
- Do not assume a mod ID equals its resource namespace or that every mod has language resources.

## Import and sharing

Start with offline project ZIP import/export and resource-pack export. Import must preview conflicts and retain local work by default. Reject archive paths that escape the destination and bound archive size and entry counts. Record source mod and version metadata without including personal paths.

Runtime resource precedence must be explicit and tested. Local overrides should apply only to entries the user has translated or accepted; pending entries should retain normal fallback behavior. Exported resource packs must include metadata for the target Minecraft version.

## Scope boundaries

Support language resources first. Hardcoded strings, server messages, custom books, and datapack literals are separate problems, not automatically covered by scanning language files.

Do not add a hosted community catalog in the initial release. Automatic translation remains an optional future provider interface, invoked explicitly, with disclosure, cancellation, rate-limit handling, and results left pending confirmation. Manual workflows must not require a network connection.

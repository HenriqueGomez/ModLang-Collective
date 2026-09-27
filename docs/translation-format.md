# Translation project format, version 2

Status: implemented local project format. Runtime overrides consume this format; import/export remains planned. The editor uses this format.

## Location and identity

The root is `mods/modlangcollective/` inside the active game directory. Each loaded mod gets a directory named by its mod ID. A target project is `<modId>/<targetLocale>.json`; target files are created only after a target language has been explicitly chosen by a caller. Startup synchronizes existing projects without selecting a target automatically. Projects for unloaded mods remain untouched.

Mod IDs and locales use lowercase ASCII letters, digits, underscores, and hyphens, with a maximum of 64 characters. Windows device names are rejected. The file identity must match the metadata inside it. Namespace identifiers remain separate from mod IDs and are never flattened into translation keys.

A project is not a Minecraft language JSON file. It stores source snapshots, confirmation state, and local work. Do not put it directly into a resource pack.

## Exact JSON fields

All listed fields are required. Unknown fields, duplicate JSON members, invalid types, malformed Unicode, and unsupported schema versions are rejected instead of repaired or overwritten.

| Object | Fields |
| --- | --- |
| Project | `schemaVersion` (integer `2`), `modId`, `modVersion`, `targetLocale`, `namespaces` |
| Namespace | `sourceLocale`, `sourcePath`, `entries` |
| Entry | `sourceText`, `sourceHistory`, `translation`, `bundledTranslation`, `state` |

`namespaces` maps resource namespace identifiers to namespace objects. `entries` maps exact translation keys to entry objects. `sourcePath` must be `assets/<namespace>/lang/<sourceLocale>.json`. Metadata and source text are strings. `sourceHistory` is an ordered array of previous source strings, oldest first. `translation` and `bundledTranslation` are each either a string or explicit JSON `null`.

The [synthetic version-one fixture](../src/test/resources/projects/v1.json) demonstrates pending work, an intentional empty translation, changed source text, and archived work. Its target strings are multilingual test data, not the mod's default interface.

## Entry states

| State | Meaning |
| --- | --- |
| `PENDING` | `null` means untranslated; a string means saved text awaiting confirmation |
| `TRANSLATED` | Deliberately supplied or accepted local text; a string is required, including `""` |
| `ARCHIVED` | The key disappeared from a validated source file; its history and local text remain |

Bundled translations are stored separately as reference material. Their presence does not make an entry locally translated. Identical source and target strings are allowed. Explicit editing can accept a translation or clear it to pending; archived entries cannot be edited through the model's editing action.

## Compatibility

Version-one files remain readable. Loading converts legacy `NEEDS_REVIEW` entries to `PENDING` in memory, preserving their text, source history, and references. Version-one pending entries must still have null translations; malformed legacy data is rejected. Reading alone never rewrites a file. The next successful save writes version two and retains the exact previous bytes through the normal backup contract. Older mod builds cannot read version-two files.

The editor uses one confirmation workflow for new text and retained translations whose source changed. There is no separate review state. Confirming one entry saves it as translated; other pending text remains unconfirmed. Save prompts before confirming all pending text. Entries with null translations are never accepted automatically, while explicitly edited empty strings can be confirmed.

## Reconciliation

| Incoming change | Result |
| --- | --- |
| New source key | Add as pending in every existing target project |
| Changed source text | Append previous source text to history, preserve local translation, return to pending confirmation |
| Previously saved source locale disappears | Refuse reconciliation; never switch the source locale silently |
| Removed key in a validated source file | Archive the entry without discarding translation or history |
| Archived key returns | Restore it as pending and retain prior local text |
| Changed bundled target translation | Update the reference only |
| Source discovery is incomplete or invalid, or a new namespace has no source selection | Skip reconciliation and retain the saved project |
| A previously known namespace disappears entirely | Skip reconciliation until absence can be distinguished reliably from unreadable discovery |
| A target file is invalid or changed externally | Refuse its update; continue processing other targets |

A sole non-English source is a fallback candidate, not proof of the author's original language. The editor asks for a source per namespace when no safe default exists. Once a project exists, its saved source locale remains authoritative even if English later appears. Current reconciliation is deliberately conservative: unresolved discovery warnings or errors block the entire mod's project update, including problems in a bundled target language.

The reader accepts at most 16 MiB of UTF-8 project JSON, 100,000 entries in total, 1,024 namespaces, 1,024 history strings per entry, and 1,048,576 UTF-16 code units per string. Nested JSON is limited to 12 levels and one million nodes. Hitting a limit fails the operation; existing history is not pruned automatically.

## Save contract

Loading returns an immutable project and a SHA-256 revision of its exact file bytes. Saving requires that revision, or an absent-file expectation for a new project. External edits are never silently merged. A cooperative writer lock and a final revision check protect against another instance of the project store changing the file.

Before replacing an existing project, storage retains its previous bytes in a backup. A validated temporary file is written in the same directory, forced to disk, and atomically moved into place. If the filesystem cannot provide the required operation, saving fails rather than falling back to truncating the original. Backups are retained without automatic pruning in this first version.

Backup filenames are `<targetLocale>.backup-<UUID>.json`. Hidden `.<targetLocale>.lock` files coordinate writers and `.<targetLocale>.tmp-<UUID>` files hold pending saves. Only exact target filenames participate in automatic synchronization. To restore a backup, stop the game, preserve the current file separately, and copy the chosen backup to `<targetLocale>.json`. Do not edit files while another program is saving them: a non-cooperative writer can still change a file between the final revision check and replacement.

Existing symbolic links, Windows junctions/reparse points, and unsafe path components are rejected. The store does not promise protection against a hostile process replacing directory ancestors during an operation, and filesystem checks cannot guarantee durability against every power-loss or hardware-failure scenario. Interrupted temporary files are not treated as projects or automatically promoted. Restoration is explicit; invalid current files are preserved for inspection.

## Runtime and export boundaries

Confirmed removal atomically moves an existing target to `<targetLocale>.removed-<UUID>.json` after the same revision and path checks. These files are excluded from active targets and can be restored like backups. Unsaved drafts are discarded only after confirmation.

Runtime application reads only the active target locale and entries marked `TRANSLATED`. It excludes pending, archived, stale, malformed, or ambiguous source entries. Accepted empty strings deliberately replace text with an empty value. Placeholder validation checks argument positions and conversions before Minecraft's own parser normalizes numeric placeholders. Stored Unicode, newlines, placeholders, and formatting remain unchanged by reconciliation.

Local overrides apply after bundled resources and resource packs. Because Minecraft's language keys are global, differing local values for the same key are all excluded and reported; identical values can coexist. Application diagnostics are available in the editor. Saving and application are separate operations: application failure does not undo a successful save.

Across all projects, projection permits at most 100,000 candidate origins and 16,777,216 UTF-16 code units of candidate keys and values. This text allowance is not a total heap bound. Exceeding either budget discards the complete local overlay and reports `RUNTIME_LIMIT`. Diagnostics are capped at 1,024, with a truncation marker when needed. Original project files remain unchanged by projection.

Export is not implemented. A future export must preserve deliberate translations and exclude unconfirmed text.

Import conflicts, attribution metadata, and automatic-translation provenance are future schema work. The codec rejects unknown fields so that saving cannot silently discard future metadata.

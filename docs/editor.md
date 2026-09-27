# Editor guide

The simplified editor targets Fabric on Minecraft 26.2. Automated checks do not replace in-game acceptance of the revised interface.

## Opening the editor

In Mod Menu 20.0.2 or 20.0.3, select a mod and click **T** beside its configuration button. Alternatively, select **ModLang Collective** and open its configuration to choose a mod. There are no title or pause menu buttons. Saved translations can apply without Mod Menu, but editor access requires it.

The T button is disabled when no supported texts are available, when source resources fail validation, or when no scan exists. Its tooltip explains the reason. The mod picker lists only eligible mods. An absent language file does not prove that every visible message is hardcoded; the editor only supports language-file entries, not literals embedded in code. A mod with both kinds of text remains available for its supported entries.

## Reading the original

**Original** stays pinned above the local language list. It opens the mod's source texts for reading without creating a translation file. English is preferred where available; ambiguous sources require an explicit reference-language choice. Choose a text to view it on the right. Original text is read-only, and Save and Confirm are unavailable in this mode. Switching between Original and a local language preserves unsaved translations.

## Translating

1. Choose **Add language** and select the language you want to translate into.
2. Selecting a new language creates its local translation file. Existing translations are opened without being overwritten.
3. Select a text from the list, read **Original**, and type your translation.
4. Choose **Confirm** to save the selected text as translated, or keep editing other texts.
5. Choose **Save** to save the language. If it contains unconfirmed translations, the editor asks before confirming all and saving.

Below the pinned Original entry, the language list contains local translations with readable language names. The text list combines resource namespaces automatically and uses original phrases as labels. Search finds original text, translated text, or technical keys. Lists scroll instead of requiring page buttons. Narrow screens may open the text picker separately.

Only one original reference is displayed for each entry. New projects prefer English; when unavailable, they use a sole available language or ask which original to use if the source is ambiguous. Existing projects retain their saved source choices. Bundled target-language lists and reference buttons are not shown.

## Language controls and layout

Hover a local language row to reveal Reset and Remove icons. They also appear on the selected or keyboard-focused row; press **R** to reset or **Delete** to remove that row's language. Both actions require confirmation, including a notice that unsaved changes will be discarded. The pinned Original entry never offers these actions.

Reset keeps the language and its source/history metadata, clears local translations, and retains archived entries as archives. The prior saved file is backed up before replacement. A failed reset leaves the existing file and in-memory draft intact. Remove retains the prior saved file as a recoverable removal backup and removes the language from the list. Neither action changes other languages or original mod resources.

In the three-column layout, language and text lists share the same top and bottom edges. Column headings and search controls follow consistent spacing, and progress appears above the aligned footer controls. Original/reference boxes fit short text and scroll only when the content exceeds available space. The translation field starts at a comfortable height and grows with its content. Confirmation messages use plain wrapped text over the normal screen background, with a compact centered layout and two columns of buttons where space permits. Help uses a top-aligned guide with direct access from the editor. Narrow layouts use one column. Long messages remain scrollable without moving the buttons off-screen.

## Confirmation and saving

- **Not translated**: no local text has been supplied.
- **Pending**: text has been entered but not confirmed.
- **Translated**: text has been confirmed.

Typing changes the entry to pending. Confirm saves the current entry as translated and preserves other entries' pending state. A saved pending translation remains available after reopening but does not override game text. Save asks **Confirm all and save** when any text remains unconfirmed; cancel leaves your work in the editor. Untouched entries are not automatically confirmed. An explicitly edited empty value can be confirmed as an intentional blank translation.

When a mod update changes the original, the previous translation is retained as pending. Version-one review entries are also loaded as pending; there is no separate review workflow. Removed source entries remain archived internally to preserve work.

Closing with unsaved changes offers save-all, discard, or cancel. Save-all follows the same pending-confirmation rule across edited languages. Successful saves are retained even if a later language fails to save. External file edits are never silently overwritten.

## Help and editing actions

Help opens directly from the editor and explains choosing a language, confirming text, saving, and applying translations in Minecraft. Technical details open from Help; Back and Escape return to Help, then to the editor, retaining drafts. Reports use a bounded content width and offer Copy text.

Reset translation is available beside the translation controls. Language reset and removal stay on language rows; the reset button displays R with an explanatory tooltip. Discard unsaved changes reloads the selected language after confirmation. Original mod files are never changed. An application failure offers a retry without requiring another save.

Minecraft can filter some pasted characters. If this would change the clipboard text, a confirmation offers to replace the complete translation field with the exact text instead.

## Using translations in the game

Set Minecraft to the language you translated. Confirmed, valid translations apply at startup and after saving. Reopen other mods' screens if they cache their labels. Pending, archived, stale, or invalid translations retain normal game text. Placeholder checks protect values such as `%s` and `%d`.

Application failure does not undo a successful save. Details and a retry action remain available when needed. See the [format guide](translation-format.md) for storage, migration, and recovery.

Automatic translation is planned only. No API is contacted and no provider is configured in this build. A future **Auto-translate** action belongs near the translation field and would produce pending text subject to the same confirmation flow.

## Manual acceptance checks

1. Confirm the T button and Mod Menu configuration entry work, with no title/pause buttons.
2. Open Original before creating a language and browse texts without generating files. Create a language, edit a text, switch to Original and back, and check the draft remains intact.
3. Scroll long language/text lists and use search and keyboard navigation at different GUI scales.
4. Edit several texts, confirm one, and reopen. Confirm the selected text is translated while the others remain pending and unapplied.
5. Save with pending text, cancel, then confirm all. Check that untouched entries remain untranslated.
6. Exercise source changes, legacy projects, empty translations, Unicode, multiline text, and placeholders.
7. Check close/save-all, conflicts, reload, removal, and backups; compare saved translations with visible in-game text.

## Available target languages

Add language offers contemporary languages and regional variants, including community-used planned languages supported by Minecraft 26.2. Historical, fictional, and joke options are excluded from new targets. Existing saved projects are not deleted or hidden, and original source references remain readable regardless of language. UI localization is separate from the languages that can be translated; see [localization coverage](localization.md).

## Original-only entries

Text that consists only of symbols, formatting, whitespace, or printf placeholders is omitted from both text lists and new translation projects. Exact matches for the installed mod ID or display name are also protected, ignoring case, surrounding whitespace, and recognized formatting. Names inside longer sentences remain editable, as do numbers and words in non-Latin scripts.

Existing translations for protected entries are retained as archived data during synchronization. They are excluded from progress, editing and confirmation; runtime also rejects them even if the saved project has not yet been synchronized. A protected source key cannot be overridden through another mod sharing the same global key. The original resources remain untouched, so Minecraft keeps its normal bundled/resource-pack value.

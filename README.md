# ModLang Collective

**Your mods, in more languages. Translated by the community.**

ModLang Collective is a Minecraft mod project by **Kito Mods**, designed to help players create, edit, and share translations for the mods they love through an in-game editor.

> **Status: early development.** The Fabric bootstrap, discovery, project storage, and in-game editor are implemented. Saved translations now overlay the active game language. Automated checks cover projection and integration contracts; the revised buttons and runtime application still need in-game verification. Sharing and automatic translation remain planned.

## AI assistance

ModLang Collective was conceived and directed by Kito Mods. OpenAI Codex generated substantial portions of the code, documentation, and draft interface translations. Kito Mods makes the product decisions and performs in-game testing. The mod does not use generative AI at runtime.

## The idea

Select a mod, choose a target language, and work with the original text and your translation side by side. Keep translations organized by mod and language, then share them with other players.

## Implemented foundation

- Discover canonical language JSON files from loaded Fabric mod roots into an immutable in-memory catalog.
- Preserve mod IDs, namespaces, locales, and source-selection evidence separately.
- Report malformed resources, missing English, and ambiguous sources without modifying mod JARs.
- Run an offline audit of top-level mod JARs for development.
- Store versioned local projects with revision checks, backups, and atomic replacement.
- Update existing target projects while preserving translations, source history, and archived entries.
- Browse the pinned Original view without creating a translation, then edit local languages with scrolling text lists, confirmation, and recoverable removal.
- Disable translation access when no supported texts are found or language resources cannot be validated, with an explanatory tooltip.
- Open the editor through Mod Menu, with a T button beside the selected mod's configuration button in versions 20.0.2 and 20.0.3.
- Apply accepted, validated local translations at language load and after saving; pending or stale entries retain normal game text.

See [development](docs/development.md) for build and validation commands.

## Planned features

- Import and export translation projects with conflict handling.
- Export standard resource packs so players can use completed translations without installing the editor.

Automatic translation is a future extension. It will require an explicit action, explain which text is sent to the selected provider, and leave results pending confirmation. No provider or free service availability is promised.

## Initial target

The initial build targets **Minecraft Java Edition 26.2 with Fabric**. Optional **Mod Menu** integration provides editor access. Internal screen hooks support **20.0.2 and 20.0.3**; the configuration button for ModLang Collective also opens the editor through the official Mod Menu API. Runtime compatibility still requires in-game validation. Support for additional Minecraft versions and loaders will be evaluated later.

The editor runs on the client and stores local work offline without a hosted service. See the [editor guide](docs/editor.md) for access, save behavior, and current limitations.

## Language policy

English is the default language for the mod's own interface, source comments, documentation, repository metadata, and release pages. The interface uses bundled translations for Minecraft's selected language, with English as fallback. New translation projects offer contemporary languages and their regional variants, including community-used planned languages. Fictional, historical, and joke options are excluded from new targets; existing projects and original references remain readable. See [localization coverage](docs/localization.md) for translated UI locales and review status.

## Scope and limitations

The initial scope is translation-key-based language resources. Literal strings embedded in code, server-generated text, datapacks, books, and other custom formats may require separate support. Installing the editor will not automatically make every text in every mod translatable.

Community translations will be stored separately from the original mod files. The project will not redistribute third-party mod JARs or assume ownership of their content.

## Documentation

- [Editor guide and manual validation](docs/editor.md)
- [Project documentation](docs/README.md)
- [Architecture and design boundaries](docs/architecture.md)
- [Translation data model](docs/translation-format.md)
- [Development and repository checks](docs/development.md)
- [Roadmap](docs/roadmap.md)
- [Contributing](CONTRIBUTING.md)

## License

The project code is licensed under the [MIT License](LICENSE). Third-party mods and translation material retain their respective licenses. Permission and attribution requirements must be checked before redistributing third-party content.

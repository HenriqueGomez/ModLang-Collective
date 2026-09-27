# Contributing

Thank you for helping make modded Minecraft accessible in more languages.

The project is in early development; discovery, local project storage, and an initial editor are implemented; in-game verification remains outstanding. Start with the [roadmap](docs/roadmap.md) and discuss substantial feature or architecture changes in an issue before implementing them.

## Communication and language

Use English for repository discussions, pull requests, documentation, code comments, identifiers, and the mod's default interface. Translation contributions may naturally contain their target language. Keep translation keys stable and preserve placeholders and formatting codes.

## Development contributions

1. Keep changes focused on one problem.
2. Read the relevant design document and explain any changes to its contracts.
3. Add meaningful tests for data preservation, import conflicts, and other behavioral changes.
4. Run the repository checks documented in [development](docs/development.md).
5. Describe the resulting behavior and the checks actually performed. Distinguish automated checks from in-game observations.

Use Java 25 and run `./gradlew build` (Windows: `gradlew.bat build`). See the development guide for offline auditing and repository checks.

## Translation contributions

The local project format is versioned and documented in the translation-format guide. There is no import workflow yet; do not treat project JSON as a Minecraft resource-pack language file.

When reporting a translation problem, include the mod ID, mod version, Minecraft version, loader, target language, and translation key when known. Explain the context and expected meaning. Remove personal information from screenshots and logs.

Only contribute material you are entitled to share. Credit existing translators and respect the original mod's license. Machine-generated translations must be labeled and reviewed for context, terminology, placeholders, and formatting.

## Repository boundaries

Do not commit private development settings, credentials, installed mod JARs, world saves, extracted third-party assets, raw research downloads, personal paths, or local runtime logs. Use synthetic, minimal fixtures for public tests.

Reusable scripts belong in `scripts/`. Temporary experiments and machine-specific tools stay outside version control. The repository checker is a useful guard, not a substitute for reviewing the diff.

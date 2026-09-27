package dev.kitomods.modlangcollective.project;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static dev.kitomods.modlangcollective.discovery.DiscoveryCatalog.*;
import static dev.kitomods.modlangcollective.project.TranslationProject.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectEngineTest {
    @Test void createKeepsBundledReferenceSeparateAndPreservesNamespaces() {
        var source = catalog(Map.of("key", "English %s\n§a"), Map.of("key", "Bundled"));
        var project = ProjectEngine.create(source, "pt_br");
        assertEquals("fixture_mod", project.modId());
        assertEquals("assets/other/lang/en_us.json", project.namespaces().get("other").sourcePath());
        var entry = project.namespaces().get("other").entries().get("key");
        assertEquals(State.PENDING, entry.state());
        assertNull(entry.translation());
        assertEquals("Bundled", entry.bundledTranslation());
        assertThrows(UnsupportedOperationException.class, () -> project.namespaces().clear());
        assertThrows(UnsupportedOperationException.class, () -> entry.sourceHistory().add("x"));
    }

    @Test void mergeRetainsTranslationHistoryAndArchivesThenUnconfirmsReaddedKeys() {
        var project = edited(ProjectEngine.create(catalog(Map.of("key", "Original"), Map.of()), "pt_br"), "key", "Local");
        var changed = ProjectEngine.merge(project, catalog(Map.of("key", "Changed", "new", "New"), Map.of("key", "New bundled")));
        Entry entry = changed.namespaces().get("other").entries().get("key");
        assertEquals("Local", entry.translation());
        assertEquals(List.of("Original"), entry.sourceHistory());
        assertEquals(State.PENDING, entry.state());
        assertEquals(State.PENDING, changed.namespaces().get("other").entries().get("new").state());
        var removed = ProjectEngine.merge(changed, catalog(Map.of(), Map.of()));
        assertEquals(State.ARCHIVED, removed.namespaces().get("other").entries().get("key").state());
        var readded = ProjectEngine.merge(removed, catalog(Map.of("key", "Changed"), Map.of()));
        entry = readded.namespaces().get("other").entries().get("key");
        assertEquals(State.PENDING, entry.state());
        assertEquals("Local", entry.translation());
        assertEquals(List.of("Original"), entry.sourceHistory());
    }

    @Test void bundledChangesDoNotCreateOrReplaceLocalWork() {
        var project = edited(ProjectEngine.create(catalog(Map.of("key", "Source"), Map.of("key", "Bundled")), "pt_br"), "key", "");
        var changed = ProjectEngine.merge(project, catalog(Map.of("key", "Source"), Map.of("key", "Updated")));
        Entry entry = changed.namespaces().get("other").entries().get("key");
        assertEquals("", entry.translation());
        assertEquals(State.TRANSLATED, entry.state());
        assertEquals("Updated", entry.bundledTranslation());
        assertEquals(List.of(), entry.sourceHistory());
    }

    @Test void creationOmitsProtectedSourceEntriesAndMergeArchivesExistingWork() {
        var catalog = namedCatalog("Example Mod", Map.of("name", "Example Mod", "icon", "★", "word", "Hello"));
        assertEquals(3, catalog.counts().entryCount(), "Discovery retains raw evidence and counts.");
        assertEquals(Map.of("name", "Example Mod", "icon", "★", "word", "Hello"),
                catalog.namespaces().getFirst().resources().getFirst().entries());
        var created = ProjectEngine.create(catalog, "pt_br");
        assertEquals(java.util.Set.of("word"), created.namespaces().get("other").entries().keySet());

        var oldEntries = new TreeMap<>(created.namespaces().get("other").entries());
        oldEntries.put("name", new Entry("Example Mod", List.of("Old Name"), "Meu Mod", null, State.TRANSLATED));
        oldEntries.put("icon", new Entry("★", List.of(), "☆", null, State.TRANSLATED));
        var old = new TranslationProject(SCHEMA_VERSION, "fixture_mod", "1", "pt_br",
                Map.of("other", new Namespace("en_us", "assets/other/lang/en_us.json", oldEntries)));
        var merged = ProjectEngine.merge(old, catalog);
        assertEquals(State.ARCHIVED, merged.namespaces().get("other").entries().get("name").state());
        assertEquals("Meu Mod", merged.namespaces().get("other").entries().get("name").translation());
        assertEquals(List.of("Old Name"), merged.namespaces().get("other").entries().get("name").sourceHistory());
        assertEquals(State.ARCHIVED, merged.namespaces().get("other").entries().get("icon").state());
        assertEquals("☆", merged.namespaces().get("other").entries().get("icon").translation());
    }

    @Test void sourceChangeWithoutLocalTextRemainsUntranslated() {
        var project = ProjectEngine.create(catalog(Map.of("key", "Source"), Map.of()), "pt_br");
        var changed = ProjectEngine.merge(project, catalog(Map.of("key", "Changed"), Map.of()));
        Entry entry = changed.namespaces().get("other").entries().get("key");
        assertEquals(State.PENDING, entry.state());
        assertNull(entry.translation());
    }

    @Test void incompleteAmbiguousConflictingAndMissingNamespacesCannotInferRemovals() {
        DiscoveryCatalog source = catalog(Map.of("key", "Source"), Map.of());
        var project = ProjectEngine.create(source, "pt_br");
        var issue = new DiscoveryIssue(Severity.WARNING, "SYMLINK_SKIPPED", 0, "assets", "Incomplete");
        assertThrows(IllegalArgumentException.class, () -> ProjectEngine.merge(project,
                new DiscoveryCatalog(source.modId(), "2", source.namespaces(), List.of(issue), source.counts())));
        NamespaceCatalog old = source.namespaces().getFirst();
        var ambiguous = new NamespaceCatalog("other", old.resources(), new SourceSelection(SourceState.AMBIGUOUS, Optional.empty(), false));
        assertThrows(IllegalArgumentException.class, () -> ProjectEngine.create(
                new DiscoveryCatalog(source.modId(), "2", List.of(ambiguous), List.of(), source.counts()), "pt_br"));
        assertEquals("en_us", ProjectEngine.merge(project,
                new DiscoveryCatalog(source.modId(), "2", List.of(ambiguous), List.of(), source.counts()))
                .namespaces().get("other").sourceLocale());
        var duplicate = new NamespaceCatalog("other", List.of(old.resources().getFirst(), old.resources().getFirst()), old.source());
        assertThrows(IllegalArgumentException.class, () -> ProjectEngine.merge(project,
                new DiscoveryCatalog(source.modId(), "2", List.of(duplicate), List.of(), source.counts())));
        var missing = new DiscoveryCatalog(source.modId(), "2", List.of(), List.of(), new Counts(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ProjectEngine.merge(project, missing));
        assertThrows(IllegalArgumentException.class, () -> ProjectEngine.create(missing, "pt_br"));
        assertEquals(State.PENDING, project.namespaces().get("other").entries().get("key").state());
    }

    @Test void missingSavedSourceCannotSilentlySwitchLanguages() {
        var project = ProjectEngine.create(catalog(Map.of("key", "Source"), Map.of()), "pt_br");
        var resource = new LanguageResource(0, "assets/other/lang/de_de.json", "de_de", Map.of("key", "Source"), ResourceStatus.VALID);
        var namespace = new NamespaceCatalog("other", List.of(resource),
                new SourceSelection(SourceState.SINGLE_LOCALE_FALLBACK, Optional.of("de_de"), true));
        var source = new DiscoveryCatalog("fixture_mod", "2", List.of(namespace), List.of(), new Counts(1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> ProjectEngine.merge(project, source));
    }

    @Test void savedManualSourceSurvivesAmbiguousCatalogAndLaterEnglishAddition() {
        var german = new LanguageResource(0, "assets/other/lang/de_de.json", "de_de", Map.of("key", "Quelle"), ResourceStatus.VALID);
        var french = new LanguageResource(0, "assets/other/lang/fr_fr.json", "fr_fr", Map.of("key", "Texte"), ResourceStatus.VALID);
        var ambiguous = new NamespaceCatalog("other", List.of(german, french),
                new SourceSelection(SourceState.AMBIGUOUS, Optional.empty(), false));
        var catalog = new DiscoveryCatalog("fixture_mod", "1", List.of(ambiguous), List.of(), new Counts(1, 2, 2, 0));
        var saved = dev.kitomods.modlangcollective.editor.SourceChoices.create(catalog, "pt_br", Map.of("other", "de_de"));
        assertEquals(saved, ProjectEngine.merge(saved, catalog));
        var english = new LanguageResource(0, "assets/other/lang/en_us.json", "en_us", Map.of("key", "English"), ResourceStatus.VALID);
        var updated = new NamespaceCatalog("other", List.of(german, french, english),
                new SourceSelection(SourceState.ENGLISH, Optional.of("en_us"), true));
        var merged = ProjectEngine.merge(saved, new DiscoveryCatalog("fixture_mod", "2", List.of(updated), List.of(), new Counts(1, 3, 3, 0)));
        assertEquals("de_de", merged.namespaces().get("other").sourceLocale());
        assertEquals("Quelle", merged.namespaces().get("other").entries().get("key").sourceText());
        assertEquals(State.PENDING, merged.namespaces().get("other").entries().get("key").state());
    }

    static DiscoveryCatalog catalog(Map<String, String> source, Map<String, String> bundled) {
        var resources = List.of(new LanguageResource(0, "assets/other/lang/en_us.json", "en_us", source, ResourceStatus.VALID),
                new LanguageResource(0, "assets/other/lang/pt_br.json", "pt_br", bundled, ResourceStatus.VALID));
        var namespace = new NamespaceCatalog("other", resources,
                new SourceSelection(SourceState.ENGLISH, Optional.of("en_us"), true));
        return new DiscoveryCatalog("fixture_mod", "1", List.of(namespace), List.of(), new Counts(1, 2, source.size() + bundled.size(), 0));
    }

    private static DiscoveryCatalog namedCatalog(String displayName, Map<String, String> source) {
        var resources = List.of(new LanguageResource(0, "assets/other/lang/en_us.json", "en_us", source, ResourceStatus.VALID));
        var namespace = new NamespaceCatalog("other", resources,
                new SourceSelection(SourceState.ENGLISH, Optional.of("en_us"), true));
        return new DiscoveryCatalog("fixture_mod", "1", List.of(namespace), List.of(),
                new Counts(1, 1, source.size(), 0), displayName);
    }

    static TranslationProject edited(TranslationProject project, String key, String value) {
        Namespace namespace = project.namespaces().get("other");
        var entries = new java.util.TreeMap<>(namespace.entries());
        entries.put(key, entries.get(key).withTranslation(value));
        return new TranslationProject(TranslationProject.SCHEMA_VERSION, project.modId(), project.modVersion(), project.targetLocale(),
                Map.of("other", new Namespace(namespace.sourceLocale(), namespace.sourcePath(), entries)));
    }
}

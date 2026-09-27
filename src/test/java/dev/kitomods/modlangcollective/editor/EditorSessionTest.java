package dev.kitomods.modlangcollective.editor;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.project.ProjectStore;
import dev.kitomods.modlangcollective.project.TranslationProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static dev.kitomods.modlangcollective.discovery.DiscoveryCatalog.*;
import static dev.kitomods.modlangcollective.project.TranslationProject.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorSessionTest {
    @TempDir Path directory;

    @Test void protectedLegacyEntriesStayStoredButCannotBeEditedOrConfirmed() throws Exception {
        var store = new ProjectStore(directory);
        var entries = new java.util.TreeMap<String, Entry>();
        entries.put("name", new Entry("Fixture Mod", List.of(), "Renamed", null, State.PENDING));
        entries.put("symbol", new Entry("→", List.of(), "Changed", null, State.TRANSLATED));
        entries.put("normal", new Entry("Fixture Mod settings", List.of(), null, null, State.PENDING));
        var legacy = new TranslationProject(SCHEMA_VERSION, "fixture_mod", "1", "pt_br",
                Map.of("fixture", new Namespace("en_us", "assets/fixture/lang/en_us.json", entries)));
        store.save(legacy, null);
        var raw = catalog(namespace("fixture", "en_us", Map.of("name", "Fixture Mod", "symbol", "→", "normal", "Fixture Mod settings")));
        var named = new DiscoveryCatalog(raw.modId(), raw.modVersion(), raw.namespaces(), raw.issues(), raw.counts(), "Fixture Mod");
        var session = EditorSession.load(store, named, "pt_br");
        assertEquals(List.of("normal"), session.visibleEntries().stream().map(EditorSession.EntryRow::key).toList());
        assertEquals(1, session.progress().active());
        assertEquals(0, session.pendingConfirmations());
        assertThrows(IllegalArgumentException.class, () -> session.selectEntry("fixture", "name"));
        assertThrows(IllegalArgumentException.class, () -> session.selectKey("symbol"));
        assertThrows(IllegalStateException.class, () -> session.stageTranslation("fixture", "name", "Wrong"));
        session.confirmPendingTranslations();
        session.save();
        assertEquals(legacy, store.load("fixture_mod", "pt_br").orElseThrow().project());
        session.discard();
        assertEquals(0, session.pendingConfirmations());
        session.setFilter(EditorSession.Filter.ARCHIVED);
        assertTrue(session.visibleEntries().isEmpty());
    }

    @Test void explicitEmptyAcceptanceDiffersFromClearAndPreservesUnicodeAndNewlines() throws Exception {
        var store = new ProjectStore(directory);
        var session = EditorSession.create(store, catalog(namespace("other", "en_us", Map.of("key", "Source"))), "pt_br", Map.of());
        assertTrue(session.dirty());
        assertFalse(session.persisted());
        assertFalse(Files.exists(directory.resolve("fixture_mod/pt_br.json")));
        session.save();
        assertFalse(session.dirty());
        assertTrue(session.persisted());
        TranslationProject before = session.project();
        session.acceptTranslation("");
        assertEquals(State.TRANSLATED, session.selectedEntry().orElseThrow().state());
        assertEquals("", session.selectedEntry().orElseThrow().translation());
        assertEquals(State.PENDING, before.namespaces().get("other").entries().get("key").state());
        assertTrue(session.dirty());
        session.clearTranslation();
        assertNull(session.selectedEntry().orElseThrow().translation());
        assertFalse(session.dirty());
        String text = "Ação 日本語 😀\nSecond %1$s line\r\n§aEnd";
        session.acceptTranslation(text);
        session.save();
        var reloaded = EditorSession.load(store, "fixture_mod", "pt_br");
        assertEquals(text, reloaded.selectedEntry().orElseThrow().translation());
        assertFalse(reloaded.dirty());
        assertThrows(NullPointerException.class, () -> reloaded.acceptTranslation(null));
    }

    @Test void saveConflictRetainsLocalDraftAndDiscardReloadsExternalWork() throws Exception {
        var store = new ProjectStore(directory);
        var original = EditorSession.create(store, catalog(namespace("other", "en_us", Map.of("key", "Source"))), "pt_br", Map.of());
        original.save();
        var external = EditorSession.load(store, "fixture_mod", "pt_br");
        String revision = original.revision();
        original.acceptTranslation("Unsaved local");
        external.acceptTranslation("Saved externally");
        external.save();
        assertThrows(ProjectStore.ConflictException.class, original::save);
        assertTrue(original.dirty());
        assertEquals(revision, original.revision());
        assertEquals("Unsaved local", original.selectedEntry().orElseThrow().translation());
        original.discard();
        assertFalse(original.dirty());
        assertEquals("Saved externally", original.selectedEntry().orElseThrow().translation());
        assertEquals(external.revision(), original.revision());
    }

    @Test void createOnlyConflictAndMissingExternalFileDoNotLoseDrafts() throws Exception {
        var store = new ProjectStore(directory);
        var source = catalog(namespace("other", "en_us", Map.of("key", "Source")));
        var first = EditorSession.create(store, source, "pt_br", Map.of());
        var second = EditorSession.create(store, source, "pt_br", Map.of());
        second.acceptTranslation("Draft");
        second.discard();
        assertNull(second.selectedEntry().orElseThrow().translation());
        assertTrue(second.dirty());
        first.save();
        second.acceptTranslation("Draft");
        assertThrows(ProjectStore.ConflictException.class, second::save);
        assertEquals("Draft", second.selectedEntry().orElseThrow().translation());
        first.acceptTranslation("Local retained");
        Files.delete(directory.resolve("fixture_mod/pt_br.json"));
        assertThrows(java.nio.file.NoSuchFileException.class, first::discard);
        assertEquals("Local retained", first.selectedEntry().orElseThrow().translation());
        assertTrue(first.dirty());
    }

    @Test void combinedListKeepsDuplicateKeysDistinctAndHidesArchives() throws Exception {
        var store = new ProjectStore(directory);
        var states = Map.of("shared", new Entry("Source", List.of(), null, "Bundled reference", State.PENDING),
                "changed", new Entry("Updated", List.of("Before"), "Retained", null, State.PENDING),
                "archived", new Entry("Removed", List.of(), "Archived local", null, State.ARCHIVED));
        var project = new TranslationProject(SCHEMA_VERSION, "fixture_mod", "1", "pt_br", Map.of(
                "first", new Namespace("en_us", "assets/first/lang/en_us.json", states),
                "second", new Namespace("en_us", "assets/second/lang/en_us.json", Map.of(
                        "shared", new Entry("Other source", List.of(), null, null, State.PENDING)))));
        store.save(project, null);
        var session = EditorSession.load(store, "fixture_mod", "pt_br");
        assertEquals(3, session.visibleEntries().size());
        session.selectEntry("first", "shared");
        session.acceptTranslation("Local first");
        session.selectEntry("second", "shared");
        assertNull(session.selectedEntry().orElseThrow().translation());
        session.setSearch("retained");
        assertEquals("changed", session.selectedKey());
        session.setSearch("");
        session.setFilter(EditorSession.Filter.PENDING);
        assertEquals(1, session.visibleEntries().size());
        session.acceptTranslation("Confirmed");
        assertTrue(session.visibleEntries().isEmpty());
        assertTrue(session.selectedEntry().isEmpty());
        session.setFilter(EditorSession.Filter.ARCHIVED);
        assertEquals("archived", session.selectedKey());
        assertThrows(IllegalStateException.class, () -> session.acceptTranslation("Cannot change"));
        assertThrows(IllegalStateException.class, () -> session.stageTranslation("Cannot change"));
        assertEquals(new EditorSession.Progress(1, 2, 1), session.progress());
        session.setFilter(EditorSession.Filter.NOT_TRANSLATED);
        assertEquals("second", session.selectedNamespace());
        assertEquals("shared", session.selectedKey());
        assertThrows(IllegalArgumentException.class, () -> session.selectEntry("missing", "shared"));
        assertThrows(IllegalArgumentException.class, () -> session.selectEntry("second", "missing"));
    }

    @Test void pendingTextSurvivesSaveAndConfirmationDoesNotAcceptUntouchedEntries() throws Exception {
        var store = new ProjectStore(directory);
        var source = catalog(namespace("other", "en_us", Map.of("first", "One", "empty", "Two", "untouched", "Three")));
        var session = EditorSession.create(store, source, "pt_br", Map.of());
        session.selectEntry("other", "first");
        session.stageTranslation("Um");
        session.stageTranslation("other", "empty", "");
        assertEquals("first", session.selectedKey());
        assertEquals(2, session.pendingConfirmations());
        session.save();
        session = EditorSession.load(store, "fixture_mod", "pt_br");
        assertEquals(2, session.pendingConfirmations());
        session.selectEntry("other", "first");
        session.acceptTranslation("Um");
        session.save();
        var loaded = EditorSession.load(store, "fixture_mod", "pt_br");
        assertEquals(1, loaded.pendingConfirmations());
        loaded.confirmPendingTranslations();
        loaded.save();
        var entries = EditorSession.load(store, "fixture_mod", "pt_br").project().namespaces().get("other").entries();
        assertEquals(State.TRANSLATED, entries.get("first").state());
        assertEquals(State.TRANSLATED, entries.get("empty").state());
        assertEquals("", entries.get("empty").translation());
        assertEquals(State.PENDING, entries.get("untouched").state());
        assertNull(entries.get("untouched").translation());
        loaded.selectEntry("other", "first");
        loaded.stageTranslation("Changed again");
        assertEquals(State.PENDING, loaded.selectedEntry().orElseThrow().state());
        assertEquals(1, loaded.pendingConfirmations());
    }

    @Test void stagingUnderTranslatedFilterDoesNotJumpToAnotherTextWhileTyping() throws Exception {
        var session = EditorSession.create(new ProjectStore(directory),
                catalog(namespace("other", "en_us", Map.of("a", "A", "b", "B"))), "pt_br", Map.of());
        session.selectEntry("other", "a");
        session.acceptTranslation("First");
        session.selectEntry("other", "b");
        session.acceptTranslation("Second");
        session.setFilter(EditorSession.Filter.TRANSLATED);
        session.selectEntry("other", "a");
        session.stageTranslation("Changed");
        session.stageTranslation("Changed again");
        assertEquals("a", session.selectedKey());
        assertEquals("Changed again", session.selectedEntry().orElseThrow().translation());
        assertEquals("Second", session.project().namespaces().get("other").entries().get("b").translation());
        assertEquals(List.of("b"), session.visibleEntries().stream().map(EditorSession.EntryRow::key).toList());
    }

    @Test void selectedNonEnglishSourceSurvivesSaveAndLoad() throws Exception {
        var store = new ProjectStore(directory);
        var session = EditorSession.create(store, ambiguousCatalog(), "pt_br", Map.of("other", "fr_fr"));
        assertEquals("fr_fr", session.project().namespaces().get("other").sourceLocale());
        assertEquals("Bonjour", session.selectedEntry().orElseThrow().sourceText());
        session.save();
        var loaded = EditorSession.load(store, "fixture_mod", "pt_br");
        assertEquals("fr_fr", loaded.project().namespaces().get("other").sourceLocale());
        assertEquals("Bonjour", loaded.selectedEntry().orElseThrow().sourceText());
    }

    @Test void removalRetainsSavedFileAndMakesSessionInactive() throws Exception {
        var store = new ProjectStore(directory);
        var session = EditorSession.create(store, catalog(namespace("other", "en_us", Map.of("key", "Source"))), "pt_br", Map.of());
        assertThrows(IllegalStateException.class, session::remove);
        session.save();
        session.acceptTranslation("Unsaved local work");
        Path retained = session.remove();
        assertTrue(Files.isRegularFile(retained));
        assertTrue(store.listTargets("fixture_mod").isEmpty());
        assertFalse(session.persisted());
        assertFalse(session.dirty());
        assertThrows(IllegalStateException.class, session::save);
        assertThrows(IllegalStateException.class, session::discard);
        assertThrows(IllegalStateException.class, () -> session.acceptTranslation("No resurrection"));
    }

    @Test void wholeLanguageResetKeepsSourcesArchivesAndOtherLanguagesAndBacksUpSavedText() throws Exception {
        var store = new ProjectStore(directory);
        var entries = Map.of(
                "confirmed", new Entry("Source", List.of("Before"), "Saved", "Bundled", State.TRANSLATED),
                "pending", new Entry("Other", List.of(), "Draft", null, State.PENDING),
                "archived", new Entry("Removed", List.of(), "Archived", null, State.ARCHIVED));
        var project = new TranslationProject(SCHEMA_VERSION, "fixture_mod", "1", "pt_br", Map.of(
                "first", new Namespace("en_us", "assets/first/lang/en_us.json", entries),
                "second", new Namespace("fr_fr", "assets/second/lang/fr_fr.json", entries)));
        store.save(project, null);
        var other = new TranslationProject(SCHEMA_VERSION, "fixture_mod", "1", "de_de", project.namespaces());
        store.save(other, null);
        byte[] before = Files.readAllBytes(directory.resolve("fixture_mod/pt_br.json"));
        var session = EditorSession.load(store, "fixture_mod", "pt_br");
        session.stageTranslation("first", "pending", "Unsaved draft");
        session.resetAndSave();
        assertFalse(session.dirty());
        assertEquals(List.of("de_de", "pt_br"), store.listTargets("fixture_mod"));
        var reset = store.load("fixture_mod", "pt_br").orElseThrow().project();
        for (var namespace : reset.namespaces().values()) {
            assertTrue(namespace.entries().values().stream().allMatch(entry -> entry.translation() == null));
            assertEquals(State.ARCHIVED, namespace.entries().get("archived").state());
            assertEquals(State.PENDING, namespace.entries().get("confirmed").state());
            assertEquals(List.of("Before"), namespace.entries().get("confirmed").sourceHistory());
            assertEquals("Bundled", namespace.entries().get("confirmed").bundledTranslation());
        }
        assertEquals(other, store.load("fixture_mod", "de_de").orElseThrow().project());
        try (var paths = Files.list(directory.resolve("fixture_mod"))) {
            var backup = paths.filter(path -> path.getFileName().toString().startsWith("pt_br.backup-")).findFirst().orElseThrow();
            assertArrayEquals(before, Files.readAllBytes(backup));
        }
    }

    @Test void resetConflictRetainsUnsavedDraftAndExternalFile() throws Exception {
        var store = new ProjectStore(directory);
        var session = EditorSession.create(store, catalog(namespace("other", "en_us", Map.of("key", "Source"))), "pt_br", Map.of());
        session.save();
        var external = EditorSession.load(store, "fixture_mod", "pt_br");
        session.stageTranslation("Keep draft");
        var draft = session.project();
        external.acceptTranslation("External saved");
        external.save();
        assertThrows(ProjectStore.ConflictException.class, session::resetAndSave);
        assertEquals(draft, session.project());
        assertTrue(session.dirty());
        assertEquals("External saved", store.load("fixture_mod", "pt_br").orElseThrow().project()
                .namespaces().get("other").entries().get("key").translation());
    }

    static DiscoveryCatalog ambiguousCatalog() {
        var resources = List.of(resource("other", "de_de", Map.of("key", "Hallo")),
                resource("other", "fr_fr", Map.of("key", "Bonjour")));
        return catalog(new NamespaceCatalog("other", resources,
                new SourceSelection(SourceState.AMBIGUOUS, Optional.empty(), false)));
    }

    static DiscoveryCatalog catalog(NamespaceCatalog... namespaces) {
        return new DiscoveryCatalog("fixture_mod", "1", List.of(namespaces), List.of(), new Counts(namespaces.length, 0, 0, 0));
    }

    static NamespaceCatalog namespace(String namespace, String locale, Map<String, String> entries) {
        return new NamespaceCatalog(namespace, List.of(resource(namespace, locale, entries)),
                new SourceSelection(locale.equals("en_us") ? SourceState.ENGLISH : SourceState.SINGLE_LOCALE_FALLBACK,
                        Optional.of(locale), true));
    }

    static LanguageResource resource(String namespace, String locale, Map<String, String> entries) {
        return new LanguageResource(0, "assets/" + namespace + "/lang/" + locale + ".json", locale, entries, ResourceStatus.VALID);
    }
}

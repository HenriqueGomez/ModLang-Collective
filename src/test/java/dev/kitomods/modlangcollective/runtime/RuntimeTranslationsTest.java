package dev.kitomods.modlangcollective.runtime;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.discovery.LanguageDiscovery;
import dev.kitomods.modlangcollective.discovery.ModSource;
import dev.kitomods.modlangcollective.project.ProjectEngine;
import dev.kitomods.modlangcollective.project.ProjectStore;
import dev.kitomods.modlangcollective.project.TranslationProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static dev.kitomods.modlangcollective.project.TranslationProject.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeTranslationsTest {
    @TempDir Path temporary;

    private DiscoveryCatalog catalog(String modId, String namespace, String json) throws Exception {
        Path root = temporary.resolve("sources/" + modId);
        Path language = root.resolve("assets/" + namespace + "/lang/en_us.json");
        Files.createDirectories(language.getParent());
        Files.writeString(language, json);
        return new LanguageDiscovery().scan(new ModSource(modId, "1", List.of(root)));
    }

    private static TranslationProject translated(DiscoveryCatalog catalog, String locale,
                                                 Map<String, String> values) {
        var project = ProjectEngine.create(catalog, locale);
        var namespaces = new TreeMap<String, Namespace>();
        project.namespaces().forEach((name, namespace) -> {
            var entries = new TreeMap<>(namespace.entries());
            values.forEach((key, value) -> {
                if (entries.containsKey(key)) entries.put(key, entries.get(key).withTranslation(value));
            });
            namespaces.put(name, new Namespace(namespace.sourceLocale(), namespace.sourcePath(), entries));
        });
        return new TranslationProject(SCHEMA_VERSION, project.modId(), project.modVersion(), locale, namespaces);
    }

    private ProjectStore store() { return new ProjectStore(temporary.resolve("projects")); }

    @Test void activeLocaleOnlyPreservesRawTextAndIntentionalEmpty() throws Exception {
        var catalog = catalog("example", "different", "{\"key\":\"Text\",\"empty\":\"Empty\",\"pending\":\"Pending\"}");
        var store = store();
        store.save(translated(catalog, "pt_br", Map.of("key", "§aTexto\n日本語", "empty", "")), null);
        store.save(translated(catalog, "fr_fr", Map.of("key", "French")), null);
        var snapshot = RuntimeTranslations.load(store, List.of(catalog), "pt_br");
        assertEquals(Map.of("key", "§aTexto\n日本語", "empty", ""), snapshot.overrides());
        assertEquals(2, snapshot.appliedEntries());
        assertEquals(1, snapshot.loadedProjects());
        assertTrue(snapshot.issues().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.overrides().put("other", "x"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.issues().clear());
        assertTrue(RuntimeTranslations.load(store, List.of(catalog), "de_de").overrides().isEmpty());
    }

    @Test void pendingAndArchivedEntriesNeverApplyBundledOrRetainedText() throws Exception {
        var catalog = catalog("example", "example", "{\"pending\":\"P\",\"review\":\"R\",\"archived\":\"A\",\"ok\":\"O\"}");
        var entries = Map.of(
                "pending", new Entry("P", List.of(), null, "Bundled", State.PENDING),
                "review", new Entry("R", List.of(), "Review", null, State.PENDING),
                "archived", new Entry("A", List.of(), "Archived", null, State.ARCHIVED),
                "ok", new Entry("O", List.of(), "Accepted", null, State.TRANSLATED));
        var project = new TranslationProject(SCHEMA_VERSION, "example", "1", "pt_br",
                Map.of("example", new Namespace("en_us", "assets/example/lang/en_us.json", entries)));
        var store = store();
        store.save(project, null);
        assertEquals(Map.of("ok", "Accepted"), RuntimeTranslations.load(store, List.of(catalog), "pt_br").overrides());
    }

    @Test void changedAndRemovedSourceKeysAreSkippedWithoutRewritingProjects() throws Exception {
        var original = catalog("example", "example", "{\"changed\":\"Old\",\"removed\":\"Gone\",\"ok\":\"Same\"}");
        var store = store();
        String revision = store.save(translated(original, "pt_br", Map.of("changed", "A", "removed", "B", "ok", "C")), null);
        var current = catalog("example", "example", "{\"changed\":\"New\",\"ok\":\"Same\"}");
        var snapshot = RuntimeTranslations.load(store, List.of(current), "pt_br");
        assertEquals(Map.of("ok", "C"), snapshot.overrides());
        assertEquals(2, snapshot.issues().stream().filter(issue -> issue.code().equals("STALE_SOURCE")).count());
        assertEquals(revision, store.load("example", "pt_br").orElseThrow().revision());
    }

    @Test void brokenProjectAndInvalidCatalogDoNotBlockOtherMods() throws Exception {
        var broken = catalog("broken", "broken", "{\"broken\":\"B\"}");
        var invalid = catalog("invalid", "invalid", "{\"invalid\":\"I\"}");
        var valid = catalog("valid", "valid", "{\"valid\":\"V\"}");
        var store = store();
        for (var catalog : List.of(broken, invalid, valid)) {
            store.save(translated(catalog, "pt_br", Map.of(catalog.modId(), "Local")), null);
        }
        Files.writeString(temporary.resolve("projects/broken/pt_br.json"), "broken");
        invalid = catalog("invalid", "invalid", "{broken");
        var snapshot = RuntimeTranslations.load(store, List.of(broken, invalid, valid), "pt_br");
        assertEquals(Map.of("valid", "Local"), snapshot.overrides());
        assertEquals(List.of("PROJECT_READ_FAILED", "INVALID_SOURCE"), snapshot.issues().stream().map(RuntimeTranslations.Issue::code).toList());
        assertEquals("broken", Files.readString(temporary.resolve("projects/broken/pt_br.json")));
    }

    @Test void globalConflictsSkipEveryOriginWhileIdenticalValuesCountEveryOrigin() throws Exception {
        var first = catalog("first", "firstnamespace", "{\"conflict\":\"Source\",\"same\":\"Source\"}");
        var second = catalog("second", "secondnamespace", "{\"conflict\":\"Source\",\"same\":\"Source\"}");
        var store = store();
        store.save(translated(first, "pt_br", Map.of("conflict", "A", "same", "Shared")), null);
        store.save(translated(second, "pt_br", Map.of("conflict", "B", "same", "Shared")), null);
        var snapshot = RuntimeTranslations.load(store, List.of(second, first), "pt_br");
        assertEquals(Map.of("same", "Shared"), snapshot.overrides());
        assertEquals(2, snapshot.appliedEntries());
        assertEquals(2, snapshot.issues().stream().filter(issue -> issue.code().equals("GLOBAL_KEY_CONFLICT")).count());
    }

    @Test void legacyProtectedTextsAreIgnoredAndProtectedGlobalKeyCannotBeClaimedByAnotherMod() throws Exception {
        var named = catalog("named", "named", "{\"named_key\":\"Example Mod\",\"symbol_key\":\"★\"}");
        named = new DiscoveryCatalog(named.modId(), named.modVersion(), named.namespaces(), named.issues(), named.counts(), "Example Mod");
        var other = catalog("other", "other", "{\"named_key\":\"Ordinary source\",\"symbol_key\":\"Ordinary source\"}");
        var store = store();
        var legacy = new TranslationProject(SCHEMA_VERSION, "named", "1", "pt_br", Map.of("named", new Namespace(
                "en_us", "assets/named/lang/en_us.json", Map.of(
                "named_key", new Entry("Example Mod", List.of(), "Meu mod", null, State.TRANSLATED),
                "symbol_key", new Entry("★", List.of(), "☆", null, State.TRANSLATED)))));
        store.save(legacy, null);
        var otherProject = translated(other, "pt_br", Map.of("named_key", "Renamed mod", "symbol_key", "Renamed symbol"));
        store.save(otherProject, null);
        var snapshot = RuntimeTranslations.load(store, List.of(named, other), "pt_br");
        assertTrue(snapshot.overrides().isEmpty());
    }

    @Test void unrelatedBundledLocaleValueDoesNotProtectAnEditableSourceString() throws Exception {
        catalog("example", "example", "{\"settings\":\"Settings\"}");
        Path bundledPath = temporary.resolve("sources/example/assets/example/lang/pt_br.json");
        Files.writeString(bundledPath, "{\"settings\":\"\"}");
        var catalog = new LanguageDiscovery().scan(new ModSource("example", "1",
                List.of(temporary.resolve("sources/example"))));
        var store = store();
        store.save(translated(catalog, "pt_br", Map.of("settings", "Configurações")), null);
        assertEquals(Map.of("settings", "Configurações"),
                RuntimeTranslations.load(store, List.of(catalog), "pt_br").overrides());
    }

    @Test void placeholderMismatchDoesNotBlockSafeReordering() throws Exception {
        var catalog = catalog("example", "example", "{\"safe\":\"%s has %d and %f %%\",\"unsafe\":\"Hello %s\"}");
        var store = store();
        store.save(translated(catalog, "pt_br", Map.of("safe", "%2$d / %1$s / %3$f %%", "unsafe", "Hello %d")), null);
        var snapshot = RuntimeTranslations.load(store, List.of(catalog), "pt_br");
        assertEquals(Map.of("safe", "%2$d / %1$s / %3$f %%"), snapshot.overrides());
        assertEquals("PLACEHOLDER_MISMATCH", snapshot.issues().getFirst().code());
    }

    @Test void unloadedModsAndDuplicateModIdsNeverApplyOrCreateDirectories() throws Exception {
        var catalog = catalog("example", "example", "{\"key\":\"Source\"}");
        var store = store();
        assertTrue(RuntimeTranslations.load(store, List.of(catalog), "pt_br").overrides().isEmpty());
        assertFalse(Files.exists(temporary.resolve("projects")));
        store.save(translated(catalog, "pt_br", Map.of("key", "Local")), null);
        assertTrue(RuntimeTranslations.load(store, List.of(), "pt_br").overrides().isEmpty());
        var duplicates = RuntimeTranslations.load(store, List.of(catalog, catalog), "pt_br");
        assertTrue(duplicates.overrides().isEmpty());
        assertTrue(duplicates.issues().stream().allMatch(issue -> issue.code().equals("DUPLICATE_MOD_ID")));
    }

    @Test void missingSavedNamespaceOrLocaleBlocksTheProject() throws Exception {
        var catalog = catalog("example", "example", "{\"key\":\"Source\"}");
        var store = store();
        store.save(translated(catalog, "pt_br", Map.of("key", "Local")), null);
        var absent = new DiscoveryCatalog("example", "2", List.of(), List.of(), new DiscoveryCatalog.Counts(0, 0, 0, 0));
        var snapshot = RuntimeTranslations.load(store, List.of(absent), "pt_br");
        assertTrue(snapshot.overrides().isEmpty());
        assertEquals("INVALID_SOURCE", snapshot.issues().getFirst().code());
        Files.move(temporary.resolve("sources/example/assets/example/lang/en_us.json"),
                temporary.resolve("sources/example/assets/example/lang/fr_fr.json"));
        var changedLocale = new LanguageDiscovery().scan(new ModSource("example", "2", List.of(temporary.resolve("sources/example"))));
        assertEquals("INVALID_SOURCE", RuntimeTranslations.load(store, List.of(changedLocale), "pt_br").issues().getFirst().code());
    }

    @Test void incompleteCatalogOrConflictingResourcesBlockEvenValidSavedEntries() throws Exception {
        var catalog = catalog("example", "example", "{\"key\":\"Source\"}");
        var store = store();
        store.save(translated(catalog, "pt_br", Map.of("key", "Local")), null);
        var incomplete = new DiscoveryCatalog(catalog.modId(), catalog.modVersion(), catalog.namespaces(),
                List.of(new DiscoveryCatalog.DiscoveryIssue(DiscoveryCatalog.Severity.ERROR,
                        "RESOURCE_LIMIT", 0, "", "Incomplete scan")), catalog.counts());
        assertEquals("INVALID_SOURCE", RuntimeTranslations.load(store, List.of(incomplete), "pt_br").issues().getFirst().code());
        var namespace = catalog.namespaces().getFirst();
        var resource = namespace.resources().getFirst();
        var duplicate = new DiscoveryCatalog(catalog.modId(), catalog.modVersion(),
                List.of(new DiscoveryCatalog.NamespaceCatalog(namespace.namespace(), List.of(resource, resource), namespace.source())),
                List.of(), catalog.counts());
        assertEquals("INVALID_SOURCE", RuntimeTranslations.load(store, List.of(duplicate), "pt_br").issues().getFirst().code());
    }

    @Test void globalKeyConflictAlsoIncludesTwoNamespacesInOneMod() throws Exception {
        catalog("example", "first", "{\"key\":\"First\"}");
        var catalog = catalog("example", "second", "{\"key\":\"Second\"}");
        var namespaces = new TreeMap<String, Namespace>();
        var project = ProjectEngine.create(catalog, "pt_br");
        project.namespaces().forEach((name, namespace) -> namespaces.put(name,
                new Namespace(namespace.sourceLocale(), namespace.sourcePath(),
                        Map.of("key", namespace.entries().get("key").withTranslation(name)))));
        var store = store();
        store.save(new TranslationProject(SCHEMA_VERSION, "example", "1", "pt_br", namespaces), null);
        var snapshot = RuntimeTranslations.load(store, List.of(catalog), "pt_br");
        assertTrue(snapshot.overrides().isEmpty());
        assertEquals(2, snapshot.issues().size());
        assertTrue(snapshot.issues().stream().allMatch(issue -> issue.code().equals("GLOBAL_KEY_CONFLICT")));
    }

    @Test void combinedCandidateEntryBudgetDiscardsEarlierAcceptedMods() throws Exception {
        var first = catalog("first", "first", "{\"one\":\"One\"}");
        var second = catalog("second", "second", "{\"two\":\"Two\"}");
        var store = store();
        store.save(translated(first, "pt_br", Map.of("one", "First")), null);
        store.save(translated(second, "pt_br", Map.of("two", "Second")), null);
        var snapshot = RuntimeTranslations.load(store, List.of(first, second), "pt_br",
                new RuntimeTranslations.Limits(1, 100, 10));
        assertTrue(snapshot.overrides().isEmpty());
        assertEquals(0, snapshot.appliedEntries());
        assertEquals("RUNTIME_LIMIT", snapshot.issues().getLast().code());
        assertEquals(2, snapshot.loadedProjects());
    }

    @Test void characterBudgetCountsKeysAndValuesAndAllowsExactBoundary() throws Exception {
        var catalog = catalog("example", "example", "{\"key\":\"Source\"}");
        var store = store();
        store.save(translated(catalog, "pt_br", Map.of("key", "Text")), null);
        var atLimit = RuntimeTranslations.load(store, List.of(catalog), "pt_br",
                new RuntimeTranslations.Limits(10, 7, 10));
        assertEquals(Map.of("key", "Text"), atLimit.overrides());
        var overLimit = RuntimeTranslations.load(store, List.of(catalog), "pt_br",
                new RuntimeTranslations.Limits(10, 6, 10));
        assertTrue(overLimit.overrides().isEmpty());
        assertEquals("RUNTIME_LIMIT", overLimit.issues().getLast().code());
    }

    @Test void diagnosticCapReportsTruncationAndCannotHideLaterFatalBudgetFailure() throws Exception {
        var catalog = catalog("example", "example", "{\"a\":\"Value %s\",\"b\":\"Value %s\",\"c\":\"Value %s\",\"z\":\"Source\"}");
        var store = store();
        store.save(translated(catalog, "pt_br", Map.of("a", "%d", "b", "%d", "c", "%d", "z", "Safe")), null);
        var capped = RuntimeTranslations.load(store, List.of(catalog), "pt_br",
                new RuntimeTranslations.Limits(10, 100, 2));
        assertEquals(Map.of("z", "Safe"), capped.overrides());
        assertEquals(List.of("PLACEHOLDER_MISMATCH", "ISSUES_TRUNCATED"),
                capped.issues().stream().map(RuntimeTranslations.Issue::code).toList());
        var fatal = RuntimeTranslations.load(store, List.of(catalog), "pt_br",
                new RuntimeTranslations.Limits(10, 1, 2));
        assertTrue(fatal.overrides().isEmpty());
        assertEquals(List.of("RUNTIME_LIMIT", "ISSUES_TRUNCATED"),
                fatal.issues().stream().map(RuntimeTranslations.Issue::code).toList());
    }
}

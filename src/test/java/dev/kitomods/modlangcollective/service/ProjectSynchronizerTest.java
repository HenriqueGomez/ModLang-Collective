package dev.kitomods.modlangcollective.service;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.discovery.LanguageDiscovery;
import dev.kitomods.modlangcollective.discovery.ModSource;
import dev.kitomods.modlangcollective.project.ProjectEngine;
import dev.kitomods.modlangcollective.project.ProjectStore;
import dev.kitomods.modlangcollective.project.TranslationProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProjectSynchronizerTest {
    @TempDir Path temporary;

    private DiscoveryCatalog catalog(String version, String json) throws Exception {
        Path root = temporary.resolve("source");
        Path language = root.resolve("assets/example/lang/en_us.json");
        Files.createDirectories(language.getParent());
        Files.writeString(language, json);
        return new LanguageDiscovery().scan(new ModSource("example", version, List.of(root)));
    }

    @Test void createsModDirectoryWithoutChoosingTargetLanguage() throws Exception {
        var store = new ProjectStore(temporary.resolve("projects"));
        var results = new ProjectSynchronizer(store).synchronize(List.of(catalog("1", "{\"key\":\"Text\"}")));
        assertTrue(results.isEmpty());
        assertTrue(Files.isDirectory(temporary.resolve("projects/example")));
        assertTrue(store.listTargets("example").isEmpty());
    }

    @Test void versionOneFixturePreservesPendingEmptyAndArchivedWork() throws Exception {
        Path root = temporary.resolve("projects");
        var store = new ProjectStore(root);
        store.ensureModDirectory("example");
        try (var fixture = getClass().getResourceAsStream("/projects/v1.json")) {
            assertNotNull(fixture);
            Files.copy(fixture, root.resolve("example/pt_br.json"));
        }
        var loaded = store.load("example", "pt_br").orElseThrow();
        var entries = loaded.project().namespaces().get("example").entries();
        assertNull(entries.get("example.pending").translation());
        assertEquals("", entries.get("example.empty").translation());
        assertEquals(TranslationProject.State.ARCHIVED, entries.get("example.removed").state());
        store.save(loaded.project(), loaded.revision());
        assertEquals(loaded.project(), store.load("example", "pt_br").orElseThrow().project());
    }

    @Test void updatesEveryTargetAndDoesNotRewriteUnchangedProjects() throws Exception {
        var store = new ProjectStore(temporary.resolve("projects"));
        var first = catalog("1", "{\"old\":\"Original\"}");
        for (String target : List.of("pt_br", "fr_fr")) {
            store.save(ProjectEngine.create(first, target), null);
        }
        var updated = catalog("2", "{\"old\":\"Changed\",\"new\":\"New\"}");
        var synchronizer = new ProjectSynchronizer(store);
        var results = synchronizer.synchronize(List.of(updated));
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.status() == ProjectSynchronizer.Status.UPDATED));
        for (String target : List.of("pt_br", "fr_fr")) {
            var loaded = store.load("example", target).orElseThrow();
            var entries = loaded.project().namespaces().get("example").entries();
            assertEquals(TranslationProject.State.PENDING, entries.get("new").state());
            assertEquals("Changed", entries.get("old").sourceText());
        }
        String revision = store.load("example", "pt_br").orElseThrow().revision();
        assertTrue(synchronizer.synchronize(List.of(updated)).stream()
                .allMatch(r -> r.status() == ProjectSynchronizer.Status.UNCHANGED));
        assertEquals(revision, store.load("example", "pt_br").orElseThrow().revision());
    }

    @Test void brokenProjectDoesNotPreventOtherTargetsFromUpdating() throws Exception {
        var store = new ProjectStore(temporary.resolve("projects"));
        var first = catalog("1", "{\"old\":\"Text\"}");
        store.save(ProjectEngine.create(first, "pt_br"), null);
        store.save(ProjectEngine.create(first, "fr_fr"), null);
        Path broken = temporary.resolve("projects/example/fr_fr.json");
        Files.writeString(broken, "broken JSON");
        var results = new ProjectSynchronizer(store).synchronize(List.of(catalog("2", "{\"new\":\"New\"}")));
        assertEquals(1, results.stream().filter(r -> r.status() == ProjectSynchronizer.Status.UPDATED).count());
        assertEquals(1, results.stream().filter(r -> r.status() == ProjectSynchronizer.Status.FAILED).count());
        assertEquals("broken JSON", Files.readString(broken));
        assertEquals("2", store.load("example", "pt_br").orElseThrow().project().modVersion());
    }

    @Test void malformedSourceAndMissingModNeverEraseExistingProjects() throws Exception {
        var store = new ProjectStore(temporary.resolve("projects"));
        store.save(ProjectEngine.create(catalog("1", "{\"old\":\"Text\"}"), "pt_br"), null);
        String revision = store.load("example", "pt_br").orElseThrow().revision();
        var synchronizer = new ProjectSynchronizer(store);
        var results = synchronizer.synchronize(List.of(catalog("2", "{broken")));
        assertEquals(ProjectSynchronizer.Status.FAILED, results.getFirst().status());
        assertEquals(revision, store.load("example", "pt_br").orElseThrow().revision());
        assertTrue(synchronizer.synchronize(List.of()).isEmpty());
        assertEquals(revision, store.load("example", "pt_br").orElseThrow().revision());
    }

    @Test void duplicateModIdentityDoesNotWriteDirectoriesOrProjects() throws Exception {
        Path root = temporary.resolve("projects");
        var source = catalog("1", "{\"key\":\"Text\"}");
        var results = new ProjectSynchronizer(new ProjectStore(root)).synchronize(List.of(source, source));
        assertTrue(results.stream().allMatch(r -> r.status() == ProjectSynchronizer.Status.FAILED));
        assertFalse(Files.exists(root));
    }

    @Test void linkedTargetDoesNotBlockOtherTargetsOrChangeLinkDestination() throws Exception {
        Path root = temporary.resolve("projects");
        var store = new ProjectStore(root);
        store.save(ProjectEngine.create(catalog("1", "{\"old\":\"Text\"}"), "pt_br"), null);
        Path outside = Files.writeString(temporary.resolve("outside.json"), "retain these bytes");
        try {
            Files.createSymbolicLink(root.resolve("example/fr_fr.json"), outside);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links unavailable on this host");
            return;
        }
        var results = new ProjectSynchronizer(store).synchronize(List.of(catalog("2", "{\"new\":\"New\"}")));
        assertEquals(1, results.stream().filter(r -> r.status() == ProjectSynchronizer.Status.UPDATED).count());
        assertEquals(1, results.stream().filter(r -> r.status() == ProjectSynchronizer.Status.FAILED).count());
        assertEquals("retain these bytes", Files.readString(outside));
    }
}

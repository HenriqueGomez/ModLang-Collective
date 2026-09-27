package dev.kitomods.modlangcollective.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static dev.kitomods.modlangcollective.project.ProjectEngineTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProjectStoreTest {
    @TempDir Path temporary;

    @Test void removalArchivesExactBytesAndDoesNotRemainAnActiveTarget() throws IOException {
        var store = new ProjectStore(temporary);
        String revision = store.save(project(), null);
        byte[] before = Files.readAllBytes(file());
        Path archived = store.remove("fixture_mod", "pt_br", revision);
        assertArrayEquals(before, Files.readAllBytes(archived));
        assertFalse(Files.exists(file()));
        assertTrue(store.listTargets("fixture_mod").isEmpty());
        assertTrue(store.load("fixture_mod", "pt_br").isEmpty());
        store.save(project(), null);
        assertArrayEquals(before, Files.readAllBytes(archived));
    }

    @Test void removalRejectsExternalEditsAndInterruptionPreservesCurrentFile() throws IOException {
        var store = new ProjectStore(temporary);
        String revision = store.save(project(), null);
        assertThrows(ProjectStore.ConflictException.class, () -> store.remove("fixture_mod", "pt_br", null));
        Files.writeString(file(), Files.readString(file()) + " ");
        byte[] modified = Files.readAllBytes(file());
        assertThrows(ProjectStore.ConflictException.class, () -> store.remove("fixture_mod", "pt_br", revision));
        assertArrayEquals(modified, Files.readAllBytes(file()));
        String currentRevision = store.load("fixture_mod", "pt_br").orElseThrow().revision();
        var interrupted = new ProjectStore(temporary, () -> { throw new IOException("Simulated removal interruption"); });
        assertThrows(IOException.class, () -> interrupted.remove("fixture_mod", "pt_br", currentRevision));
        assertArrayEquals(modified, Files.readAllBytes(file()));
        var raced = new ProjectStore(temporary, () -> Files.writeString(file(), "External change"));
        assertThrows(ProjectStore.ConflictException.class, () -> raced.remove("fixture_mod", "pt_br", currentRevision));
        assertEquals("External change", Files.readString(file()));
    }

    @Test void writesReloadsAndRetainsExactPriorBytesInBackups() throws IOException {
        var store = new ProjectStore(temporary);
        var project = project();
        assertTrue(store.load("fixture_mod", "pt_br").isEmpty());
        String revision = store.save(project, null);
        var loaded = store.load("fixture_mod", "pt_br").orElseThrow();
        assertEquals(project, loaded.project());
        assertEquals(revision, loaded.revision());
        Path file = file();
        // Whitespace is still external work; its full bytes must survive a valid subsequent save.
        Files.writeString(file, Files.readString(file) + "  \n");
        byte[] before = Files.readAllBytes(file);
        revision = store.load("fixture_mod", "pt_br").orElseThrow().revision();
        String changed = store.save(edited(project, "key", ""), revision);
        assertNotEquals(revision, changed);
        assertEquals("", store.load("fixture_mod", "pt_br").orElseThrow().project()
                .namespaces().get("other").entries().get("key").translation());
        List<Path> backups = backups();
        assertEquals(1, backups.size());
        assertArrayEquals(before, Files.readAllBytes(backups.getFirst()));
        store.save(edited(project, "key", "New"), changed);
        assertEquals(2, backups().size());
        assertEquals(List.of("pt_br"), store.listTargets("fixture_mod"));
    }

    @Test void staleRevisionAndCreateOnlyCannotOverwriteExternalEdits() throws IOException {
        var store = new ProjectStore(temporary);
        String revision = store.save(project(), null);
        assertThrows(ProjectStore.ConflictException.class, () -> store.save(project(), null));
        Files.writeString(file(), Files.readString(file()) + " ");
        byte[] external = Files.readAllBytes(file());
        assertThrows(ProjectStore.ConflictException.class, () -> store.save(edited(project(), "key", "Local"), revision));
        assertArrayEquals(external, Files.readAllBytes(file()));
    }

    @Test void rechecksExternalChangesImmediatelyBeforeReplacement() throws IOException {
        String revision = new ProjectStore(temporary).save(project(), null);
        var store = new ProjectStore(temporary, () -> Files.writeString(file(), "external editor bytes"));
        assertThrows(ProjectStore.ConflictException.class, () -> store.save(edited(project(), "key", "Local"), revision));
        assertEquals("external editor bytes", Files.readString(file()));
        assertNoTemporaryFiles();
    }

    @Test void interruptedWriteLeavesOriginalIntactAndCleansTemporaryFile() throws IOException {
        String revision = new ProjectStore(temporary).save(project(), null);
        byte[] original = Files.readAllBytes(file());
        var store = new ProjectStore(temporary, () -> { throw new IOException("Simulated interruption after force"); });
        assertThrows(IOException.class, () -> store.save(edited(project(), "key", "Local"), revision));
        assertArrayEquals(original, Files.readAllBytes(file()));
        assertNoTemporaryFiles();
    }

    @Test void cooperativeConcurrentWriterCannotEnterReplacement() throws IOException {
        var second = new ProjectStore(temporary);
        String revision = second.save(project(), null);
        var first = new ProjectStore(temporary, () -> assertThrows(ProjectStore.ConflictException.class,
                () -> second.save(edited(project(), "key", "Concurrent"), revision)));
        first.save(edited(project(), "key", "First"), revision);
        assertEquals("First", second.load("fixture_mod", "pt_br").orElseThrow().project()
                .namespaces().get("other").entries().get("key").translation());
    }

    @Test void rejectsMismatchingStoredIdentityAndUnsafePaths() throws IOException {
        var store = new ProjectStore(temporary);
        store.save(project(), null);
        Files.writeString(file(), Files.readString(file()).replace("fixture_mod", "other_mod"));
        assertThrows(IOException.class, () -> store.load("fixture_mod", "pt_br"));
        for (String name : List.of("..", "../other", "con", "nul", "com1", "a/b", "a\\b", "trailing.", "UPPER")) {
            assertThrows(IllegalArgumentException.class, () -> store.ensureModDirectory(name));
            assertThrows(IllegalArgumentException.class, () -> store.load("fixture_mod", name));
        }
    }

    @Test void refusesSymlinkDirectoriesAndTargetFilesWhenSupported() throws IOException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path link = root.resolve("fixture_mod");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic link creation is unavailable: " + exception.getClass().getSimpleName());
        }
        assertThrows(IOException.class, () -> new ProjectStore(root).save(project(), null));
        assertFalse(Files.exists(outside.resolve("pt_br.json")));
        Files.delete(link);
        Files.createDirectory(link);
        Path outsideFile = Files.writeString(outside.resolve("target.json"), "original");
        Files.createSymbolicLink(link.resolve("pt_br.json"), outsideFile);
        var store = new ProjectStore(root);
        assertEquals(List.of("pt_br"), store.listTargets("fixture_mod"));
        assertThrows(IOException.class, () -> store.load("fixture_mod", "pt_br"));
        assertThrows(IOException.class, () -> store.save(project(), null));
        assertEquals("original", Files.readString(outsideFile));
    }

    @Test void refusesWindowsJunctionsWhenSupported() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path junction = root.resolve("fixture_mod");
        // PowerShell arguments are fixed test-generated paths, not user input.
        var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "New-Item -ItemType Junction -Path '" + junction.toString().replace("'", "''")
                        + "' -Target '" + outside.toString().replace("'", "''") + "' | Out-Null")
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assumeTrue(process.waitFor() == 0, "Junction creation is unavailable.");
        try {
            assertThrows(IOException.class, () -> new ProjectStore(root).save(project(), null));
            assertFalse(Files.exists(outside.resolve("pt_br.json")));
        } finally {
            Files.delete(junction);
        }
    }

    @Test void strictCodecRejectsUnknownDuplicateWrongTypesStatesAndInvalidUtf8() throws IOException {
        String valid = new String(ProjectJson.encode(project()), StandardCharsets.UTF_8);
        for (String invalid : List.of(
                valid.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3"),
                valid.replace("\"schemaVersion\": 2", "\"schemaVersion\": \"1\""),
                valid.replace("\"schemaVersion\": 2", "\"schemaVersion\": 2, \"extra\": null"),
                valid.replace("\"schemaVersion\": 2", "\"schemaVersion\": 2, \"schemaVersion\": 2"),
                valid.replace("\"translation\": null", "\"translation\": 1"),
                valid.replace("\"state\": \"PENDING\"", "\"state\": \"TRANSLATED\""),
                valid.replace("\"state\": \"PENDING\"", "\"state\": \"UNKNOWN\""),
                valid.replace("assets/other/lang/en_us.json", "../personal/path"),
                valid.replace("\"sourceHistory\": []", "\"sourceHistory\": [null]"),
                valid + "{}")) {
            assertThrows(IOException.class, () -> ProjectJson.decode(invalid.getBytes(StandardCharsets.UTF_8)), invalid);
        }
        assertThrows(IOException.class, () -> ProjectJson.decode(new byte[]{(byte) 0xC3, 0x28}));
        assertThrows(IOException.class, () -> ProjectJson.decode(new byte[ProjectJson.MAX_BYTES + 1]));
        assertThrows(IOException.class, () -> ProjectJson.decode("[".repeat(20).getBytes(StandardCharsets.UTF_8)));
    }

    @Test void oversizedSaveStopsBeforeTouchingExistingProject() throws IOException {
        var store = new ProjectStore(temporary);
        var original = project();
        String revision = store.save(original, null);
        byte[] before = Files.readAllBytes(file());
        String large = "x".repeat(TranslationProject.MAX_TEXT_LENGTH);
        var entries = new java.util.TreeMap<String, TranslationProject.Entry>();
        for (int i = 0; i < 17; i++) {
            entries.put("key" + i, new TranslationProject.Entry(large, List.of(), null, null, TranslationProject.State.PENDING));
        }
        var namespace = new TranslationProject.Namespace("en_us", "assets/other/lang/en_us.json", entries);
        var oversized = new TranslationProject(TranslationProject.SCHEMA_VERSION, original.modId(), original.modVersion(), original.targetLocale(), Map.of("other", namespace));
        assertThrows(IOException.class, () -> store.save(oversized, revision));
        assertArrayEquals(before, Files.readAllBytes(file()));
        assertTrue(backups().isEmpty());
        assertNoTemporaryFiles();
    }

    @Test void legacyReviewMigratesInMemoryAndFirstSaveBacksUpOriginalBytes() throws Exception {
        byte[] legacy;
        try (var input = getClass().getResourceAsStream("/projects/v1.json")) {
            legacy = input.readAllBytes();
        }
        var migrated = ProjectJson.decode(legacy);
        assertEquals(2, migrated.schemaVersion());
        var namespace = migrated.namespaces().values().iterator().next();
        assertTrue(namespace.entries().values().stream().anyMatch(entry ->
                entry.state() == TranslationProject.State.PENDING && entry.translation() != null));
        var store = new ProjectStore(temporary);
        store.ensureModDirectory(migrated.modId());
        Path target = temporary.resolve(migrated.modId()).resolve(migrated.targetLocale() + ".json");
        Files.write(target, legacy);
        var loaded = store.load(migrated.modId(), migrated.targetLocale()).orElseThrow();
        assertArrayEquals(legacy, Files.readAllBytes(target));
        assertEquals(migrated, loaded.project());
        store.save(loaded.project(), loaded.revision());
        assertTrue(Files.readString(target).contains("\"schemaVersion\": 2"));
        try (var paths = Files.list(target.getParent())) {
            Path backup = paths.filter(path -> path.getFileName().toString().contains(".backup-")).findFirst().orElseThrow();
            assertArrayEquals(legacy, Files.readAllBytes(backup));
        }
        assertEquals(migrated, store.load(migrated.modId(), migrated.targetLocale()).orElseThrow().project());
        assertThrows(IOException.class, () -> ProjectJson.decode(new String(legacy, StandardCharsets.UTF_8)
                .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ProjectJson.decode(new String(legacy, StandardCharsets.UTF_8)
                .replace("\"translation\": null", "\"translation\": \"invalid legacy pending\"")
                .getBytes(StandardCharsets.UTF_8)));
    }

    private TranslationProject project() {
        return ProjectEngine.create(catalog(Map.of("key", "Source %s\n§a 日本語 😀"), Map.of("key", "Bundled")), "pt_br");
    }

    private Path file() { return temporary.resolve("fixture_mod/pt_br.json"); }

    private List<Path> backups() throws IOException {
        try (var paths = Files.list(file().getParent())) {
            return paths.filter(path -> path.getFileName().toString().contains(".backup-")).toList();
        }
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var paths = Files.list(file().getParent())) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }
}

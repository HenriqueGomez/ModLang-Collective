package dev.kitomods.modlangcollective.discovery;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static dev.kitomods.modlangcollective.discovery.DiscoveryCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LanguageDiscoveryTest {
    @TempDir Path temporary;

    @Test void prefersEnglishWithoutAssumingNamespaceEqualsModId() throws IOException {
        write(temporary, "other_namespace", "pt_br", "{\"key\":\"Português\"}");
        write(temporary, "other_namespace", "en_us", "{\"z\":\"Last\",\"a\":\"First\"}");
        DiscoveryCatalog catalog = scan(temporary);
        var namespace = catalog.namespaces().getFirst();
        assertEquals("fixture_mod", catalog.modId());
        assertEquals("other_namespace", namespace.namespace());
        assertEquals(SourceState.ENGLISH, namespace.source().state());
        assertEquals("en_us", namespace.source().locale().orElseThrow());
        assertTrue(namespace.source().usable());
        assertEquals(List.of("a", "z"), List.copyOf(namespace.resources().getFirst().entries().keySet()));
        assertEquals(new Counts(1, 2, 3, 0), catalog.counts());
        assertThrows(UnsupportedOperationException.class, () -> namespace.resources().clear());
        assertThrows(UnsupportedOperationException.class, () -> namespace.resources().getFirst().entries().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.issues().clear());
    }

    @Test void soleNonEnglishLocaleIsExplicitFallback() throws IOException {
        write(temporary, "fixture", "ja_jp", "{\"key\":\"日本語\"}");
        DiscoveryCatalog catalog = scan(temporary);
        assertEquals(SourceState.SINGLE_LOCALE_FALLBACK, catalog.namespaces().getFirst().source().state());
        assertEquals("ja_jp", catalog.namespaces().getFirst().source().locale().orElseThrow());
        assertTrue(catalog.namespaces().getFirst().source().usable());
        assertIssue(catalog, "MISSING_ENGLISH");
    }

    @Test void multipleNonEnglishLocalesRequireSelection() throws IOException {
        write(temporary, "fixture", "de_de", "{}");
        write(temporary, "fixture", "pt_br", "{}");
        SourceSelection selection = scan(temporary).namespaces().getFirst().source();
        assertEquals(SourceState.AMBIGUOUS, selection.state());
        assertTrue(selection.locale().isEmpty());
        assertFalse(selection.usable());
    }

    @Test void namespacesChooseSourcesIndependentlyAndHaveDeterministicOrder() throws IOException {
        write(temporary, "zebra", "ja_jp", "{}");
        write(temporary, "alpha", "en_us", "{}");
        DiscoveryCatalog catalog = scan(temporary);
        assertEquals(List.of("alpha", "zebra"), catalog.namespaces().stream().map(NamespaceCatalog::namespace).toList());
        assertEquals(SourceState.ENGLISH, catalog.namespaces().getFirst().source().state());
        assertEquals(SourceState.SINGLE_LOCALE_FALLBACK, catalog.namespaces().get(1).source().state());
        assertEquals(catalog, scan(temporary));
        assertIssue(catalog, "MULTIPLE_NAMESPACES");
    }

    @Test void conflictingRootsRetainBothWithoutChoosingPrecedence() throws IOException {
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        write(first, "fixture", "en_us", "{\"key\":\"First\"}");
        write(second, "fixture", "en_us", "{\"key\":\"Second\"}");
        DiscoveryCatalog catalog = scan(first, second);
        var namespace = catalog.namespaces().getFirst();
        assertEquals(2, namespace.resources().size());
        assertEquals("First", namespace.resources().getFirst().entries().get("key"));
        assertEquals("Second", namespace.resources().get(1).entries().get("key"));
        assertFalse(namespace.source().usable());
        assertIssue(catalog, "DUPLICATE_LOCALE");
    }

    @Test void repeatedRootIsSkippedWithoutInventingResourceConflict() throws IOException {
        write(temporary, "fixture", "en_us", "{}");
        DiscoveryCatalog catalog = scan(temporary, temporary.resolve("unused/.."));
        assertEquals(1, catalog.counts().resourceCount());
        assertIssue(catalog, "DUPLICATE_ROOT");
        assertTrue(catalog.namespaces().getFirst().source().usable());
        assertFalse(catalog.issues().stream().anyMatch(issue -> issue.code().equals("DUPLICATE_LOCALE")));
    }

    @Test void nonStringValuesAreReportedAndDoNotBecomeTranslations() throws IOException {
        write(temporary, "fixture", "en_us", "{\"valid\":\"Text\",\"empty\":\"\",\"number\":12,\"boolean\":true,\"null\":null,\"object\":{},\"array\":[]}");
        DiscoveryCatalog catalog = scan(temporary);
        LanguageResource resource = catalog.namespaces().getFirst().resources().getFirst();
        assertEquals(Map.of("valid", "Text", "empty", ""), resource.entries());
        assertEquals(ResourceStatus.PARTIAL, resource.status());
        assertEquals(5, catalog.issues().stream().filter(issue -> issue.code().equals("NON_STRING_VALUE")).count());
        assertFalse(catalog.namespaces().getFirst().source().usable());
    }

    @Test void strictMalformedJsonNeverLeavesPartialSourceEntries() throws IOException {
        List<String> malformed = List.of("{\"valid\":\"Text\",}", "{key:'text'}", "/* comment */ {}", "{} {}",
                "{\"valid\":\"Text\",\"broken\":", "[]", "null", "{\"key\":NaN}", "{\"key\":\"raw\nnewline\"}");
        for (int index = 0; index < malformed.size(); index++) {
            Path root = temporary.resolve(Integer.toString(index));
            write(root, "fixture", "en_us", malformed.get(index));
            DiscoveryCatalog catalog = scan(root);
            LanguageResource resource = catalog.namespaces().getFirst().resources().getFirst();
            assertEquals(ResourceStatus.INVALID, resource.status(), malformed.get(index));
            assertTrue(resource.entries().isEmpty());
            assertIssue(catalog, "MALFORMED_JSON");
        }
    }

    @Test void duplicateKeysAreExcludedEvenWhenRepeatedThreeTimes() throws IOException {
        write(temporary, "fixture", "en_us", "{\"duplicate\":\"a\",\"good\":\"yes\",\"duplicate\":\"b\",\"duplicate\":\"c\"}");
        DiscoveryCatalog catalog = scan(temporary);
        assertEquals(Map.of("good", "yes"), catalog.namespaces().getFirst().resources().getFirst().entries());
        assertIssue(catalog, "DUPLICATE_KEY");
    }

    @Test void boundedFileDoesNotPreventOtherResourcesFromBeingRead() throws IOException {
        write(temporary, "fixture", "en_us", "{\"key\":\"Longer than the limit\"}");
        write(temporary, "fixture", "fr_fr", "{}");
        DiscoveryCatalog catalog = new LanguageDiscovery(8).scan(source(temporary));
        assertEquals(ResourceStatus.TOO_LARGE, catalog.namespaces().getFirst().resources().getFirst().status());
        assertEquals(ResourceStatus.VALID, catalog.namespaces().getFirst().resources().get(1).status());
        assertEquals(SourceState.ENGLISH, catalog.namespaces().getFirst().source().state());
        assertFalse(catalog.namespaces().getFirst().source().usable());
        assertIssue(catalog, "FILE_TOO_LARGE");
    }

    @Test void rejectsInvalidUtf8() throws IOException {
        Path file = write(temporary, "fixture", "en_us", "{}");
        Files.write(file, new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, (byte) 0x28, '"', '}'});
        DiscoveryCatalog catalog = scan(temporary);
        assertIssue(catalog, "INVALID_UTF8");
        assertEquals(ResourceStatus.INVALID, catalog.namespaces().getFirst().resources().getFirst().status());
    }

    @Test void onlyCanonicalPathsAreScannedAndNestedJarsAreNotOpened() throws IOException {
        write(temporary, "fixture", "en_us", "{}");
        for (String path : List.of("data/fixture/lang/en_us.json", "assets/fixture/lang/sub/fr_fr.json",
                "assets/Upper/lang/en_us.json", "assets/fixture/lang/EN_US.json", "assets/fixture/lang/fr_fr.json.bak",
                "META-INF/jars/nested.jar")) {
            Path file = temporary.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "not a language file");
        }
        assertEquals(1, scan(temporary).counts().resourceCount());
    }

    @Test void zipFilesystemRootsAreSupportedAndArchiveIsUnchanged() throws IOException {
        Path archive = temporary.resolve("fixture.jar");
        URI uri = URI.create("jar:" + archive.toUri());
        try (var zip = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            write(zip.getPath("/"), "fixture", "en_us", "{\"key\":\"Value\"}");
        }
        byte[] before = Files.readAllBytes(archive);
        try (var zip = FileSystems.newFileSystem(archive)) {
            DiscoveryCatalog catalog = scan(zip.getPath("/"));
            assertEquals(Map.of("key", "Value"), catalog.namespaces().getFirst().resources().getFirst().entries());
            assertTrue(catalog.issues().isEmpty());
        }
        assertArrayEquals(before, Files.readAllBytes(archive));
    }

    @Test void sourceFilesAndDirectoryContentsRemainUntouched() throws IOException {
        Path file = write(temporary, "fixture", "en_us", "{\"key\":\"Value %s §a\"}");
        byte[] before = Files.readAllBytes(file);
        FileTime modified = Files.getLastModifiedTime(file);
        List<Path> paths;
        try (var stream = Files.walk(temporary)) { paths = stream.sorted().toList(); }
        scan(temporary);
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(modified, Files.getLastModifiedTime(file));
        try (var stream = Files.walk(temporary)) { assertEquals(paths, stream.sorted().toList()); }
    }

    @Test void symbolicLinkResourcesAndRootAncestorsAreSkippedWhenSupported() throws IOException {
        Path external = temporary.resolve("external");
        Path file = write(external, "fixture", "en_us", "{}");
        Path root = temporary.resolve("root");
        Path link = root.resolve("assets/fixture/lang/en_us.json");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, file);
            Files.createSymbolicLink(temporary.resolve("linked"), external);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Host does not permit symbolic links");
        }
        DiscoveryCatalog catalog = scan(root);
        assertEquals(0, catalog.counts().resourceCount());
        assertIssue(catalog, "SYMLINK_SKIPPED");
        DiscoveryCatalog ancestor = scan(temporary.resolve("linked/assets"));
        assertEquals(0, ancestor.counts().resourceCount());
        assertIssue(ancestor, "SYMLINK_SKIPPED");
    }

    @Test void missingRootReportsNoAbsolutePathAndEmptyModIsValid() throws IOException {
        DiscoveryCatalog missing = scan(temporary.resolve("private_missing_path"));
        assertIssue(missing, "UNREADABLE_ROOT");
        assertFalse(missing.toString().contains(temporary.toString()));
        assertFalse(missing.toString().contains("private_missing_path"));
        assertEquals(new Counts(0, 0, 0, 0), scan(temporary).counts());
    }

    @Test void skippedSymbolicLinkRootCannotHideAnEnglishConflict() throws IOException {
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        write(first, "fixture", "en_us", "{\"key\":\"First\"}");
        write(second, "fixture", "en_us", "{\"key\":\"Second\"}");
        Path linkedRoot = temporary.resolve("linked_root");
        try {
            Files.createSymbolicLink(linkedRoot, second);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Host does not permit symbolic links");
        }
        DiscoveryCatalog catalog = scan(first, linkedRoot);
        SourceSelection selection = catalog.namespaces().getFirst().source();
        assertEquals(SourceState.ENGLISH, selection.state());
        assertFalse(selection.usable());
        assertEquals(1, catalog.counts().resourceCount());
        assertIssue(catalog, "SYMLINK_SKIPPED");
    }

    @Test void incompleteRootScanCannotClaimAUsableSource() throws IOException {
        write(temporary, "fixture", "en_us", "{}");
        DiscoveryCatalog catalog = scan(temporary, temporary.resolve("missing"));
        assertEquals(SourceState.ENGLISH, catalog.namespaces().getFirst().source().state());
        assertFalse(catalog.namespaces().getFirst().source().usable());
        assertIssue(catalog, "UNREADABLE_ROOT");
    }

    @Test void excessRootsAreRejectedWithoutPartialDiscovery() throws IOException {
        write(temporary, "fixture", "en_us", "{}");
        DiscoveryCatalog catalog = new LanguageDiscovery().scan(new ModSource("fixture", "1",
                java.util.Collections.nCopies(LanguageDiscovery.MAX_ROOTS + 1, temporary)));
        assertEquals(0, catalog.counts().resourceCount());
        assertIssue(catalog, "ROOT_LIMIT");
    }

    @Test void translationEntryLimitRejectsTheWholeResource() throws IOException {
        StringBuilder json = new StringBuilder("{");
        for (int index = 0; index <= LanguageDiscovery.MAX_TRANSLATION_ENTRIES; index++) {
            if (index > 0) json.append(',');
            json.append('"').append(index).append("\":\"x\"");
        }
        json.append('}');
        write(temporary, "fixture", "en_us", json.toString());
        DiscoveryCatalog catalog = scan(temporary);
        assertIssue(catalog, "ENTRY_LIMIT");
        assertEquals(ResourceStatus.INVALID, catalog.namespaces().getFirst().resources().getFirst().status());
        assertTrue(catalog.namespaces().getFirst().resources().getFirst().entries().isEmpty());
    }

    @Test void malformedValueIssueFloodIsBounded() throws IOException {
        StringBuilder json = new StringBuilder("{");
        for (int index = 0; index < LanguageDiscovery.MAX_ISSUES + 10; index++) {
            if (index > 0) json.append(',');
            json.append('"').append(index).append("\":false");
        }
        json.append('}');
        write(temporary, "fixture", "en_us", json.toString());
        DiscoveryCatalog catalog = scan(temporary);
        assertEquals(LanguageDiscovery.MAX_ISSUES + 1, catalog.issues().size());
        assertIssue(catalog, "ISSUE_LIMIT");
    }

    private static Path write(Path root, String namespace, String locale, String json) throws IOException {
        Path file = root.resolve("assets/" + namespace + "/lang/" + locale + ".json");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, json);
    }

    private static ModSource source(Path... roots) {
        return new ModSource("fixture_mod", "1.2.3", List.of(roots));
    }

    private static DiscoveryCatalog scan(Path... roots) {
        return new LanguageDiscovery().scan(source(roots));
    }

    private static void assertIssue(DiscoveryCatalog catalog, String code) {
        assertTrue(catalog.issues().stream().anyMatch(issue -> issue.code().equals(code)), code);
    }
}

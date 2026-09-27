package dev.kitomods.modlangcollective.editor;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static dev.kitomods.modlangcollective.editor.EditorSessionTest.*;
import static dev.kitomods.modlangcollective.editor.TranslationAvailability.*;
import static org.junit.jupiter.api.Assertions.*;

class TranslationAvailabilityTest {
    @Test void protectedOnlySourcesDoNotOfferTranslation() {
        var raw = catalog(namespace("example", "en_us", Map.of("symbol", "→", "name", "Example Mod")));
        var named = new DiscoveryCatalog(raw.modId(), raw.modVersion(), raw.namespaces(), raw.issues(), raw.counts(), "Example Mod");
        assertEquals(Status.NO_TEXTS, assess(named));
        assertEquals(Status.AVAILABLE, assess(catalog(namespace("example", "en_us", Map.of("text", "Example Mod settings")))));
    }

    @Test void missingEmptyAndPopulatedResourcesHaveDifferentEligibility() {
        assertEquals(Status.NOT_SCANNED, assess(null));
        assertEquals(Status.NO_TEXTS, assess(catalog()));
        assertEquals(Status.NO_TEXTS, assess(catalog(namespace("example", "en_us", Map.of()))));
        assertEquals(Status.AVAILABLE, assess(catalog(namespace("example", "en_us", Map.of("key", "Text")))));
        assertEquals(Status.AVAILABLE, assess(catalog(namespace("example", "fr_fr", Map.of("key", "Texte")))));
        assertEquals(Status.AVAILABLE, assess(ambiguousCatalog()));
    }

    @Test void incompleteSourcesAreNotMisreportedAsHardcodedOrUsable() {
        var source = catalog(namespace("example", "en_us", Map.of("key", "Text")));
        var issue = new DiscoveryCatalog.DiscoveryIssue(DiscoveryCatalog.Severity.ERROR, "IO_ERROR", 0, "assets", "Unreadable");
        assertEquals(Status.INVALID_RESOURCES, assess(new DiscoveryCatalog(source.modId(), source.modVersion(),
                source.namespaces(), List.of(issue), source.counts())));
        var valid = source.namespaces().getFirst();
        var duplicate = new DiscoveryCatalog.NamespaceCatalog("example",
                List.of(valid.resources().getFirst(), valid.resources().getFirst()), valid.source());
        assertEquals(Status.INVALID_RESOURCES, assess(catalog(duplicate)));
        var partial = new DiscoveryCatalog.LanguageResource(0, "assets/example/lang/en_us.json", "en_us",
                Map.of("surviving.key", "Text"), DiscoveryCatalog.ResourceStatus.PARTIAL);
        assertEquals(Status.INVALID_RESOURCES, assess(catalog(new DiscoveryCatalog.NamespaceCatalog("example",
                List.of(partial), valid.source()))));
    }

    @Test void emptyPreferredSourceDoesNotBecomeUsableBecauseOtherLocaleHasText() {
        var original = namespace("example", "en_us", Map.of());
        var resources = List.of(original.resources().getFirst(), resource("example", "pt_br", Map.of("key", "Texto")));
        assertEquals(Status.NO_TEXTS, assess(catalog(new DiscoveryCatalog.NamespaceCatalog("example", resources, original.source()))));
    }
}

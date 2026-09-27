package dev.kitomods.modlangcollective.editor;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static dev.kitomods.modlangcollective.discovery.DiscoveryCatalog.*;
import static dev.kitomods.modlangcollective.editor.EditorSessionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceChoicesTest {
    @Test void ambiguousSourcesRequirePerNamespaceChoiceAndSafeDefaultsRemainAvailable() {
        var ambiguous = ambiguousCatalog().namespaces().getFirst();
        var catalog = catalog(ambiguous, namespace("english", "en_us", Map.of("key", "English")),
                namespace("single", "es_es", Map.of("key", "Hola")));
        assertEquals(Map.of("english", "en_us", "single", "es_es"), SourceChoices.defaults(catalog));
        assertEquals(List.of("de_de", "fr_fr"), SourceChoices.options(catalog).get("other"));
        assertThrows(IllegalArgumentException.class, () -> SourceChoices.create(catalog, "pt_br", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SourceChoices.create(catalog, "pt_br", Map.of("other", "missing")));
        assertThrows(IllegalArgumentException.class, () -> SourceChoices.create(catalog, "pt_br", Map.of("missing", "fr_fr")));
        var project = SourceChoices.create(catalog, "pt_br", Map.of("other", "fr_fr"));
        assertEquals("fr_fr", project.namespaces().get("other").sourceLocale());
        assertEquals("Hola", project.namespaces().get("single").entries().get("key").sourceText());
        assertEquals(3, project.namespaces().size());
    }

    @Test void manualChoiceNeverWaivesWarningsDuplicatesOrPartialBundledResources() {
        var catalog = ambiguousCatalog();
        for (Severity severity : List.of(Severity.WARNING, Severity.ERROR)) {
            var unsafe = new DiscoveryCatalog(catalog.modId(), catalog.modVersion(), catalog.namespaces(),
                    List.of(new DiscoveryIssue(severity, "INCOMPLETE", 0, "assets", "Incomplete discovery.")), catalog.counts());
            assertThrows(IllegalArgumentException.class, () -> SourceChoices.create(unsafe, "pt_br", Map.of("other", "fr_fr")));
        }
        var resource = resource("other", "fr_fr", Map.of("key", "Bonjour"));
        var selection = new SourceSelection(SourceState.AMBIGUOUS, Optional.empty(), false);
        var duplicate = catalog(new NamespaceCatalog("other", List.of(resource, resource), selection));
        assertThrows(IllegalArgumentException.class, () -> SourceChoices.create(duplicate, "pt_br", Map.of("other", "fr_fr")));
        for (ResourceStatus status : ResourceStatus.values()) {
            if (status == ResourceStatus.VALID) continue;
            var invalidBundled = new LanguageResource(0, "assets/other/lang/pt_br.json", "pt_br", Map.of(), status);
            var partial = catalog(new NamespaceCatalog("other", List.of(resource, invalidBundled), selection));
            assertThrows(IllegalArgumentException.class, () -> SourceChoices.create(partial, "pt_br", Map.of("other", "fr_fr")));
        }
        var nonCanonical = new LanguageResource(0, "assets/wrong/lang/fr_fr.json", "fr_fr", Map.of(), ResourceStatus.VALID);
        var wrongPath = catalog(new NamespaceCatalog("other", List.of(nonCanonical), selection));
        assertThrows(IllegalArgumentException.class, () -> SourceChoices.create(wrongPath, "pt_br", Map.of("other", "fr_fr")));
        var duplicateNamespace = catalog(catalog.namespaces().getFirst(), catalog.namespaces().getFirst());
        assertThrows(IllegalArgumentException.class, () -> SourceChoices.options(duplicateNamespace));
    }
}

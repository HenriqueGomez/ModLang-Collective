package dev.kitomods.modlangcollective.editor;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import java.util.Map;
import dev.kitomods.modlangcollective.discovery.TranslationTextPolicy;

/** Eligibility follows the same source validation as project creation, not namespace counts. */
public final class TranslationAvailability {
    private TranslationAvailability() { }

    public enum Status { AVAILABLE, NO_TEXTS, INVALID_RESOURCES, NOT_SCANNED }

    public static Status assess(DiscoveryCatalog catalog) {
        if (catalog == null) return Status.NOT_SCANNED;
        if (catalog.issues().stream().anyMatch(issue -> issue.severity() != DiscoveryCatalog.Severity.INFO)) {
            return Status.INVALID_RESOURCES;
        }
        if (catalog.namespaces().isEmpty()) return Status.NO_TEXTS;
        try {
            SourceChoices.options(catalog);
            Map<String, String> defaults = SourceChoices.defaults(catalog);
            boolean hasText = catalog.namespaces().stream().anyMatch(namespace -> {
                String preferred = defaults.get(namespace.namespace());
                return namespace.resources().stream()
                        .filter(resource -> preferred == null || preferred.equals(resource.locale()))
                        .anyMatch(resource -> resource.entries().values().stream().anyMatch(value -> !TranslationTextPolicy.isProtected(catalog, value)));
            });
            return hasText ? Status.AVAILABLE : Status.NO_TEXTS;
        } catch (IllegalArgumentException exception) {
            return Status.INVALID_RESOURCES;
        }
    }
}

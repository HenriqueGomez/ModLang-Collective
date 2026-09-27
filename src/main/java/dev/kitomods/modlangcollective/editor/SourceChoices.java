package dev.kitomods.modlangcollective.editor;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.discovery.TranslationTextPolicy;
import dev.kitomods.modlangcollective.project.TranslationProject;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static dev.kitomods.modlangcollective.discovery.DiscoveryCatalog.*;
import static dev.kitomods.modlangcollective.project.TranslationProject.*;

/** Explicit source selection cannot waive incomplete or conflicting discovery evidence. */
public final class SourceChoices {
    private SourceChoices() { }

    public static Map<String, List<String>> options(DiscoveryCatalog catalog) {
        if (catalog.namespaces().isEmpty()) throw new IllegalArgumentException("No source namespace was discovered.");
        if (catalog.issues().stream().anyMatch(issue -> issue.severity() != Severity.INFO)) {
            throw new IllegalArgumentException("Discovery has unresolved warnings or errors; source selection is unavailable.");
        }
        var options = new TreeMap<String, List<String>>();
        for (NamespaceCatalog namespace : catalog.namespaces()) {
            if (options.containsKey(namespace.namespace())) throw new IllegalArgumentException("Duplicate discovered namespace.");
            var locales = new java.util.TreeSet<String>();
            for (LanguageResource resource : namespace.resources()) {
                validateComponent(resource.locale());
                if (resource.status() != ResourceStatus.VALID || !locales.add(resource.locale())) {
                    throw new IllegalArgumentException("Discovery contains invalid or conflicting resources.");
                }
                if (!resource.resourcePath().equals("assets/" + namespace.namespace() + "/lang/" + resource.locale() + ".json")) {
                    throw new IllegalArgumentException("Discovery resource path is not canonical.");
                }
            }
            if (locales.isEmpty()) throw new IllegalArgumentException("A namespace has no source resources.");
            options.put(namespace.namespace(), List.copyOf(locales));
        }
        return Collections.unmodifiableMap(options);
    }

    /** Ambiguous non-English namespaces are omitted until the user chooses a locale. */
    public static Map<String, String> defaults(DiscoveryCatalog catalog) {
        var result = new TreeMap<String, String>();
        options(catalog).forEach((namespace, locales) -> {
            if (locales.contains("en_us")) result.put(namespace, "en_us");
            else if (locales.size() == 1) result.put(namespace, locales.getFirst());
        });
        return Collections.unmodifiableMap(result);
    }

    public static TranslationProject create(DiscoveryCatalog catalog, String targetLocale,
                                            Map<String, String> sourceLocales) {
        Map<String, List<String>> options = options(catalog);
        var selected = new TreeMap<>(defaults(catalog));
        sourceLocales.forEach((namespace, locale) -> {
            if (!options.containsKey(namespace) || !options.get(namespace).contains(locale)) {
                throw new IllegalArgumentException("Selected source is missing or does not belong to this namespace.");
            }
            selected.put(namespace, locale);
        });
        var namespaces = new TreeMap<String, Namespace>();
        for (NamespaceCatalog namespace : catalog.namespaces()) {
            String locale = selected.get(namespace.namespace());
            if (locale == null) throw new IllegalArgumentException("Choose a source language for namespace " + namespace.namespace() + ".");
            LanguageResource source = namespace.resources().stream().filter(resource -> resource.locale().equals(locale))
                    .findFirst().orElseThrow();
            Map<String, String> bundled = namespace.resources().stream().filter(resource -> resource.locale().equals(targetLocale))
                    .findFirst().map(LanguageResource::entries).orElse(Map.of());
            var entries = new TreeMap<String, Entry>();
            source.entries().forEach((key, text) -> {
                if (!TranslationTextPolicy.isProtected(catalog, text)) entries.put(key,
                        new Entry(text, List.of(), null, bundled.get(key), State.PENDING));
            });
            namespaces.put(namespace.namespace(), new Namespace(locale, source.resourcePath(), entries));
        }
        return new TranslationProject(SCHEMA_VERSION, catalog.modId(), catalog.modVersion(), targetLocale, namespaces);
    }
}

package dev.kitomods.modlangcollective.project;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.discovery.TranslationTextPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static dev.kitomods.modlangcollective.discovery.DiscoveryCatalog.*;
import static dev.kitomods.modlangcollective.project.TranslationProject.*;

/** Pure reconciliation. An uncertain catalog is rejected before any local work changes. */
public final class ProjectEngine {
    private ProjectEngine() { }

    public static TranslationProject create(DiscoveryCatalog catalog, String targetLocale) {
        if (catalog.namespaces().isEmpty()) throw new IllegalArgumentException("No usable source namespace was discovered.");
        return merge(new TranslationProject(SCHEMA_VERSION, catalog.modId(), catalog.modVersion(),
                targetLocale, Map.of()), catalog);
    }

    public static TranslationProject merge(TranslationProject project, DiscoveryCatalog catalog) {
        if (!project.modId().equals(catalog.modId())) throw new IllegalArgumentException("Discovery mod identity mismatch.");
        if (catalog.issues().stream().anyMatch(issue -> issue.severity() != Severity.INFO)) {
            throw new IllegalArgumentException("Discovery has unresolved warnings or errors; synchronization was skipped.");
        }
        var seen = new HashSet<String>();
        var incoming = new TreeMap<String, Namespace>();
        var discoveredNames = new HashSet<String>();
        catalog.namespaces().forEach(namespace -> discoveredNames.add(namespace.namespace()));
        if (!discoveredNames.containsAll(project.namespaces().keySet())) {
            throw new IllegalArgumentException("A previous namespace is absent; synchronization requires complete source evidence.");
        }
        for (NamespaceCatalog namespace : catalog.namespaces()) {
            if (!seen.add(namespace.namespace())) throw new IllegalArgumentException("Duplicate discovered namespace.");
            Namespace previous = project.namespaces().get(namespace.namespace());
            if (previous == null && (!namespace.source().usable() || namespace.source().locale().isEmpty()
                    || (namespace.source().state() != SourceState.ENGLISH
                    && namespace.source().state() != SourceState.SINGLE_LOCALE_FALLBACK))) {
                throw new IllegalArgumentException("Source selection is incomplete or ambiguous.");
            }
            // A persisted source choice must not silently change when another locale appears.
            String sourceLocale = previous == null ? namespace.source().locale().orElseThrow() : previous.sourceLocale();
            var locales = new HashSet<String>();
            for (LanguageResource resource : namespace.resources()) {
                if (resource.status() != ResourceStatus.VALID || !locales.add(resource.locale())) {
                    throw new IllegalArgumentException("Discovery contains invalid or conflicting resources.");
                }
                if (!resource.resourcePath().equals("assets/" + namespace.namespace() + "/lang/" + resource.locale() + ".json")) {
                    throw new IllegalArgumentException("Discovery resource path is not canonical.");
                }
            }
            LanguageResource source = namespace.resources().stream().filter(r -> r.locale().equals(sourceLocale))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Selected source is missing."));
            Map<String, String> bundled = namespace.resources().stream()
                    .filter(r -> r.locale().equals(project.targetLocale())).findFirst()
                    .map(LanguageResource::entries).orElse(Map.of());
            var entries = new TreeMap<String, Entry>();
            if (previous != null) previous.entries().forEach((key, entry) -> entries.put(key, archive(entry)));
            for (var sourceEntry : source.entries().entrySet()) {
                String key = sourceEntry.getKey();
                String value = sourceEntry.getValue();
                Entry old = previous == null ? null : previous.entries().get(key);
                if (TranslationTextPolicy.isProtected(catalog, value)) continue;
                if (old == null) {
                    entries.put(key, new Entry(value, List.of(), null, bundled.get(key), State.PENDING));
                } else {
                    boolean changed = !old.sourceText().equals(value);
                    var history = new ArrayList<>(old.sourceHistory());
                    if (changed) history.add(old.sourceText());
                    boolean changedSource = changed || old.state() == State.ARCHIVED
                            || !previous.sourceLocale().equals(sourceLocale);
                    entries.put(key, new Entry(value, history, old.translation(), bundled.get(key),
                            changedSource ? State.PENDING : old.state()));
                }
            }
            incoming.put(namespace.namespace(), new Namespace(sourceLocale, source.resourcePath(), entries));
        }
        return new TranslationProject(SCHEMA_VERSION, project.modId(), catalog.modVersion(), project.targetLocale(), incoming);
    }

    private static Entry archive(Entry entry) {
        return new Entry(entry.sourceText(), entry.sourceHistory(), entry.translation(),
                entry.bundledTranslation(), State.ARCHIVED);
    }
}

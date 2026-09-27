package dev.kitomods.modlangcollective.runtime;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.discovery.TranslationTextPolicy;
import dev.kitomods.modlangcollective.project.ProjectStore;
import dev.kitomods.modlangcollective.project.TranslationProject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static dev.kitomods.modlangcollective.discovery.DiscoveryCatalog.*;

/** Read-only projection of saved local work into Minecraft's global translation key space. */
public final class RuntimeTranslations {
    public static final int MAX_CANDIDATE_ENTRIES = 100_000;
    /** UTF-16 code units retained by candidate keys and translations, approximately 32 MiB. */
    public static final long MAX_TEXT_CHARACTERS = 16L * 1024 * 1024;
    public static final int MAX_ISSUES = 1_024;

    private RuntimeTranslations() { }

    public static Snapshot load(ProjectStore store, List<DiscoveryCatalog> catalogs, String locale) {
        return load(store, catalogs, locale, new Limits(MAX_CANDIDATE_ENTRIES, MAX_TEXT_CHARACTERS, MAX_ISSUES));
    }

    static Snapshot load(ProjectStore store, List<DiscoveryCatalog> catalogs, String locale, Limits limits) {
        Objects.requireNonNull(store);
        TranslationProject.validateComponent(locale);
        var issues = new IssueCollector(limits.issues());
        var candidates = new TreeMap<String, List<Candidate>>();
        var seen = new HashSet<String>();
        var duplicates = new HashSet<String>();
        var protectedGlobalKeys = new HashSet<String>();
        for (var catalog : catalogs) {
            if (!seen.add(catalog.modId())) duplicates.add(catalog.modId());
            for (var namespace : catalog.namespaces()) {
                namespace.source().locale().flatMap(sourceLocale -> namespace.resources().stream()
                        .filter(resource -> resource.locale().equals(sourceLocale)
                                && resource.status() == ResourceStatus.VALID)
                        .findFirst()).ifPresent(resource -> resource.entries().forEach((key, text) -> {
                    if (TranslationTextPolicy.isProtected(catalog, text)) protectedGlobalKeys.add(key);
                }));
            }
        }
        int loadedProjects = 0;
        int translatedEntries = 0;
        int candidateEntries = 0;
        long candidateCharacters = 0;
        for (var catalog : catalogs.stream().sorted(Comparator.comparing(DiscoveryCatalog::modId)).toList()) {
            String modId = catalog.modId();
            if (duplicates.contains(modId)) {
                issues.add(new Issue("DUPLICATE_MOD_ID", modId, null, null, "Duplicate installed mod identity."));
                continue;
            }
            TranslationProject project;
            try {
                var loaded = store.load(modId, locale);
                if (loaded.isEmpty()) continue;
                project = loaded.get().project();
                loadedProjects++;
            } catch (IOException | IllegalArgumentException | SecurityException exception) {
                issues.add(new Issue("PROJECT_READ_FAILED", modId, null, null, "Saved project could not be read safely."));
                continue;
            }
            Map<String, LanguageResource> sources;
            try {
                sources = sources(project, catalog);
            } catch (IllegalArgumentException exception) {
                issues.add(new Issue("INVALID_SOURCE", modId, null, null, exception.getMessage()));
                continue;
            }
            for (var item : project.namespaces().entrySet()) {
                LanguageResource source = sources.get(item.getKey());
                source.entries().forEach((key, text) -> {
                    if (TranslationTextPolicy.isProtected(catalog, text)) protectedGlobalKeys.add(key);
                });
            }
            for (var namespace : project.namespaces().entrySet()) {
                LanguageResource source = sources.get(namespace.getKey());
                for (var item : namespace.getValue().entries().entrySet()) {
                    var entry = item.getValue();
                    if (entry.state() != TranslationProject.State.TRANSLATED || entry.translation() == null) continue;
                    if (protectedGlobalKeys.contains(item.getKey())
                            || TranslationTextPolicy.isProtected(catalog, entry.sourceText())) continue;
                    translatedEntries++;
                    String key = item.getKey();
                    if (!entry.sourceText().equals(source.entries().get(key))) {
                        issues.add(new Issue("STALE_SOURCE", modId, namespace.getKey(), key,
                                "Saved source text is changed or absent in the installed source."));
                        continue;
                    }
                    if (!PrintfPlaceholders.compatible(entry.sourceText(), entry.translation())) {
                        issues.add(new Issue("PLACEHOLDER_MISMATCH", modId, namespace.getKey(), key,
                                "Translation has incompatible or unsupported printf placeholders."));
                        continue;
                    }
                    long characters = (long) key.length() + entry.translation().length();
                    if (candidateEntries >= limits.entries() || characters > limits.characters() - candidateCharacters) {
                        issues.terminal(new Issue("RUNTIME_LIMIT", modId, namespace.getKey(), key,
                                "Combined local translations exceed the runtime budget; all overrides were skipped."));
                        return new Snapshot(Map.of(), issues.values(), loadedProjects, translatedEntries, 0);
                    }
                    candidateEntries++;
                    candidateCharacters += characters;
                    candidates.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new Candidate(modId, namespace.getKey(), entry.translation()));
                }
            }
        }
        var overrides = new TreeMap<String, String>();
        int appliedEntries = 0;
        for (var item : candidates.entrySet()) {
            if (protectedGlobalKeys.contains(item.getKey())) continue;
            List<Candidate> origins = item.getValue();
            String value = origins.getFirst().value();
            if (origins.stream().anyMatch(origin -> !value.equals(origin.value()))) {
                for (var origin : origins) {
                    issues.add(new Issue("GLOBAL_KEY_CONFLICT", origin.modId(), origin.namespace(), item.getKey(),
                            "Different local translations claim the same global Minecraft key; all were skipped."));
                }
                continue;
            }
            overrides.put(item.getKey(), value);
            appliedEntries += origins.size();
        }
        return new Snapshot(overrides, issues.values(), loadedProjects, translatedEntries, appliedEntries);
    }

    private static Map<String, LanguageResource> sources(TranslationProject project, DiscoveryCatalog catalog) {
        if (catalog.issues().stream().anyMatch(issue -> issue.severity() != Severity.INFO)) {
            throw new IllegalArgumentException("Discovery has unresolved warnings or errors.");
        }
        var namespaces = new HashSet<String>();
        var sources = new TreeMap<String, LanguageResource>();
        for (var namespace : catalog.namespaces()) {
            if (!namespaces.add(namespace.namespace())) throw new IllegalArgumentException("Duplicate source namespace.");
            var locales = new HashSet<String>();
            for (var resource : namespace.resources()) {
                if (resource.status() != ResourceStatus.VALID || !locales.add(resource.locale())) {
                    throw new IllegalArgumentException("Discovery contains invalid or conflicting resources.");
                }
                if (!resource.resourcePath().equals("assets/" + namespace.namespace() + "/lang/" + resource.locale() + ".json")) {
                    throw new IllegalArgumentException("Source resource path is not canonical.");
                }
            }
            var saved = project.namespaces().get(namespace.namespace());
            if (saved == null) continue;
            // A deliberate saved source selection remains authoritative when new locales appear.
            var source = namespace.resources().stream()
                    .filter(resource -> resource.locale().equals(saved.sourceLocale())
                            && resource.resourcePath().equals(saved.sourcePath()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Saved source locale is no longer available."));
            sources.put(namespace.namespace(), source);
        }
        if (!sources.keySet().containsAll(project.namespaces().keySet())) {
            throw new IllegalArgumentException("A saved source namespace is absent from discovery.");
        }
        return sources;
    }

    private record Candidate(String modId, String namespace, String value) { }

    record Limits(int entries, long characters, int issues) {
        Limits {
            if (entries < 1 || characters < 1 || issues < 2) throw new IllegalArgumentException("Invalid runtime limits.");
        }
    }

    private static final class IssueCollector {
        private final int maximum;
        private final List<Issue> values = new ArrayList<>();

        private IssueCollector(int maximum) { this.maximum = maximum; }

        private void add(Issue issue) {
            if (values.size() < maximum - 1) values.add(issue);
            else if (values.size() == maximum - 1) {
                values.add(new Issue("ISSUES_TRUNCATED", null, null, null,
                        "Additional runtime diagnostics were omitted after reaching the diagnostic limit."));
            }
        }

        private void terminal(Issue issue) {
            // A fatal budget failure must remain visible even after ordinary diagnostics filled the cap.
            if (values.size() == maximum) values.set(maximum - 2, issue);
            else values.add(issue);
        }

        private List<Issue> values() { return values; }
    }

    public record Issue(String code, String modId, String namespace, String key, String message) { }

    /** appliedEntries includes identical-value origins; overrides.size() counts distinct global keys. */
    public record Snapshot(Map<String, String> overrides, List<Issue> issues, int loadedProjects,
                           int translatedEntries, int appliedEntries) {
        public Snapshot {
            overrides = Collections.unmodifiableMap(new TreeMap<>(overrides));
            issues = List.copyOf(issues);
        }
    }
}

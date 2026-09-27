package dev.kitomods.modlangcollective.discovery;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable discovery evidence, without machine-specific source paths or inferred pack precedence. */
public record DiscoveryCatalog(String modId, String modVersion,
                               List<NamespaceCatalog> namespaces,
                               List<DiscoveryIssue> issues, Counts counts, String displayName) {
    public DiscoveryCatalog(String modId, String modVersion, List<NamespaceCatalog> namespaces,
                            List<DiscoveryIssue> issues, Counts counts) {
        this(modId, modVersion, namespaces, issues, counts, modId);
    }

    public DiscoveryCatalog {
        java.util.Objects.requireNonNull(modId, "modId");
        java.util.Objects.requireNonNull(modVersion, "modVersion");
        java.util.Objects.requireNonNull(counts, "counts");
        java.util.Objects.requireNonNull(displayName, "displayName");
        namespaces = List.copyOf(namespaces);
        issues = List.copyOf(issues);
    }

    public record NamespaceCatalog(String namespace, List<LanguageResource> resources,
                                   SourceSelection source) {
        public NamespaceCatalog {
            resources = List.copyOf(resources);
        }
    }

    public record LanguageResource(int rootIndex, String resourcePath, String locale,
                                   Map<String, String> entries, ResourceStatus status) {
        public LanguageResource {
            entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        }
    }

    /** Locale preference describes discovered files; usable additionally requires one valid file. */
    public record SourceSelection(SourceState state, Optional<String> locale, boolean usable) { }

    public record DiscoveryIssue(Severity severity, String code, int rootIndex,
                                 String resourcePath, String message) { }

    /** Counts include all discovered resource candidates, including invalid and conflicting files. */
    public record Counts(int namespaceCount, int resourceCount, int entryCount, int issueCount) { }

    public enum ResourceStatus { VALID, PARTIAL, INVALID, TOO_LARGE, IO_ERROR, LIMIT_EXCEEDED }
    public enum SourceState { ENGLISH, SINGLE_LOCALE_FALLBACK, AMBIGUOUS, NONE }
    public enum Severity { INFO, WARNING, ERROR }
}

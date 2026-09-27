package dev.kitomods.modlangcollective.project;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Version-two local work. Bundled translations are references, never implicit local work. */
public record TranslationProject(int schemaVersion, String modId, String modVersion,
                                 String targetLocale, Map<String, Namespace> namespaces) {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_ENTRIES = 100_000;
    public static final int MAX_NAMESPACES = 1_024;
    public static final int MAX_HISTORY = 1_024;
    public static final int MAX_TEXT_LENGTH = 1_048_576;
    private static final Pattern COMPONENT = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,128}");
    private static final Pattern RESERVED = Pattern.compile("(?:con|prn|aux|nul|com[0-9]|lpt[0-9])", Pattern.CASE_INSENSITIVE);

    public TranslationProject {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported project schema: " + schemaVersion);
        validateComponent(modId);
        validateComponent(targetLocale);
        text(modVersion);
        namespaces = immutable(namespaces);
        if (namespaces.size() > MAX_NAMESPACES) throw new IllegalArgumentException("Too many namespaces.");
        long count = 0;
        for (var item : namespaces.entrySet()) {
            String name = item.getKey();
            if (!NAMESPACE.matcher(name).matches() || name.equals(".") || name.equals("..")) {
                throw new IllegalArgumentException("Invalid namespace.");
            }
            Namespace namespace = item.getValue();
            if (!namespace.sourcePath().equals("assets/" + name + "/lang/" + namespace.sourceLocale() + ".json")) {
                throw new IllegalArgumentException("Source path does not match namespace and locale.");
            }
            count += namespace.entries().size();
        }
        if (count > MAX_ENTRIES) throw new IllegalArgumentException("Too many project entries.");
    }

    public record Namespace(String sourceLocale, String sourcePath, Map<String, Entry> entries) {
        public Namespace {
            validateComponent(sourceLocale);
            text(sourcePath);
            entries = immutable(entries);
            if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many namespace entries.");
            entries.keySet().forEach(TranslationProject::text);
        }
    }

    public record Entry(String sourceText, List<String> sourceHistory, String translation,
                        String bundledTranslation, State state) {
        public Entry {
            text(sourceText);
            sourceHistory = List.copyOf(sourceHistory);
            if (sourceHistory.size() > MAX_HISTORY) throw new IllegalArgumentException("Source history limit reached.");
            sourceHistory.forEach(TranslationProject::text);
            if (translation != null) text(translation);
            if (bundledTranslation != null) text(bundledTranslation);
            Objects.requireNonNull(state, "state");
            if (state == State.TRANSLATED && translation == null) throw new IllegalArgumentException("Translated entries require local text.");
        }

        /** Explicit editing action, including accepting a deliberately empty string. */
        public Entry withTranslation(String value) {
            if (state == State.ARCHIVED) throw new IllegalStateException("Archived entries cannot be edited.");
            return new Entry(sourceText, sourceHistory, value, bundledTranslation,
                    value == null ? State.PENDING : State.TRANSLATED);
        }
    }

    public enum State { PENDING, TRANSLATED, ARCHIVED }

    public static void validateComponent(String value) {
        if (value == null || !COMPONENT.matcher(value).matches() || RESERVED.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid project path component.");
        }
    }

    private static <T> Map<String, T> immutable(Map<String, T> values) {
        Objects.requireNonNull(values, "map");
        var copy = new TreeMap<String, T>();
        values.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static void text(String value) {
        Objects.requireNonNull(value, "text");
        if (value.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Text exceeds the project limit.");
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("Unpaired Unicode surrogate.");
                }
            } else if (Character.isLowSurrogate(current)) throw new IllegalArgumentException("Unpaired Unicode surrogate.");
        }
    }
}

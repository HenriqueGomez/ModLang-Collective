package dev.kitomods.modlangcollective.editor;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.discovery.TranslationTextPolicy;
import dev.kitomods.modlangcollective.project.ProjectStore;
import dev.kitomods.modlangcollective.project.TranslationProject;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static dev.kitomods.modlangcollective.project.TranslationProject.*;

/** Widget-independent editor state. Saving persists local work; it does not apply runtime resources. */
public final class EditorSession {
    private final ProjectStore store;
    private final String displayName;
    private TranslationProject baseline;
    private TranslationProject project;
    private String revision;
    private String selectedNamespace;
    private String selectedKey;
    private String search = "";
    private Filter filter = Filter.ALL;
    private boolean removed;

    private EditorSession(ProjectStore store, TranslationProject project, String revision, String displayName) {
        this.displayName = displayName;
        this.store = Objects.requireNonNull(store);
        this.baseline = project;
        this.project = project;
        this.revision = revision;
        resetSelection();
    }

    public static EditorSession load(ProjectStore store, String modId, String targetLocale) throws IOException {
        var loaded = store.load(modId, targetLocale)
                .orElseThrow(() -> new NoSuchFileException("The target project no longer exists."));
        return new EditorSession(store, loaded.project(), loaded.revision(), modId);
    }

    /** Keeps discovery identity available even when loading a legacy saved project. */
    public static EditorSession load(ProjectStore store, DiscoveryCatalog catalog, String targetLocale) throws IOException {
        var loaded = store.load(catalog.modId(), targetLocale)
                .orElseThrow(() -> new NoSuchFileException("The target project no longer exists."));
        return new EditorSession(store, loaded.project(), loaded.revision(), catalog.displayName());
    }

    private boolean protectedEntry(Entry entry) {
        return TranslationTextPolicy.isProtected(project.modId(), displayName, entry.sourceText());
    }

    /** Creates an unsaved draft. The create-only save refuses to overwrite an existing target. */
    public static EditorSession create(ProjectStore store, DiscoveryCatalog catalog, String targetLocale,
                                       Map<String, String> sourceLocales) {
        return new EditorSession(store, SourceChoices.create(catalog, targetLocale, sourceLocales), null, catalog.displayName());
    }

    public TranslationProject project() { return project; }
    public boolean dirty() { return !removed && (revision == null || !project.equals(baseline)); }
    public boolean persisted() { return !removed && revision != null; }
    public String revision() { return revision; }
    public String selectedNamespace() { return selectedNamespace; }
    public String selectedKey() { return selectedKey; }
    public String search() { return search; }
    public Filter filter() { return filter; }

    public Optional<Entry> selectedEntry() {
        return selectedNamespace == null || selectedKey == null ? Optional.empty()
                : Optional.ofNullable(project.namespaces().get(selectedNamespace).entries().get(selectedKey)).filter(entry -> !protectedEntry(entry));
    }

    public void selectNamespace(String namespace) {
        if (!project.namespaces().containsKey(namespace)) throw new IllegalArgumentException("Unknown namespace.");
        selectedNamespace = namespace;
        selectedKey = visibleEntries().stream().filter(row -> row.namespace().equals(namespace))
                .map(EntryRow::key).findFirst().orElse(null);
    }

    public void selectKey(String key) {
        if (selectedNamespace == null || !project.namespaces().get(selectedNamespace).entries().containsKey(key)) {
            throw new IllegalArgumentException("Unknown entry in the selected namespace.");
        }
        if (protectedEntry(project.namespaces().get(selectedNamespace).entries().get(key))) throw new IllegalArgumentException("Original-only text cannot be edited.");
        selectedKey = key;
    }

    public void setSearch(String value) {
        search = Objects.requireNonNull(value);
        reconcileSelection();
    }

    public void setFilter(Filter value) {
        filter = Objects.requireNonNull(value);
        reconcileSelection();
    }

    public List<EntryRow> visibleEntries() {
        String query = search.toLowerCase(Locale.ROOT);
        return project.namespaces().entrySet().stream()
                .flatMap(namespace -> namespace.getValue().entries().entrySet().stream()
                        .map(item -> new EntryRow(namespace.getKey(), item.getKey(), item.getValue())))
                .filter(row -> !protectedEntry(row.entry()))
                .filter(row -> filter.matches(row.entry()))
                .filter(row -> contains(row.key(), query) || contains(row.entry().sourceText(), query)
                        || contains(row.entry().translation(), query))
                .toList();
    }

    public void selectEntry(String namespace, String key) {
        if (!project.namespaces().containsKey(namespace)
                || !project.namespaces().get(namespace).entries().containsKey(key)) {
            throw new IllegalArgumentException("Unknown entry.");
        }
        if (protectedEntry(project.namespaces().get(namespace).entries().get(key))) throw new IllegalArgumentException("Original-only text cannot be edited.");
        selectedNamespace = namespace;
        selectedKey = key;
    }

    /** Editing retains text as unconfirmed, without moving the selection while typing. */
    public void stageTranslation(String text) { stageTranslation(selectedNamespace, selectedKey, text); }

    public void stageTranslation(String namespace, String key, String text) {
        requireActive();
        Objects.requireNonNull(text);
        Namespace currentNamespace = project.namespaces().get(namespace);
        if (currentNamespace == null || !currentNamespace.entries().containsKey(key)) {
            throw new IllegalArgumentException("Unknown entry.");
        }
        Entry current = currentNamespace.entries().get(key);
        if (protectedEntry(current)) throw new IllegalStateException("Original-only text cannot be edited.");
        if (current.state() == State.ARCHIVED) throw new IllegalStateException("Archived entries cannot be edited.");
        var entries = new TreeMap<>(currentNamespace.entries());
        entries.put(key, new Entry(current.sourceText(), current.sourceHistory(), text,
                current.bundledTranslation(), State.PENDING));
        var namespaces = new TreeMap<>(project.namespaces());
        namespaces.put(namespace, new Namespace(currentNamespace.sourceLocale(), currentNamespace.sourcePath(), entries));
        project = new TranslationProject(SCHEMA_VERSION, project.modId(), project.modVersion(), project.targetLocale(), namespaces);
    }

    public int pendingConfirmations() {
        return (int) project.namespaces().values().stream().flatMap(ns -> ns.entries().values().stream())
                .filter(entry -> !protectedEntry(entry) && entry.state() == State.PENDING && entry.translation() != null).count();
    }

    /** Call only after explicit confirmation; untouched entries remain untranslated. */
    public void confirmPendingTranslations() {
        requireActive();
        var namespaces = new TreeMap<String, Namespace>();
        project.namespaces().forEach((name, namespace) -> {
            var entries = new TreeMap<String, Entry>();
            namespace.entries().forEach((key, entry) -> entries.put(key,
                    !protectedEntry(entry) && entry.state() == State.PENDING && entry.translation() != null
                            ? entry.withTranslation(entry.translation()) : entry));
            namespaces.put(name, new Namespace(namespace.sourceLocale(), namespace.sourcePath(), entries));
        });
        project = new TranslationProject(SCHEMA_VERSION, project.modId(), project.modVersion(), project.targetLocale(), namespaces);
        reconcileSelection();
    }

    /** Accepting an empty string intentionally marks the entry translated. */
    public void acceptTranslation(String text) { replaceTranslation(Objects.requireNonNull(text)); }

    /** Clearing local work is distinct from accepting an empty translation. */
    public void clearTranslation() { replaceTranslation(null); }

    public void save() throws IOException {
        requireActive();
        String savedRevision = store.save(project, revision);
        revision = savedRevision;
        baseline = project;
    }

    /** Explicit whole-language reset. Commit first so write conflicts preserve the draft. */
    public void resetAndSave() throws IOException {
        requireActive();
        var namespaces = new TreeMap<String, Namespace>();
        project.namespaces().forEach((name, namespace) -> {
            var entries = new TreeMap<String, Entry>();
            namespace.entries().forEach((key, entry) -> entries.put(key,
                    new Entry(entry.sourceText(), entry.sourceHistory(), null, entry.bundledTranslation(),
                            entry.state() == State.ARCHIVED ? State.ARCHIVED : State.PENDING)));
            namespaces.put(name, new Namespace(namespace.sourceLocale(), namespace.sourcePath(), entries));
        });
        var reset = new TranslationProject(SCHEMA_VERSION, project.modId(), project.modVersion(), project.targetLocale(), namespaces);
        String savedRevision = store.save(reset, revision);
        project = reset;
        baseline = reset;
        revision = savedRevision;
        filter = Filter.ALL;
        search = "";
        resetSelection();
    }

    /** Reloads external edits, or resets an unsaved draft when no target exists yet. */
    public void discard() throws IOException {
        requireActive();
        var latest = store.load(project.modId(), project.targetLocale());
        if (latest.isPresent()) {
            project = latest.get().project();
            baseline = project;
            revision = latest.get().revision();
        } else if (revision == null) {
            project = baseline;
        } else {
            throw new NoSuchFileException("The target project no longer exists; local edits were retained.");
        }
        if (selectedNamespace == null || !project.namespaces().containsKey(selectedNamespace)) resetSelection();
        else reconcileSelection();
    }

    /** Call only after explicit UI confirmation. The store retains a recoverable removed copy. */
    public Path remove() throws IOException {
        requireActive();
        if (revision == null) throw new IllegalStateException("An unsaved target cannot be removed from storage.");
        Path retained = store.remove(project.modId(), project.targetLocale(), revision);
        removed = true;
        return retained;
    }

    public Progress progress() {
        int pending = 0, translated = 0, archived = 0;
        for (Namespace namespace : project.namespaces().values()) {
            for (Entry entry : namespace.entries().values()) {
                if (protectedEntry(entry)) continue;
                switch (entry.state()) {
                    case PENDING -> pending++;
                    case TRANSLATED -> translated++;
                    case ARCHIVED -> archived++;
                }
            }
        }
        return new Progress(pending, translated, archived);
    }

    private void replaceTranslation(String text) {
        requireActive();
        Entry current = selectedEntry().orElseThrow(() -> new IllegalStateException("Select an entry first."));
        Entry updated = current.withTranslation(text);
        Namespace namespace = project.namespaces().get(selectedNamespace);
        var entries = new TreeMap<>(namespace.entries());
        entries.put(selectedKey, updated);
        var namespaces = new TreeMap<>(project.namespaces());
        namespaces.put(selectedNamespace, new Namespace(namespace.sourceLocale(), namespace.sourcePath(), entries));
        project = new TranslationProject(project.schemaVersion(), project.modId(), project.modVersion(), project.targetLocale(), namespaces);
        reconcileSelection();
    }

    private void resetSelection() {
        selectedNamespace = project.namespaces().keySet().stream().findFirst().orElse(null);
        selectedKey = null;
        reconcileSelection();
    }

    private void reconcileSelection() {
        List<EntryRow> visible = visibleEntries();
        if (visible.stream().noneMatch(row -> row.namespace().equals(selectedNamespace) && row.key().equals(selectedKey))) {
            selectedNamespace = visible.isEmpty() ? null : visible.getFirst().namespace();
            selectedKey = visible.isEmpty() ? null : visible.getFirst().key();
        }
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private void requireActive() {
        if (removed) throw new IllegalStateException("The target project was removed; open a new editor session.");
    }

    public enum Filter {
        ALL, NOT_TRANSLATED, PENDING, TRANSLATED, ARCHIVED;
        private boolean matches(Entry entry) {
            return switch (this) {
                case ALL -> entry.state() != State.ARCHIVED;
                case NOT_TRANSLATED -> entry.state() == State.PENDING && entry.translation() == null;
                case PENDING -> entry.state() == State.PENDING && entry.translation() != null;
                case TRANSLATED -> entry.state() == State.TRANSLATED;
                case ARCHIVED -> entry.state() == State.ARCHIVED;
            };
        }
    }

    public record EntryRow(String namespace, String key, Entry entry) { }
    public record Progress(int pending, int translated, int archived) {
        public int active() { return pending + translated; }
    }
}

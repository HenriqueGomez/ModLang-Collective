package dev.kitomods.modlangcollective.client.screen;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.discovery.TranslationTextPolicy;
import dev.kitomods.modlangcollective.editor.EditorSession;
import dev.kitomods.modlangcollective.editor.TargetLanguages;
import dev.kitomods.modlangcollective.editor.SourceChoices;
import dev.kitomods.modlangcollective.project.ProjectStore;
import dev.kitomods.modlangcollective.project.TranslationProject;
import dev.kitomods.modlangcollective.client.runtime.RuntimeLanguageBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import static dev.kitomods.modlangcollective.client.screen.EditorScreens.language;
import static dev.kitomods.modlangcollective.client.screen.EditorScreens.text;

/** Language, original text and translation are the primary editing concepts. */
final class TranslationEditorScreen extends EditorBaseScreen {
    private final ProjectStore store;
    private final DiscoveryCatalog catalog;
    private final Map<String, EditorSession> sessions = new TreeMap<>();
    private final TreeSet<String> stored = new TreeSet<>();
    private final Map<String, String> referenceSources = new TreeMap<>();
    private EditorSession session;
    private boolean viewingOriginal = true;
    private String referenceNamespace;
    private String referenceKey;
    private String referenceSearch = "";
    private boolean loadedTargets;
    private boolean wide;
    private int languageWidth;
    private int entriesX;
    private int entriesWidth;
    private int rightX;
    private int rightWidth;
    private int sourceY;
    private int sourceHeight;
    private int targetY;
    private int targetMaxHeight;
    private MultiLineEditBox target;
    private EditBox entrySearch;
    private Button confirm;
    private Button resetText;
    private Button discardChanges;
    private EditorChoiceList languageList;
    private EditorChoiceList entryList;
    private double languageScroll;
    private double entryScroll;
    private RuntimeLanguageBridge.ApplyReport application = RuntimeLanguageBridge.latestReport();
    private boolean applyFailed;
    private Component feedback = Component.empty();
    private boolean restoringText;
    private TranslationProject progressProject;
    private EditorSession.Progress cachedProgress;

    TranslationEditorScreen(Screen parent, ProjectStore store, DiscoveryCatalog catalog) {
        super(parent, text("title", "Translate: %s", EditorScreens.modName(catalog.modId())));
        this.store = store;
        this.catalog = catalog;
        showStatusLine = false;
        status = text("local_only", "Choose a language, translate a text, then confirm it. Save can confirm all pending translations together.");
        try { referenceSources.putAll(SourceChoices.defaults(catalog)); }
        catch (IllegalArgumentException ignored) { /* The view will explain that source text is unavailable. */ }
    }

    @Override protected void init() {
        if (languageList != null) languageScroll = languageList.scrollAmount();
        if (entryList != null) entryScroll = entryList.scrollAmount();
        languageList = null;
        entryList = null;
        target = null;
        confirm = null;
        resetText = null;
        discardChanges = null;
        if (!minimumSize(320, 240)) return;
        if (!loadedTargets) {
            loadedTargets = true;
            try { stored.addAll(store.listTargets(catalog.modId())); }
            catch (IOException | IllegalArgumentException exception) { status = failure(exception); }
        }
        wide = width >= 680;
        languageWidth = wide ? 156 : 104;
        entriesX = languageWidth + 20;
        entriesWidth = 210;
        rightX = wide ? entriesX + entriesWidth + 10 : languageWidth + 20;
        rightWidth = width - rightX - 10;
        sourceY = wide ? 46 : 76;
        sourceHeight = Math.max(28, (height - sourceY - 116) / 2);
        targetY = sourceY + sourceHeight + 22;
        buildLanguages();
        button(text("save", "Save"), 10, height - 52, languageWidth, () -> requestSave(false, false)).active = session != null && !viewingOriginal;
        button(text("back", "Back"), 10, height - 28, languageWidth, this::onClose);
        if (!viewingOriginal && session != null) {
            int half = (rightWidth - 4) / 2;
            discardChanges = button(text("discard_language_changes", "Discard changes"), rightX, height - 28, half, this::reload);
            discardChanges.active = session.dirty();
            button(text("help", "Help"), rightX + half + 4, height - 28, rightWidth - half - 4, this::showHelp);
        } else button(text("help", "Help"), rightX, height - 28, rightWidth, this::showHelp);
        if (viewingOriginal) {
            buildOriginalView();
            return;
        }
        if (session == null) {
            viewingOriginal = true;
            buildOriginalView();
            return;
        }
        if (wide) buildEntryList();
        else {
            int half = (rightWidth - 4) / 2;
            button(text("choose_text", "Choose text"), rightX, 32, half, this::chooseEntry);
            button(filterLabel(session.filter()), rightX + half + 4, 32, rightWidth - half - 4, this::chooseFilter);
        }
        buildTextFields();
    }

    private void buildLanguages() {
        button(text("add_language", "Add language"), 10, 46, languageWidth, this::chooseTarget);
        button(text("original_pin", "Original"), 10, 72, languageWidth, this::selectOriginal);
        var locales = new TreeSet<>(stored);
        locales.addAll(sessions.keySet());
        var choices = locales.stream().map(locale -> {
            boolean dirty = sessions.containsKey(locale) && sessions.get(locale).dirty();
            Component label = language(locale).copy().append(dirty ? " *" : "");
            return new ChoiceScreen.Choice(locale, label, dirty ? text("unsaved_changes", "Unsaved changes") : Component.empty());
        }).toList();
        languageList = addRenderableWidget(new EditorChoiceList(minecraft, 10, 98, languageWidth,
                height - 174, choices, !viewingOriginal && session != null ? session.project().targetLocale() : null,
                this::selectTarget, this::resetLanguage, this::removeLanguage));
        languageList.setScrollAmount(languageScroll);
    }

    private record ReferenceRow(String namespace, String key, String locale, String text) { }

    private List<ReferenceRow> referenceRows() {
        var rows = new ArrayList<ReferenceRow>();
        for (var namespace : catalog.namespaces()) {
            String locale = referenceSources.get(namespace.namespace());
            if (locale == null) continue;
            namespace.resources().stream().filter(resource -> resource.locale().equals(locale)
                            && resource.status() == DiscoveryCatalog.ResourceStatus.VALID)
                    .findFirst().ifPresent(resource -> resource.entries().forEach((key, value) -> {
                        if (TranslationTextPolicy.isProtected(catalog, value)) return;
                        if (referenceSearch.isBlank() || key.toLowerCase(java.util.Locale.ROOT).contains(referenceSearch.toLowerCase(java.util.Locale.ROOT))
                                || value.toLowerCase(java.util.Locale.ROOT).contains(referenceSearch.toLowerCase(java.util.Locale.ROOT)))
                            rows.add(new ReferenceRow(namespace.namespace(), key, locale, value));
                    }));
        }
        return List.copyOf(rows);
    }

    private ReferenceRow selectedReferenceRow() {
        if (referenceNamespace == null || referenceKey == null) return null;
        String locale = referenceSources.get(referenceNamespace);
        if (locale == null) return null;
        return catalog.namespaces().stream().filter(namespace -> namespace.namespace().equals(referenceNamespace))
                .flatMap(namespace -> namespace.resources().stream())
                .filter(resource -> resource.locale().equals(locale) && resource.status() == DiscoveryCatalog.ResourceStatus.VALID)
                .findFirst().map(resource -> resource.entries().containsKey(referenceKey)
                        ? new ReferenceRow(referenceNamespace, referenceKey, locale, resource.entries().get(referenceKey)) : null)
                .filter(row -> !TranslationTextPolicy.isProtected(catalog, row.text()))
                .orElse(null);
    }

    private void buildOriginalView() {
        int sourceCount = referenceSources.size();
        if (sourceCount == 0) {
            readOnly(text("original_title", "Original texts"),
                    text("original_missing", "Choose Original again to select a source language, or no safe source text is available.").getString(),
                    rightX, sourceY, rightWidth, height - sourceY - 65);
            return;
        }
        List<ReferenceRow> visibleRows = referenceRows();
        boolean selectedVisible = visibleRows.stream().anyMatch(row -> row.namespace().equals(referenceNamespace)
                && row.key().equals(referenceKey));
        if (!selectedVisible && !visibleRows.isEmpty()) {
            referenceNamespace = visibleRows.getFirst().namespace();
            referenceKey = visibleRows.getFirst().key();
        }
        if (wide) {
            entrySearch = addRenderableWidget(new EditBox(font, entriesX, 46, entriesWidth, 20, text("search_entries", "Search texts")));
            entrySearch.setHint(text("search_entries", "Search texts"));
            entrySearch.setMaxLength(1024);
            entrySearch.setValue(referenceSearch);
            entrySearch.setResponder(value -> {
                int cursor = entrySearch.getCursorPosition();
                referenceSearch = value;
                entryScroll = 0;
                refresh();
                setFocused(entrySearch);
                entrySearch.setFocused(true);
                entrySearch.setCursorPosition(Math.min(cursor, value.length()));
                entrySearch.setHighlightPos(Math.min(cursor, value.length()));
            });
            buildOriginalEntryList();
        } else {
            button(text("choose_text", "Choose text"), rightX, 32, rightWidth, this::chooseOriginalEntry);
        }
        var selected = visibleRows.stream().filter(row -> row.namespace().equals(referenceNamespace)
                && row.key().equals(referenceKey)).findFirst();
        if (selected.isEmpty()) {
            readOnly(text("original_title", "Original texts"),
                    text("original_help", "Select a text to see it in its original language. This view is read-only.").getString(),
                    rightX, sourceY, rightWidth, height - sourceY - 65);
            return;
        }
        var row = selected.get();
        readOnly(text("original", "Original"), row.text(), rightX, sourceY, rightWidth, height - sourceY - 65);
    }

    private void buildOriginalEntryList() {
        List<ReferenceRow> rows = referenceRows();
        String selected = null;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).namespace().equals(referenceNamespace) && rows.get(i).key().equals(referenceKey)) selected = Integer.toString(i);
        }
        List<ChoiceScreen.Choice> choices = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ReferenceRow row = rows.get(i);
            String label = row.text().isEmpty() ? text("empty_original", "(Empty original text)").getString() : row.text().replace('\n', ' ');
            choices.add(new ChoiceScreen.Choice(Integer.toString(i), Component.literal(label),
                    Component.literal(row.namespace() + " · " + language(row.locale()).getString())));
        }
        entryList = addRenderableWidget(new EditorChoiceList(minecraft, entriesX, 98, entriesWidth, height - 174,
                choices, selected, id -> {
                    ReferenceRow row = rows.get(Integer.parseInt(id));
                    referenceNamespace = row.namespace();
                    referenceKey = row.key();
                    refresh();
                }));
        entryList.setScrollAmount(entryScroll);
    }

    private void selectOriginal() {
        viewingOriginal = true;
        referenceSearch = "";
        entryScroll = 0;
        try {
            var defaults = SourceChoices.defaults(catalog);
            defaults.forEach(referenceSources::putIfAbsent);
            var options = SourceChoices.options(catalog);
            var selected = new TreeMap<>(referenceSources);
            defaults.forEach(selected::putIfAbsent);
            chooseReferenceSources(options, selected);
        } catch (IllegalArgumentException exception) { error(exception); }
    }

    private void chooseReferenceSources(Map<String, List<String>> options, Map<String, String> selected) {
        var missing = options.keySet().stream().filter(namespace -> !selected.containsKey(namespace)).findFirst();
        if (missing.isPresent()) {
            String namespace = missing.get();
            var choices = options.get(namespace).stream().map(locale -> new ChoiceScreen.Choice(locale, language(locale),
                    text("reference_source_hint", "Use this language as the read-only reference for this part of the mod."))).toList();
            minecraft.gui.setScreen(new ChoiceScreen(this, text("choose_reference_source", "Choose an original language for %s", namespace), choices, source -> {
                selected.put(namespace, source);
                chooseReferenceSources(options, selected);
            }));
            return;
        }
        referenceSources.clear();
        referenceSources.putAll(selected);
        referenceNamespace = null;
        referenceKey = null;
        session = null;
        minecraft.gui.setScreen(this);
    }

    private void chooseOriginalEntry() {
        List<ReferenceRow> rows = referenceRows();
        List<ChoiceScreen.Choice> choices = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ReferenceRow row = rows.get(i);
            String label = row.text().isEmpty() ? text("empty_original", "(Empty original text)").getString() : row.text().replace('\n', ' ');
            choices.add(new ChoiceScreen.Choice(Integer.toString(i), Component.literal(label), Component.literal(row.namespace())));
        }
        minecraft.gui.setScreen(new ChoiceScreen(this, text("choose_text", "Choose text"), choices, id -> {
            ReferenceRow row = rows.get(Integer.parseInt(id));
            referenceNamespace = row.namespace();
            referenceKey = row.key();
            minecraft.gui.setScreen(this);
        }));
    }

    private List<ChoiceScreen.Choice> entryChoices(List<EditorSession.EntryRow> rows) {
        var choices = new ArrayList<ChoiceScreen.Choice>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            String original = row.entry().sourceText();
            Component label = original.isEmpty() ? text("empty_original", "(Empty original text)") : Component.literal(original.replace('\n', ' '));
            choices.add(new ChoiceScreen.Choice(Integer.toString(i), label, stateLabel(row.entry())));
        }
        return choices;
    }

    private void buildEntryList() {
        entrySearch = addRenderableWidget(new EditBox(font, entriesX, 46, entriesWidth, 20, text("search_entries", "Search texts")));
        entrySearch.setHint(text("search_entries", "Search texts"));
        entrySearch.setMaxLength(1024);
        entrySearch.setValue(session.search());
        button(filterLabel(session.filter()), entriesX, 72, entriesWidth, this::chooseFilter);
        entrySearch.setResponder(value -> {
            int cursor = entrySearch.getCursorPosition();
            session.setSearch(value);
            entryScroll = 0;
            if (entryList != null) entryList.setScrollAmount(0);
            refresh();
            setFocused(entrySearch);
            entrySearch.setFocused(true);
            entrySearch.setCursorPosition(Math.min(cursor, value.length()));
            entrySearch.setHighlightPos(Math.min(cursor, value.length()));
        });
        List<EditorSession.EntryRow> rows = session.visibleEntries();
        String selected = null;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).namespace().equals(session.selectedNamespace()) && rows.get(i).key().equals(session.selectedKey())) selected = Integer.toString(i);
        }
        entryList = addRenderableWidget(new EditorChoiceList(minecraft, entriesX, 98, entriesWidth, height - 174,
                entryChoices(rows), selected, id -> {
                    var row = rows.get(Integer.parseInt(id));
                    session.selectEntry(row.namespace(), row.key());
                    refresh();
                }));
        entryList.setScrollAmount(entryScroll);
    }

    private void buildTextFields() {
        var selected = session.selectedEntry();
        if (selected.isEmpty()) {
            readOnly(text("no_texts", "No matching texts"), text("no_texts_help", "Try another filter or clear the search.").getString(),
                    rightX, sourceY, rightWidth, height - sourceY - 65);
            return;
        }
        var entry = selected.get();
        int maxSourceHeight = Math.max(28, Math.min(height / 3, height - sourceY - 150));
        sourceHeight = measuredTextHeight(entry.sourceText(), rightWidth - 16, maxSourceHeight, 28);
        targetY = sourceY + sourceHeight + 22;
        targetMaxHeight = Math.max(24, height - 76 - targetY);
        readOnly(text("original", "Original"), entry.sourceText(), rightX, sourceY, rightWidth, sourceHeight);
        target = MultiLineEditBox.builder().setX(rightX).setY(targetY)
                .setPlaceholder(text("translation_placeholder", "Type your translation here"))
                .build(font, rightWidth, targetHeight(Objects.toString(entry.translation(), "")), text("translation", "Your translation"));
        target.setCharacterLimit(TranslationProject.MAX_TEXT_LENGTH);
        target.setValue(Objects.toString(entry.translation(), ""));
        String namespace = session.selectedNamespace();
        String key = session.selectedKey();
        target.setValueListener(value -> {
            if (restoringText) return;
            try {
                session.stageTranslation(namespace, key, value);
                resizeTarget(value);
                if (confirm != null) confirm.active = true;
                if (resetText != null) resetText.active = true;
                if (discardChanges != null) discardChanges.active = session.dirty();
                feedback = text("pending_feedback", "Pending confirmation");
            } catch (IllegalArgumentException | IllegalStateException exception) {
                // Restore synchronously without replacing the screen during a widget event.
                restoringText = true;
                try {
                    target.setValue(Objects.toString(session.project().namespaces().get(namespace).entries().get(key).translation(), ""));
                } finally { restoringText = false; }
                status = failure(exception);
                feedback = text("invalid_text", "This edit could not be accepted. Your previous text was kept.");
            }
        });
        addRenderableWidget(target);
        int half = (rightWidth - 4) / 2;
        confirm = button(text("confirm", "Confirm"), rightX, height - 52, half, this::confirmCurrent);
        resetText = button(text("reset_text", "Reset text"), rightX + half + 4, height - 52, rightWidth - half - 4, this::resetTranslation);
        resetText.active = entry.translation() != null;
        confirm.active = entry.translation() != null && entry.state() != TranslationProject.State.TRANSLATED;
    }

    private int targetHeight(String value) {
        return measuredTextHeight(value, rightWidth - 20, targetMaxHeight, Math.min(64, targetMaxHeight));
    }

    /** Resizing the existing widget preserves its focus, selection and caret while text is entered. */
    private void resizeTarget(String value) {
        if (target != null) target.setHeight(targetHeight(value));
    }

    private int measuredTextHeight(String value, int wrapWidth, int maximum, int minimum) {
        int lines = Math.max(1, font.split(Component.literal(Objects.toString(value, "")), Math.max(1, wrapWidth)).size());
        return Math.min(Math.max(1, maximum), Math.max(Math.min(minimum, maximum), lines * 9 + 18));
    }

    private void selectTarget(String locale) {
        try {
            EditorSession selected = sessions.get(locale);
            if (selected == null) {
                selected = EditorSession.load(store, catalog, locale);
                sessions.put(locale, selected);
            }
            session = selected;
            viewingOriginal = false;
            feedback = Component.empty();
            entryScroll = 0;
            if (entryList != null) entryList.setScrollAmount(0);
            refresh();
        } catch (IOException | IllegalArgumentException exception) { error(exception); }
    }

    private void chooseTarget() {
        var choices = minecraft.getLanguageManager().getLanguages().entrySet().stream()
                .filter(item -> TargetLanguages.isSupported(item.getKey()))
                .map(item -> new ChoiceScreen.Choice(item.getKey(), language(item.getKey()), Component.empty())).toList();
        minecraft.gui.setScreen(new ChoiceScreen(this, text("choose_target", "Choose a language"), choices, locale -> {
            if (stored.contains(locale) || sessions.containsKey(locale)) {
                minecraft.gui.setScreen(this);
                selectTarget(locale);
            } else createTarget(locale);
        }));
    }

    private void createTarget(String locale) {
        try {
            if (!TargetLanguages.isSupported(locale) || !minecraft.getLanguageManager().getLanguages().containsKey(locale)) {
                throw new IllegalArgumentException("This language is not available in Minecraft.");
            }
            chooseSources(locale, SourceChoices.options(catalog), new TreeMap<>(SourceChoices.defaults(catalog)));
        } catch (IllegalArgumentException exception) { error(exception); }
    }

    private void chooseSources(String locale, Map<String, List<String>> options, Map<String, String> selected) {
        var missing = options.keySet().stream().filter(namespace -> !selected.containsKey(namespace)).findFirst();
        if (missing.isPresent()) {
            String namespace = missing.get();
            var choices = options.get(namespace).stream().map(source -> new ChoiceScreen.Choice(source, language(source),
                    text("source_choice_hint", "The original language could not be identified. Choose the language you can use as a reference."))).toList();
            minecraft.gui.setScreen(new ChoiceScreen(this, text("choose_source", "Choose the original language"), choices, source -> {
                selected.put(namespace, source);
                chooseSources(locale, options, selected);
            }));
            return;
        }
        EditorSession candidate = null;
        try {
            candidate = EditorSession.create(store, catalog, locale, selected);
            candidate.save();
            sessions.put(locale, candidate);
            stored.add(locale);
            session = candidate;
            viewingOriginal = false;
            entryScroll = 0;
            if (entryList != null) entryList.setScrollAmount(0);
            status = text("created", "Language added. Select a text to start translating.");
            feedback = status;
            minecraft.gui.setScreen(this);
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            if (candidate != null) {
                sessions.put(locale, candidate);
                session = candidate;
            }
            error(exception);
        }
    }

    private void chooseEntry() {
        var rows = session.visibleEntries();
        minecraft.gui.setScreen(new ChoiceScreen(this, text("choose_text", "Choose text"), entryChoices(rows), id -> {
            var row = rows.get(Integer.parseInt(id));
            session.selectEntry(row.namespace(), row.key());
            minecraft.gui.setScreen(this);
        }));
    }

    private void chooseFilter() {
        var choices = List.of(EditorSession.Filter.ALL, EditorSession.Filter.NOT_TRANSLATED,
                EditorSession.Filter.PENDING, EditorSession.Filter.TRANSLATED).stream()
                .map(filter -> new ChoiceScreen.Choice(filter.name(), filterLabel(filter), Component.empty())).toList();
        minecraft.gui.setScreen(new ChoiceScreen(this, text("filter", "Show texts"), choices, filter -> {
            session.setFilter(EditorSession.Filter.valueOf(filter));
            entryScroll = 0;
            if (entryList != null) entryList.setScrollAmount(0);
            minecraft.gui.setScreen(this);
        }));
    }

    private void confirmCurrent() {
        if (target == null || session.selectedEntry().isEmpty() || session.selectedEntry().get().translation() == null) return;
        try {
            session.acceptTranslation(target.getValue());
            session.save();
            stored.add(session.project().targetLocale());
            applySaved(text("confirmed", "Translation confirmed and saved."));
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) { error(exception); }
    }

    private void resetTranslation() {
        if (session == null || session.selectedEntry().isEmpty()) return;
        minecraft.gui.setScreen(new EditorDialogScreen(this, text("reset_title", "Reset this translation?"),
                text("reset_message", "Remove your translation for this text? The original stays available. Save to keep this change."),
                List.of(new EditorDialogScreen.Action(text("reset_translation", "Reset translation"), () -> {
                    session.clearTranslation(); minecraft.gui.setScreen(this);
                }), new EditorDialogScreen.Action(text("cancel", "Cancel"), () -> minecraft.gui.setScreen(this)))));
    }

    private void requestSave(boolean all, boolean close) {
        if (!all && (viewingOriginal || session == null)) return;
        List<EditorSession> toSave = all ? List.copyOf(sessions.values()) : session == null ? List.of() : List.of(session);
        int pending = toSave.stream().mapToInt(EditorSession::pendingConfirmations).sum();
        if (pending == 0) { save(toSave, false, close); return; }
        minecraft.gui.setScreen(new EditorDialogScreen(this, text("pending_title", "Unconfirmed translations"),
                text("pending_message", "There are %s unconfirmed translations. Confirm all and save? Texts you have not translated will stay untranslated.", pending),
                List.of(new EditorDialogScreen.Action(text("confirm_all_save", "Confirm all and save"), () -> save(toSave, true, close)),
                        new EditorDialogScreen.Action(text("cancel", "Cancel"), () -> minecraft.gui.setScreen(this)))));
    }

    private void save(List<EditorSession> toSave, boolean confirmPending, boolean close) {
        try {
            for (EditorSession edited : toSave) {
                if (confirmPending) edited.confirmPendingTranslations();
                if (edited.dirty()) edited.save();
                stored.add(edited.project().targetLocale());
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) { error(exception); return; }
        minecraft.gui.setScreen(this);
        if (applySaved(text("saved", "Saved.")) && close) minecraft.gui.setScreen(parent);
    }

    private boolean applySaved(Component prefix) {
        try {
            application = RuntimeLanguageBridge.reloadSavedTranslations();
            applyFailed = false;
            status = prefix.copy();
            if (session != null && !session.project().targetLocale().equals(application.locale())) {
                status = status.copy().append(" ").append(text("switch_game_language", "Select %s in Minecraft's language settings to use this translation.",
                        language(session.project().targetLocale())));
            }
            if (!application.issues().isEmpty()) status = status.copy().append(" ").append(text("application_warnings", "Some translations could not be applied. See Help for details."));
            feedback = status;
            if (session != null && !session.project().targetLocale().equals(application.locale())) {
                feedback = text("saved_choose_language", "Saved. Set Minecraft to %s to use this translation.", language(session.project().targetLocale()));
            } else if (!application.issues().isEmpty()) {
                feedback = text("saved_with_warnings", "Saved. Some texts could not be applied; see Help.");
            }
            refresh();
            return true;
        } catch (RuntimeException exception) {
            application = null;
            applyFailed = true;
            status = prefix.copy().append(" ").append(text("apply_failed", "Your saved translations are safe, but the game could not refresh them. Choose Retry to try again. %s",
                    Objects.toString(exception.getMessage(), exception.getClass().getSimpleName())));
            minecraft.gui.setScreen(new EditorDialogScreen(this, text("apply_failed_title", "Translations could not be refreshed"), status,
                    List.of(new EditorDialogScreen.Action(text("retry_apply_short", "Retry"), this::retryApplication),
                            new EditorDialogScreen.Action(text("back", "Back"), () -> minecraft.gui.setScreen(this)))));
            return false;
        }
    }

    private void retryApplication() {
        minecraft.gui.setScreen(this);
        applySaved(text("using_saved", "Using saved translations."));
    }

    private EditorSession sessionForLanguage(String locale) throws IOException {
        EditorSession found = sessions.get(locale);
        if (found != null) return found;
        found = EditorSession.load(store, catalog, locale);
        sessions.put(locale, found);
        return found;
    }

    private void resetLanguage(String locale) {
        try {
            EditorSession resetting = sessionForLanguage(locale);
            minecraft.gui.setScreen(new EditorDialogScreen(this, text("reset_language_title", "Reset this language?"),
                    text("reset_language_message", "Clear all translations in %s, including unsaved changes? The language will stay in the list. A backup of the previously saved text will be kept.", language(locale)),
                    List.of(new EditorDialogScreen.Action(text("reset_language_confirm", "Reset language"), () -> {
                        try {
                            resetting.resetAndSave();
                            stored.add(locale);
                            status = text("reset_language_done", "Translations reset.");
                            if (session == resetting) feedback = status;
                            minecraft.gui.setScreen(this);
                            applySaved(status);
                        } catch (IOException | IllegalStateException exception) { error(exception); }
                    }), new EditorDialogScreen.Action(text("cancel", "Cancel"), () -> minecraft.gui.setScreen(this)))));
        } catch (IOException | IllegalStateException exception) { error(exception); }
    }

    private void removeLanguage(String locale) {
        EditorSession removing;
        try { removing = sessionForLanguage(locale); }
        catch (IOException | IllegalStateException exception) { error(exception); return; }
        minecraft.gui.setScreen(new EditorDialogScreen(this, text("remove_title", "Remove this language?"),
                text("remove_message", "Remove your translations for %s, including unsaved changes? A backup of saved text will be kept. Texts included with the mod stay unchanged.", language(locale)),
                List.of(new EditorDialogScreen.Action(text("remove", "Remove language"), () -> {
                    try {
                        if (removing.persisted()) removing.remove();
                        sessions.remove(locale); stored.remove(locale);
                        if (session == removing) { session = null; viewingOriginal = true; }
                        status = text("removed", "Language removed. A backup of the saved translations was kept.");
                        minecraft.gui.setScreen(this);
                        applySaved(status);
                    } catch (IOException | IllegalStateException exception) { error(exception); }
                }), new EditorDialogScreen.Action(text("cancel", "Cancel"), () -> minecraft.gui.setScreen(this)))));
    }

    private void reload() {
        EditorSession reloading = session;
        String locale = reloading.project().targetLocale();
        minecraft.gui.setScreen(new EditorDialogScreen(this, text("reload_title", "Discard unsaved changes?"),
                text("reload_message", "Discard unsaved changes for %s and reload the saved text? Your other languages will not be changed. If loading fails, your current work is kept.", language(locale)),
                List.of(new EditorDialogScreen.Action(text("reload_confirm", "Discard changes and reload"), () -> {
                    try {
                        reloading.discard();
                        if (reloading.persisted()) stored.add(locale);
                        status = text("reloaded", "Saved text loaded.");
                        minecraft.gui.setScreen(this);
                    } catch (IOException | IllegalArgumentException | IllegalStateException exception) { error(exception); }
                }), new EditorDialogScreen.Action(text("cancel", "Cancel"), () -> minecraft.gui.setScreen(this)))));
    }

    private EditorSession.Progress currentProgress() {
        if (progressProject != session.project()) {
            progressProject = session.project();
            cachedProgress = session.progress();
        }
        return cachedProgress;
    }

    private void showHelp() {
        minecraft.gui.setScreen(new HelpScreen(this, this::showApplicationDetails,
                applyFailed ? this::retryApplication : null));
    }

    private void showApplicationDetails(Screen detailsParent) {
        var report = RuntimeLanguageBridge.latestReport();
        var lines = new StringBuilder();
        if (session != null && session.selectedEntry().isPresent()) lines.append(session.selectedNamespace()).append(" / ").append(session.selectedKey()).append("\n\n");
        if (applyFailed) lines.append(status.getString()).append("\n\n");
        if (report == null) lines.append(text("no_application", "No successful language refresh has been reported yet.").getString());
        else {
            lines.append(text("applied", "Applied %s saved texts for %s; %s warnings.",
                    report.appliedKeys(), language(report.locale()), report.issues().size()).getString());
            report.issues().forEach(issue -> lines.append("\n\n").append(issue.modId()).append(" / ")
                    .append(issue.namespace()).append(" / ").append(issue.key()).append("\n").append(issue.message()));
        }
        minecraft.gui.setScreen(new ReferenceScreen(detailsParent, text("runtime_details", "Technical details"), lines.toString()));
    }

    private Component failure(Exception exception) {
        return text("failure", "The operation failed. Your current work was kept. %s", Objects.toString(exception.getMessage(), exception.getClass().getSimpleName()));
    }

    private void error(Exception exception) {
        status = failure(exception);
        minecraft.gui.setScreen(new EditorDialogScreen(this, text("error", "Could not complete the action"), status,
                List.of(new EditorDialogScreen.Action(text("back", "Back"), () -> minecraft.gui.setScreen(this)))));
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (target != null && target.isFocused() && event.isPaste()) {
            String clipboard = minecraft.keyboardHandler.getClipboard();
            if (clipboard.length() > TranslationProject.MAX_TEXT_LENGTH) {
                error(new IllegalArgumentException("The copied text is too long. The current translation was kept."));
                return true;
            }
            if (!StringUtil.filterText(clipboard, true).equals(clipboard)
                    || (long) target.getValue().length() + clipboard.length() > TranslationProject.MAX_TEXT_LENGTH) {
                EditorSession editing = session;
                String namespace = session.selectedNamespace();
                String key = session.selectedKey();
                minecraft.gui.setScreen(new EditorDialogScreen(this, text("exact_paste_title", "Keep all pasted characters?"),
                        text("exact_paste_message", "A normal paste could remove characters or exceed the text limit. Replace the entire translation with the exact copied text, or cancel to keep your current translation."),
                        List.of(new EditorDialogScreen.Action(text("exact_paste_replace", "Replace with copied text"), () -> {
                            try {
                                editing.stageTranslation(namespace, key, clipboard);
                                feedback = text("pending_feedback", "Pending confirmation");
                                minecraft.gui.setScreen(this);
                            } catch (IllegalArgumentException | IllegalStateException exception) { error(exception); }
                        }), new EditorDialogScreen.Action(text("cancel", "Cancel"), () -> minecraft.gui.setScreen(this)))));
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override public void onClose() {
        if (sessions.values().stream().noneMatch(EditorSession::dirty)) { super.onClose(); return; }
        minecraft.gui.setScreen(new EditorDialogScreen(this, text("unsaved_title", "Unsaved changes"),
                text("unsaved_message", "Save your changes before leaving? If any translations are unconfirmed, you will be asked before confirming them."),
                List.of(new EditorDialogScreen.Action(text("save_all_close", "Save and close"), () -> requestSave(true, true)),
                        new EditorDialogScreen.Action(text("discard_close", "Discard changes and close"), () -> minecraft.gui.setScreen(parent)),
                        new EditorDialogScreen.Action(text("cancel", "Cancel"), () -> minecraft.gui.setScreen(this)))));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (compact) return;
        graphics.text(font, text("languages", "Languages"), 10, 33, 0xFFFFFFFF);
        if (wide) graphics.text(font, text("texts", "Texts"), entriesX, 33, 0xFFFFFFFF);
        if (viewingOriginal) {
            ReferenceRow row = selectedReferenceRow();
            if (row != null) graphics.text(font,
                            font.plainSubstrByWidth(text("original_language", "Original: %s", language(row.locale())).getString(), rightWidth),
                            rightX, sourceY - 12, 0xFFFFFFFF);
            return;
        }
        if (session == null) return;
        if (wide) graphics.text(font, font.plainSubstrByWidth(text("progress_short", "%s of %s translated", currentProgress().translated(),
                currentProgress().active()).getString(), entriesWidth), entriesX, height - 68, 0xFFFFFFFF);
        if (session.selectedNamespace() != null) {
            String locale = session.project().namespaces().get(session.selectedNamespace()).sourceLocale();
            graphics.text(font, font.plainSubstrByWidth(text("original_language", "Original: %s", language(locale)).getString(), rightWidth), rightX, sourceY - 12, 0xFFFFFFFF);
        }
        if (session.selectedEntry().isPresent()) {
            graphics.text(font, font.plainSubstrByWidth(text("target_state", "%s: %s", language(session.project().targetLocale()),
                    stateLabel(session.selectedEntry().get())).getString(), rightWidth), rightX, targetY - 12, 0xFFFFFFFF);
        }
        graphics.text(font, font.plainSubstrByWidth(feedback.getString(), rightWidth), rightX, height - 68, 0xFFFFDD88);
        if (mouseX >= rightX && mouseX < rightX + rightWidth && mouseY >= height - 70 && mouseY < height - 57
                && !feedback.getString().isEmpty()) {
            graphics.setTooltipForNextFrame(font, font.split(feedback, Math.max(1, Math.min(360, width - 24))), mouseX, mouseY);
        }
    }

    private static Component filterLabel(EditorSession.Filter filter) {
        return switch (filter) {
            case ALL -> text("filter_all", "All texts");
            case NOT_TRANSLATED -> text("filter_not_translated", "Not translated");
            case PENDING -> text("filter_pending", "Pending");
            case TRANSLATED -> text("filter_translated", "Translated");
            case ARCHIVED -> text("filter_archived", "Archived");
        };
    }

    private static Component stateLabel(TranslationProject.Entry entry) {
        if (entry.state() == TranslationProject.State.TRANSLATED) return text("filter_translated", "Translated");
        if (entry.translation() == null) return text("filter_not_translated", "Not translated");
        return text("filter_pending", "Pending");
    }
}

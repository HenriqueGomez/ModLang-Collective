package dev.kitomods.modlangcollective.client.screen;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import static dev.kitomods.modlangcollective.client.screen.EditorScreens.text;

/** Searchable native scrolling list shared by language, text and mod selection. */
final class ChoiceScreen extends EditorBaseScreen {
    record Choice(String id, Component label, Component detail) { }
    private final List<Choice> choices;
    private final Consumer<String> selected;
    private String query = "";
    private EditorChoiceList list;
    private double scroll;

    ChoiceScreen(Screen parent, Component title, List<Choice> choices, Consumer<String> selected) {
        super(parent, title);
        this.choices = List.copyOf(choices);
        this.selected = selected;
    }

    @Override protected void init() {
        if (list != null) scroll = list.scrollAmount();
        list = null;
        if (!minimumSize(320, 220)) return;
        int w = Math.min(620, width - 40);
        int left = (width - w) / 2;
        var search = addRenderableWidget(new EditBox(font, left, 34, w, 20, text("search", "Search")));
        search.setMaxLength(1024);
        search.setHint(text("search", "Search"));
        search.setValue(query);
        search.setResponder(value -> { query = value; scroll = 0; populate(left, w); });
        button(text("back", "Back"), width / 2 - 50, height - 28, 100, this::onClose);
        populate(left, w);
        setInitialFocus(search);
    }

    private void populate(int left, int w) {
        if (list != null) removeWidget(list);
        String needle = query.toLowerCase(Locale.ROOT);
        List<Choice> matches = choices.stream().filter(choice -> (choice.id() + " " + choice.label().getString()
                + " " + choice.detail().getString()).toLowerCase(Locale.ROOT).contains(needle)).toList();
        list = addRenderableWidget(new EditorChoiceList(minecraft, left, 60, w, height - 115,
                matches, null, selected));
        list.setScrollAmount(scroll);
        status = text("results", "%s results", matches.size());
    }
}

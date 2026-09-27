package dev.kitomods.modlangcollective.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import static dev.kitomods.modlangcollective.client.screen.EditorScreens.text;
import java.util.function.Consumer;

/** Native scrolling, keyboard navigation and narration without pagination controls. */
final class EditorChoiceList extends ObjectSelectionList<EditorChoiceList.Row> {
    private final Consumer<String> selected;
    private final Consumer<String> reset;
    private final Consumer<String> remove;

    EditorChoiceList(Minecraft minecraft, int x, int y, int width, int height,
                     List<ChoiceScreen.Choice> choices, String selectedId, Consumer<String> selected) {
        this(minecraft, x, y, width, height, choices, selectedId, selected, null, null);
    }

    EditorChoiceList(Minecraft minecraft, int x, int y, int width, int height,
                     List<ChoiceScreen.Choice> choices, String selectedId, Consumer<String> selected,
                     Consumer<String> reset, Consumer<String> remove) {
        super(minecraft, width, height, y, 25);
        setX(x);
        this.selected = selected;
        this.reset = reset;
        this.remove = remove;
        centerListVertically = false;
        for (var choice : choices) {
            Row row = new Row(choice);
            addEntry(row);
            if (choice.id().equals(selectedId)) setSelected(row);
        }
    }

    @Override public int getRowWidth() { return Math.max(20, getWidth() - 12); }
    @Override protected int scrollBarX() { return getX() + getWidth() - 6; }
    @Override protected void extractListBackground(GuiGraphicsExtractor graphics) {
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x66000000);
    }
    @Override protected void extractListSeparators(GuiGraphicsExtractor graphics) { }

    final class Row extends ObjectSelectionList.Entry<Row> {
        private final ChoiceScreen.Choice choice;
        private final Button resetButton;
        private final Button removeButton;
        Row(ChoiceScreen.Choice choice) {
            this.choice = choice;
            resetButton = reset == null ? null : Button.builder(Component.literal("R"), ignored -> reset.accept(choice.id()))
                    .bounds(0, 0, 18, 20).build();
            removeButton = remove == null ? null : Button.builder(Component.literal("×"), ignored -> remove.accept(choice.id()))
                    .bounds(0, 0, 18, 20).build();
            if (resetButton != null) resetButton.setTooltip(Tooltip.create(text("reset_language_shortcut", "Reset language (R)")));
            if (removeButton != null) removeButton.setTooltip(Tooltip.create(text("remove_language_shortcut", "Remove language (Delete)")));
        }
        @Override public Component getNarration() {
            var message = choice.label().copy().append(". ").append(choice.detail());
            if (reset != null) message.append(". ").append(text("language_actions_keys", "Press R to reset this language or Delete to remove it. Confirmation is required."));
            return message;
        }
        private boolean actionsVisible(boolean hovered) {
            return resetButton != null && removeButton != null && (hovered || getSelected() == this || getFocused() == this);
        }
        private void positionActions() {
            removeButton.setX(getContentX() + getContentWidth() - 20);
            resetButton.setX(removeButton.getX() - 20);
            resetButton.setY(getContentY() + 1);
            removeButton.setY(getContentY() + 1);
        }
        @Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            boolean actions = actionsVisible(hovered);
            graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(choice.label().getString(),
                    Math.max(1, getContentWidth() - (actions ? 44 : 4))), getContentX() + 2, getContentY() + 5, 0xFFFFFFFF);
            boolean overAction = false;
            if (actions) {
                positionActions();
                resetButton.extractRenderState(graphics, mouseX, mouseY, delta);
                removeButton.extractRenderState(graphics, mouseX, mouseY, delta);
                overAction = resetButton.isMouseOver(mouseX, mouseY) || removeButton.isMouseOver(mouseX, mouseY);
            }
            if (hovered && !overAction) {
                var lines = new ArrayList<net.minecraft.util.FormattedCharSequence>();
                lines.addAll(minecraft.font.split(choice.label(), 280));
                if (!choice.detail().getString().isEmpty()) lines.addAll(minecraft.font.split(choice.detail(), 280));
                graphics.setTooltipForNextFrame(minecraft.font, lines, mouseX, mouseY);
            }
        }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != 0) return false;
            if (actionsVisible(true)) {
                positionActions();
                if (resetButton.mouseClicked(event, doubleClick) || removeButton.mouseClicked(event, doubleClick)) return true;
            }
            activate();
            return true;
        }
        @Override public boolean keyPressed(KeyEvent event) {
            if (reset != null && event.key() == 82) { reset.accept(choice.id()); return true; }
            if (remove != null && event.key() == 261) { remove.accept(choice.id()); return true; }
            if (event.key() == 257 || event.key() == 335 || event.key() == 32) {
                activate();
                return true;
            }
            return false;
        }
        private void activate() { setSelected(this); selected.accept(choice.id()); }
    }
}

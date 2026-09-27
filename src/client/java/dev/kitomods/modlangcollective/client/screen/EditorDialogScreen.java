package dev.kitomods.modlangcollective.client.screen;

import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Explicit choices for destructive and draft-sensitive actions. Escape always cancels. */
final class EditorDialogScreen extends EditorBaseScreen {
    record Action(Component label, Runnable run) { }
    private final Component message;
    private final List<Action> actions;

    EditorDialogScreen(Screen parent, Component title, Component message, List<Action> actions) {
        super(parent, title);
        this.message = message;
        this.actions = List.copyOf(actions);
    }

    @Override
    protected void init() {
        if (!minimumSize(320, 220)) return;
        int contentWidth = Math.min(width - 32, 560);
        int left = (width - contentWidth) / 2;
        boolean twoColumns = width >= 440;
        int columns = twoColumns ? 2 : 1;
        int rows = Math.max(1, (actions.size() + columns - 1) / columns);
        int buttonHeight = 20;
        int rowStep = 24;
        int maxMessageHeight = Math.max(24, height - 38 - rows * rowStep - 26);
        int measuredHeight = Math.max(24, font.split(message, Math.max(1, contentWidth - 16)).size() * 9 + 12);
        int messageHeight = Math.min(maxMessageHeight, measuredHeight);
        int blockHeight = messageHeight + 14 + rows * rowStep;
        int messageTop = Math.max(38, (height - blockHeight) / 2);
        int buttonsTop = messageTop + messageHeight + 14;
        var text = new FittingMultiLineTextWidget(left, messageTop, contentWidth, messageHeight, message, font) {
            @Override protected void extractBackground(GuiGraphicsExtractor graphics) { }
            @Override protected void extractBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height) { }
        };
        text.minimizeHeight();
        addRenderableWidget(text);
        int gap = 8;
        int buttonWidth = columns == 1 ? contentWidth : (contentWidth - gap) / 2;
        for (int i = 0; i < actions.size(); i++) {
            Action action = actions.get(i);
            int column = twoColumns ? i % 2 : 0;
            int row = twoColumns ? i / 2 : i;
            int x = twoColumns && i == actions.size() - 1 && actions.size() % 2 == 1
                    ? (width - buttonWidth) / 2 : left + column * (buttonWidth + gap);
            button(action.label(), x, buttonsTop + row * rowStep, buttonWidth, action.run());
        }
    }
}

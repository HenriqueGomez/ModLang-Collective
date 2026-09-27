package dev.kitomods.modlangcollective.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import static dev.kitomods.modlangcollective.client.screen.EditorScreens.text;

abstract class EditorBaseScreen extends Screen {
    protected final Screen parent;
    protected Component status = Component.empty();
    protected boolean compact;
    protected boolean showStatusLine = true;

    EditorBaseScreen(Screen parent, Component title) {
        super(title);
        this.parent = parent;
    }

    protected boolean minimumSize(int minimumWidth, int minimumHeight) {
        compact = width < minimumWidth || height < minimumHeight;
        if (compact) {
            int w = Math.max(60, Math.min(width - 16, 320));
            addRenderableWidget(new FittingMultiLineTextWidget((width - w) / 2, 38, w,
                    Math.max(24, height - 90), text("small_window", "Enlarge the window or reduce GUI Scale to use this screen (minimum %s x %s GUI pixels). Your draft is retained.", minimumWidth, minimumHeight), font));
            button(text("back", "Back"), Math.max(4, (width - 100) / 2), Math.max(8, height - 28), 100, this::onClose);
        }
        return !compact;
    }

    protected Button button(Component label, int x, int y, int w, Runnable action) {
        Button button = Button.builder(label, ignored -> action.run()).bounds(x, y, w, 20).build();
        button.setTooltip(Tooltip.create(label));
        return addRenderableWidget(button);
    }

    protected FittingMultiLineTextWidget readOnly(Component label, String value, int x, int y, int w, int maxHeight) {
        int contentHeight = Math.max(24, font.split(Component.literal(value), Math.max(1, w - 16)).size() * 9 + 12);
        int h = Math.min(Math.max(24, maxHeight), contentHeight);
        var viewer = new FittingMultiLineTextWidget(x, y, w, h, Component.literal(value), font);
        viewer.minimizeHeight();
        viewer.setTooltip(Tooltip.create(label));
        return addRenderableWidget(viewer);
    }

    protected void refresh() { rebuildWidgets(); }

    @Override
    public void onClose() { minecraft.gui.setScreen(parent); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        if (!compact && showStatusLine) graphics.text(font, font.plainSubstrByWidth(status.getString(), Math.max(1, width - 20)), 10, height - 45, 0xFFFFDD88);
    }
}

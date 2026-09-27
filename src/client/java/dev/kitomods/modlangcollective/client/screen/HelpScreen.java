package dev.kitomods.modlangcollective.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import java.util.function.Consumer;
import static dev.kitomods.modlangcollective.client.screen.EditorScreens.text;

/** Short editing guidance, kept separate from confirmation dialogs. */
final class HelpScreen extends EditorBaseScreen {
    private final Consumer<Screen> details;
    private final Runnable retry;

    HelpScreen(Screen parent, Consumer<Screen> details, Runnable retry) {
        super(parent, text("help", "Help"));
        this.details = details;
        this.retry = retry;
        showStatusLine = false;
    }

    @Override protected void init() {
        if (!minimumSize(320, 220)) return;
        int contentWidth = Math.min(480, width - 32);
        int left = (width - contentWidth) / 2;
        var guidance = text("help_steps", "1. Choose a language\nUse Add language to start. Original shows the source texts without changing them.\n\n2. Translate and confirm\nChoose a text, type your translation, then use Confirm to save it. Save can confirm all texts you have edited; untouched texts stay untranslated.\n\n3. Use your translations\nSelect the same language in Minecraft. Reopen a mod screen if it still shows the old text.\n\nManage a language\nHover over its name to reset or remove it. Discard changes restores its saved text. These actions ask before changing your work.");
        int reserved = retry == null ? 76 : 100;
        int measured = Math.max(24, font.split(guidance, contentWidth - 16).size() * 9 + 12);
        int messageHeight = Math.min(measured, height - reserved);
        var viewer = new FittingMultiLineTextWidget(left, 38, contentWidth, messageHeight, guidance, font) {
            @Override protected void extractBackground(GuiGraphicsExtractor graphics) { }
            @Override protected void extractBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height) { }
        };
        viewer.minimizeHeight();
        addRenderableWidget(viewer);
        int buttonsY = 38 + viewer.getHeight() + 10;
        int buttonWidth = (contentWidth - 8) / 2;
        button(text("runtime_details", "Technical details"), left, buttonsY, buttonWidth, () -> details.accept(this));
        button(text("back", "Back"), left + buttonWidth + 8, buttonsY, buttonWidth, this::onClose);
        if (retry != null) button(text("retry_apply", "Retry applying translations"), left, buttonsY + 24, contentWidth, retry);
    }
}

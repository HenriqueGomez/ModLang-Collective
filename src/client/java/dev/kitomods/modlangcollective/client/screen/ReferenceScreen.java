package dev.kitomods.modlangcollective.client.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import static dev.kitomods.modlangcollective.client.screen.EditorScreens.text;

final class ReferenceScreen extends EditorBaseScreen {
    private final String value;

    ReferenceScreen(Screen parent, Component title, String value) {
        super(parent, title);
        this.value = value;
    }

    @Override
    protected void init() {
        if (!minimumSize(320, 220)) return;
        int contentWidth = Math.min(560, width - 32);
        var viewer = readOnly(text("read_only", "Read-only text"), value, (width - contentWidth) / 2, 38, contentWidth, height - 100);
        int buttonsY = Math.min(38 + viewer.getHeight() + 12, height - 28);
        button(text("copy", "Copy text"), width / 2 - 104, buttonsY, 100, () -> minecraft.keyboardHandler.setClipboard(value));
        button(text("back", "Back"), width / 2 + 4, buttonsY, 100, this::onClose);
    }
}

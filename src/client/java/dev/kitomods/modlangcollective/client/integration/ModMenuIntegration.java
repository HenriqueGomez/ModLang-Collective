package dev.kitomods.modlangcollective.client.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.kitomods.modlangcollective.client.screen.EditorScreens;

/** Official Mod Menu entry point remains available even when its internal screen hook is disabled. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> EditorScreens.create(parent, null);
    }
}

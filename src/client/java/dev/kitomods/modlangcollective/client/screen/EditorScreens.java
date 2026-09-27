package dev.kitomods.modlangcollective.client.screen;

import dev.kitomods.modlangcollective.client.ModLangCollectiveClient;
import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.project.ProjectStore;
import dev.kitomods.modlangcollective.editor.TranslationAvailability;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Editor entry points for the optional Mod Menu integration. */
public final class EditorScreens {
    private EditorScreens() { }

    public static void open(Screen parent, String modId) {
        Minecraft.getInstance().gui.setScreen(create(parent, modId));
    }

    public static Screen create(Screen parent, String modId) {
        List<DiscoveryCatalog> catalogs = ModLangCollectiveClient.catalogs();
        var store = new ProjectStore(FabricLoader.getInstance().getGameDir().resolve("mods/modlangcollective"));
        if (modId != null) {
            var match = catalogs.stream().filter(catalog -> catalog.modId().equals(modId)).findFirst();
            if (match.isPresent() && TranslationAvailability.assess(match.get()) == TranslationAvailability.Status.AVAILABLE)
                return new TranslationEditorScreen(parent, store, match.get());
            return new ReferenceScreen(parent, text("unavailable", "Translation unavailable"), availabilityMessage(modId).getString());
        }
        return new ChoiceScreen(parent, text("mods", "Choose a mod"),
                catalogs.stream().filter(catalog -> TranslationAvailability.assess(catalog) == TranslationAvailability.Status.AVAILABLE).map(catalog -> new ChoiceScreen.Choice(catalog.modId(),
                        modName(catalog.modId()), Component.empty()))
                        .toList(), id -> {
                    var catalog = catalogs.stream().filter(item -> item.modId().equals(id)).findFirst().orElseThrow();
                    Minecraft.getInstance().gui.setScreen(new TranslationEditorScreen(create(parent, null), store, catalog));
                });
    }

    public static TranslationAvailability.Status availability(String modId) {
        return TranslationAvailability.assess(ModLangCollectiveClient.catalogs().stream()
                .filter(catalog -> catalog.modId().equals(modId)).findFirst().orElse(null));
    }

    public static Component availabilityMessage(String modId) {
        return switch (availability(modId)) {
            case AVAILABLE -> text("translate_available", "Translate this mod's language-file texts. Text written directly in code is not supported.");
            case NO_TEXTS -> text("translate_no_texts", "No supported translatable texts were found. Text written directly in the mod's code (hardcoded text) cannot be translated by this editor.");
            case INVALID_RESOURCES -> text("translate_invalid", "Translation is unavailable because this mod's language files could not be read or validated safely.");
            case NOT_SCANNED -> text("translate_not_scanned", "No language scan is available for this entry.");
        };
    }

    static Component text(String key, String fallback, Object... arguments) {
        return Component.translatableWithFallback("modlangcollective.editor." + key, fallback, arguments);
    }

    static Component language(String locale) {
        var info = Minecraft.getInstance().getLanguageManager().getLanguage(locale);
        return info == null ? Component.literal(locale)
                : info.toComponent();
    }

    static Component modName(String modId) {
        return Component.literal(FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getName()).orElse(modId));
    }
}

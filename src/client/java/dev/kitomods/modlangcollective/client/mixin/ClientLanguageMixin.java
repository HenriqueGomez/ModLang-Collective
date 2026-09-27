package dev.kitomods.modlangcollective.client.mixin;

import dev.kitomods.modlangcollective.client.runtime.LanguageOverlayAccess;
import dev.kitomods.modlangcollective.client.runtime.RuntimeLanguageBridge;
import java.util.List;
import java.util.Map;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLanguage.class)
abstract class ClientLanguageMixin implements LanguageOverlayAccess {
    @Shadow @Final @Mutable private Map<String, String> storage;

    @Inject(method = "loadFrom", at = @At("RETURN"))
    private static void modlangcollective$decorate(ResourceManager resources, List<String> languages,
                                                   boolean rightToLeft, CallbackInfoReturnable<ClientLanguage> callback) {
        ((LanguageOverlayAccess) callback.getReturnValue()).modlangcollective$applySavedTranslations(languages);
    }

    @Override public void modlangcollective$applySavedTranslations(List<String> languages) {
        storage = RuntimeLanguageBridge.overlay(storage, languages);
    }
}

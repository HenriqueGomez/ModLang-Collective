package dev.kitomods.modlangcollective.client.mixin;

import com.terraformersmc.modmenu.gui.ModsScreen;
import dev.kitomods.modlangcollective.editor.TranslationAvailability;
import dev.kitomods.modlangcollective.client.screen.EditorScreens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional, version-gated hook; does not replace any mod's configuration factory. */
@Pseudo
@Mixin(targets = "com.terraformersmc.modmenu.gui.ModsScreen", remap = false)
abstract class ModMenuScreenMixin extends Screen {
    @Unique private Button modlangcollective$translate;
    @Unique private Component modlangcollective$reason = Component.empty();
    @Shadow private int rightPaneX;

    protected ModMenuScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void modlangcollective$addTranslate(CallbackInfo callback) {
        modlangcollective$translate = Button.builder(Component.literal("T"), ignored -> {
            var selected = ((ModsScreen) (Object) this).getSelectedEntry();
            if (selected != null) EditorScreens.open((Screen) (Object) this, selected.getMod().getId());
        }).bounds(width - 48, 48, 20, 20)
                .createNarration(ignored -> modlangcollective$reason.copy()).build();
        modlangcollective$translate.setTooltip(Tooltip.create(Component.translatable("modlangcollective.translate.tooltip")));
        addRenderableWidget(modlangcollective$translate);
        modlangcollective$refresh();
    }

    @Inject(method = "updateSelectedEntry", at = @At("TAIL"), remap = false)
    private void modlangcollective$selectionChanged(CallbackInfo callback) {
        modlangcollective$refresh();
    }

    // Reserve a header column for Translate; native configuration keeps its original position.
    @ModifyVariable(method = "extractRenderState", at = @At("STORE"), name = "maxNameWidth", remap = false)
    private int modlangcollective$reserveNameSpace(int original) {
        return Math.max(12, original - 52);
    }

    @ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lcom/terraformersmc/modmenu/util/mod/ModBadgeRenderer;<init>(IIILcom/terraformersmc/modmenu/util/mod/Mod;Lcom/terraformersmc/modmenu/gui/ModsScreen;)V"),
            index = 2, remap = false)
    private int modlangcollective$reserveBadgeSpace(int original) {
        return original - 24;
    }

    @ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"),
            index = 1, remap = false)
    private String modlangcollective$trimHeaderVersion(String version) {
        return font.plainSubstrByWidth(version, Math.max(12, width - rightPaneX - 36 - 52));
    }

    @Unique private void modlangcollective$refresh() {
        if (modlangcollective$translate == null) return;
        var selected = ((ModsScreen) (Object) this).getSelectedEntry();
        String modId = selected == null ? "" : selected.getMod().getId();
        modlangcollective$translate.active = selected != null
                && EditorScreens.availability(modId) == TranslationAvailability.Status.AVAILABLE;
        modlangcollective$reason = EditorScreens.availabilityMessage(modId);
        modlangcollective$translate.setTooltip(Tooltip.create(modlangcollective$reason));
    }
}

package dev.kitomods.modlangcollective.client.mixin;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** The internal Mod Menu screen hook is enabled only for the inspected version. */
public final class OptionalIntegrationPlugin implements IMixinConfigPlugin {
    public static boolean supportsVersion(String version) {
        return Set.of("20.0.2", "20.0.3").contains(version);
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.endsWith(".ModMenuScreenMixin")) return true;
        return FabricLoader.getInstance().getModContainer("modmenu").map(mod -> {
            String version = mod.getMetadata().getVersion().getFriendlyString();
            boolean supported = supportsVersion(version);
            if (!supported) org.slf4j.LoggerFactory.getLogger("modlangcollective").warn(
                    "Mod Menu {} has not been verified for the selected-mod button. Open ModLang Collective's configuration in Mod Menu to access the editor.", version);
            return supported;
        }).orElse(false);
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) { }
}

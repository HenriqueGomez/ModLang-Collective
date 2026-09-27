package dev.kitomods.modlangcollective.tools;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;

/** Checks compiled hook contracts without starting Minecraft or opening a game directory. */
public final class ClientIntegrationAudit {
    private ClientIntegrationAudit() { }

    public static void main(String[] args) throws Exception {
        requireMethod("net.minecraft.client.resources.language.ClientLanguage", "loadFrom",
                "(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Z)Lnet/minecraft/client/resources/language/ClientLanguage;");
        if (requireClass("net.minecraft.client.resources.language.ClientLanguage").fields.stream()
                .noneMatch(field -> field.name.equals("storage") && field.desc.equals("Ljava/util/Map;"))) {
            throw new IllegalStateException("Missing client language storage field.");
        }
        requireMethod("net.minecraft.client.resources.language.LanguageManager", "onResourceManagerReload",
                "(Lnet/minecraft/server/packs/resources/ResourceManager;)V");
        requireMethod("dev.kitomods.modlangcollective.client.integration.ModMenuIntegration", "getModConfigScreenFactory",
                "()Lcom/terraformersmc/modmenu/api/ConfigScreenFactory;");
        requireMethod("com.terraformersmc.modmenu.gui.ModsScreen", "init", "()V");
        requireMethod("com.terraformersmc.modmenu.gui.ModsScreen", "updateSelectedEntry",
                "(Lcom/terraformersmc/modmenu/gui/widget/entries/ModListEntry;)V");
        requireMethod("com.terraformersmc.modmenu.gui.ModsScreen", "getSelectedEntry",
                "()Lcom/terraformersmc/modmenu/gui/widget/entries/ModListEntry;");
        var modMenu = requireClass("com.terraformersmc.modmenu.gui.ModsScreen");
        var render = modMenu.methods.stream().filter(method -> method.name.equals("extractRenderState")).findFirst().orElseThrow();
        if (render.localVariables.stream().noneMatch(local -> local.name.equals("maxNameWidth") && local.desc.equals("I"))) {
            throw new IllegalStateException("Missing Mod Menu header-width local variable.");
        }
        requireInvocation(render.instructions.toArray(), "com/terraformersmc/modmenu/util/mod/ModBadgeRenderer", "<init>",
                "(IIILcom/terraformersmc/modmenu/util/mod/Mod;Lcom/terraformersmc/modmenu/gui/ModsScreen;)V");
        requireInvocation(render.instructions.toArray(), "net/minecraft/client/gui/GuiGraphicsExtractor", "text",
                "(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V");
        try (var input = ClientIntegrationAudit.class.getClassLoader()
                .getResourceAsStream("modlangcollective.client.mixins.json")) {
            if (input == null) throw new IllegalStateException("Missing mixin configuration.");
            var json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            MixinEnvironment.CompatibilityLevel.valueOf(json.get("compatibilityLevel").getAsString());
            String pluginName = json.get("plugin").getAsString();
            // This standalone process has no loaded Fabric mods, even with compile-only JARs on its classpath.
            var plugin = Class.forName(pluginName).getConstructor().newInstance();
            var applies = plugin.getClass().getMethod("shouldApplyMixin", String.class, String.class);
            String prefix = json.get("package").getAsString() + ".";
            if ((boolean) applies.invoke(plugin, "com.terraformersmc.modmenu.gui.ModsScreen", prefix + "ModMenuScreenMixin")) {
                throw new IllegalStateException("Optional Mod Menu hook enabled without a loaded mod.");
            }
            if (!(boolean) applies.invoke(plugin, "net.minecraft.client.resources.language.ClientLanguage", prefix + "ClientLanguageMixin")) {
                throw new IllegalStateException("Language application was disabled without Mod Menu.");
            }
            var supported = plugin.getClass().getMethod("supportsVersion", String.class);
            if (!(boolean) supported.invoke(null, "20.0.2") || !(boolean) supported.invoke(null, "20.0.3")) {
                throw new IllegalStateException("An inspected Mod Menu version was disabled.");
            }
            for (var mixin : json.getAsJsonArray("client")) {
                if (mixin.getAsString().equals("MenuAccessMixin")) throw new IllegalStateException("Unwanted title/pause menu access remains enabled.");
                requireClass(prefix + mixin.getAsString());
            }
        }
        var normalized = new java.util.HashMap<String, String>();
        net.minecraft.locale.Language.loadFromJson(new java.io.ByteArrayInputStream(
                "{\"fixture\":\"%1$d itens, %2$.2f\",\"empty\":\"\"}".getBytes(StandardCharsets.UTF_8)), normalized::put);
        if (!"%1$s itens, %2$s".equals(normalized.get("fixture")) || !"".equals(normalized.get("empty"))) {
            throw new IllegalStateException("Vanilla numeric-placeholder normalization changed.");
        }
        System.out.println("Client hook symbols, mixin metadata, and absent-Mod-Menu gate passed. No game was launched.");
    }

    private static ClassNode requireClass(String name) throws Exception {
        try (var input = ClientIntegrationAudit.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (input == null) throw new IllegalStateException("Missing integration class: " + name);
            var node = new ClassNode(Opcodes.ASM9);
            new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static void requireInvocation(org.objectweb.asm.tree.AbstractInsnNode[] instructions,
                                          String owner, String name, String descriptor) {
        long count = java.util.Arrays.stream(instructions).filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)).count();
        if (count != 1) throw new IllegalStateException("Expected one header hook invocation: " + owner + "." + name);
    }

    private static void requireMethod(String owner, String name, String descriptor) throws Exception {
        if (requireClass(owner).methods.stream().noneMatch(method -> method.name.equals(name) && method.desc.equals(descriptor))) {
            throw new IllegalStateException("Missing integration method: " + owner + "." + name + descriptor);
        }
    }
}

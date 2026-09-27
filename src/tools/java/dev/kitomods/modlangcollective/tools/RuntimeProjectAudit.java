package dev.kitomods.modlangcollective.tools;

import com.google.gson.JsonParser;
import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.discovery.LanguageDiscovery;
import dev.kitomods.modlangcollective.discovery.ModSource;
import dev.kitomods.modlangcollective.project.ProjectStore;
import dev.kitomods.modlangcollective.project.TranslationProject;
import dev.kitomods.modlangcollective.runtime.RuntimeTranslations;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only check of an existing local project against top-level mod JARs, without game startup. */
public final class RuntimeProjectAudit {
    private RuntimeProjectAudit() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected mods directory, mod ID, and locale.");
        Path mods = Path.of(args[0]);
        var catalogs = new ArrayList<DiscoveryCatalog>();
        List<Path> jars;
        try (var files = Files.list(mods)) {
            jars = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
        for (Path jar : jars) {
            try (var zip = FileSystems.newFileSystem(jar, Map.of())) {
                Path metadata = zip.getPath("/fabric.mod.json");
                if (!Files.exists(metadata)) continue;
                byte[] bytes;
                try (var input = Files.newInputStream(metadata)) { bytes = input.readNBytes(1_048_577); }
                if (bytes.length > 1_048_576) throw new IllegalArgumentException("Mod metadata exceeds the audit limit.");
                var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                catalogs.add(new LanguageDiscovery().scan(new ModSource(json.get("id").getAsString(),
                        json.get("version").getAsString(), List.of(zip.getPath("/")),
                        json.has("name") ? json.get("name").getAsString() : json.get("id").getAsString())));
            }
        }
        var store = new ProjectStore(mods.resolve("modlangcollective"));
        var saved = store.load(args[1], args[2]).orElseThrow();
        var snapshot = RuntimeTranslations.load(store, catalogs, args[2]);
        int accepted = 0;
        int matching = 0;
        for (var namespace : saved.project().namespaces().values()) {
            for (var entry : namespace.entries().entrySet()) {
                if (entry.getValue().state() != TranslationProject.State.TRANSLATED) continue;
                accepted++;
                if (entry.getValue().translation().equals(snapshot.overrides().get(entry.getKey()))) matching++;
            }
        }
        if (!saved.revision().equals(store.load(args[1], args[2]).orElseThrow().revision())) {
            throw new IllegalStateException("Project changed during the read-only audit.");
        }
        System.out.printf("Runtime projection audit: %d top-level mod catalogs, %d accepted target entries, %d exact projected matches, %d findings. Project unchanged.%n",
                catalogs.size(), accepted, matching, snapshot.issues().size());
        snapshot.issues().forEach(issue -> System.out.printf("%s: %s / %s / %s%n", issue.code(), issue.modId(), issue.namespace(), issue.key()));
        if (accepted == 0 || matching != accepted) throw new IllegalStateException("Some accepted target entries are not eligible for application.");
    }
}

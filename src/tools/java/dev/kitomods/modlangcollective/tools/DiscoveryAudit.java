package dev.kitomods.modlangcollective.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.kitomods.modlangcollective.discovery.LanguageDiscovery;
import dev.kitomods.modlangcollective.discovery.ModSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline evidence from top-level JARs; does not emulate Fabric's nested-mod resolution. */
public final class DiscoveryAudit {
    private DiscoveryAudit() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 4 || !args[0].equals("--mods") || !args[2].equals("--report")) {
            throw new IllegalArgumentException("Usage: --mods <directory> --report <json-file>");
        }
        Path mods = Path.of(args[1]).toRealPath();
        Path report = Path.of(args[3]).toAbsolutePath().normalize();
        Path ancestor = report;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        Path resolvedReport = ancestor.toRealPath().resolve(ancestor.relativize(report)).normalize();
        if (!Files.isDirectory(mods) || resolvedReport.startsWith(mods)) {
            throw new IllegalArgumentException("Use an existing mods directory and a report outside that directory.");
        }
        var results = new ArrayList<Map<String, Object>>();
        var discovery = new LanguageDiscovery();
        List<Path> jars;
        try (var files = Files.list(mods)) {
            jars = files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
        for (Path jar : jars) {
            var item = new LinkedHashMap<String, Object>();
            item.put("jar", jar.getFileName().toString());
            try (var zip = FileSystems.newFileSystem(jar, Map.of())) {
                Path metadata = zip.getPath("/fabric.mod.json");
                if (!Files.isRegularFile(metadata)) {
                    item.put("status", "NO_FABRIC_METADATA");
                } else {
                    byte[] bytes;
                    try (var input = Files.newInputStream(metadata)) {
                        bytes = input.readNBytes(1_048_577);
                    }
                    if (bytes.length > 1_048_576) throw new IOException("Metadata size limit");
                    // Metadata parsing is separate from the strict language-resource parser.
                    var json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    String id = json.get("id").getAsString();
                    String version = json.get("version").getAsString();
                    var catalog = discovery.scan(new ModSource(id, version, List.of(zip.getPath("/")), json.has("name") ? json.get("name").getAsString() : id));
                    item.put("status", "SCANNED");
                    item.put("modId", id);
                    item.put("modVersion", version);
                    item.put("counts", catalog.counts());
                    item.put("translationAvailability", dev.kitomods.modlangcollective.editor.TranslationAvailability.assess(catalog));
                    item.put("issues", catalog.issues());
                    item.put("namespaces", catalog.namespaces().stream().map(namespace -> {
                        var detail = new LinkedHashMap<String, Object>();
                        detail.put("namespace", namespace.namespace());
                        detail.put("sourceState", namespace.source().state());
                        detail.put("sourceLocale", namespace.source().locale().orElse(null));
                        detail.put("sourceUsable", namespace.source().usable());
                        detail.put("resources", namespace.resources().stream().map(resource -> Map.of(
                                "locale", resource.locale(), "path", resource.resourcePath(),
                                "status", resource.status(), "entryCount", resource.entries().size())).toList());
                        return detail;
                    }).toList());
                }
            } catch (IOException | RuntimeException exception) {
                item.put("status", "AUDIT_FAILED");
                item.put("errorType", exception.getClass().getSimpleName());
            }
            results.add(item);
        }
        var output = Map.of("scope", "Top-level JARs only; nested-mod resolution requires the Fabric runtime.",
                "jarCount", jars.size(), "mods", results);
        Files.createDirectories(report.getParent());
        // Replace the directory entry rather than following an existing file link.
        Path temporary = Files.createTempFile(report.getParent(), ".discovery-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(output) + "\n");
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        long scanned = results.stream().filter(r -> "SCANNED".equals(r.get("status"))).count();
        System.out.printf("Offline discovery: %d JARs, %d scanned, %d skipped or failed.%n",
                jars.size(), scanned, jars.size() - scanned);
    }
}

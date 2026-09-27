package dev.kitomods.modlangcollective.discovery;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static dev.kitomods.modlangcollective.discovery.DiscoveryCatalog.*;

/** Read-only, bounded discovery of canonical language files. Does not open nested archives. */
public final class LanguageDiscovery {
    public static final int DEFAULT_MAX_FILE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_DIRECTORY_ENTRIES = 16_384;
    public static final int MAX_RESOURCES = 16_384;
    public static final int MAX_TRANSLATION_ENTRIES = 100_000;
    public static final int MAX_ROOTS = 1_024;
    public static final int MAX_TOTAL_FILE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_ISSUES = 4_096;
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern LOCALE_FILE = Pattern.compile("[a-z0-9_-]+\\.json");
    private final int maxFileBytes;

    public LanguageDiscovery() {
        this(DEFAULT_MAX_FILE_BYTES);
    }

    public LanguageDiscovery(int maxFileBytes) {
        if (maxFileBytes < 1 || maxFileBytes > DEFAULT_MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File limit must be between 1 and " + DEFAULT_MAX_FILE_BYTES);
        }
        this.maxFileBytes = maxFileBytes;
    }

    public DiscoveryCatalog scan(ModSource source) {
        var issues = new ArrayList<DiscoveryIssue>();
        var resources = new TreeMap<String, List<LanguageResource>>();
        var seenRoots = new HashSet<Path>();
        int resourceCount = 0;
        var budget = new ReadBudget();
        if (source.roots().size() > MAX_ROOTS) {
            issue(issues, Severity.ERROR, "ROOT_LIMIT", -1, "", "Root count exceeds the discovery limit.");
            return catalog(source, resources, issues);
        }
        for (int rootIndex = 0; rootIndex < source.roots().size(); rootIndex++) {
            Path root = source.roots().get(rootIndex).toAbsolutePath().normalize();
            if (!seenRoots.add(root)) {
                issue(issues, Severity.WARNING, "DUPLICATE_ROOT", rootIndex, "", "Repeated root was skipped.");
                continue;
            }
            if (hasSymlinkAncestor(root)) {
                issue(issues, Severity.WARNING, "SYMLINK_SKIPPED", rootIndex, "", "Symbolic-link root was skipped.");
                continue;
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                issue(issues, Severity.ERROR, "UNREADABLE_ROOT", rootIndex, "", "Resource root is not an accessible directory.");
                continue;
            }
            Path assets = root.resolve("assets");
            if (!directory(assets, rootIndex, "assets", issues)) continue;
            for (Path namespacePath : children(assets, rootIndex, "assets", issues)) {
                String namespace = namespacePath.getFileName().toString();
                if (!NAMESPACE.matcher(namespace).matches()) continue;
                String namespaceResource = "assets/" + namespace;
                if (!directory(namespacePath, rootIndex, namespaceResource, issues)) continue;
                Path lang = namespacePath.resolve("lang");
                if (!directory(lang, rootIndex, namespaceResource + "/lang", issues)) continue;
                for (Path file : children(lang, rootIndex, namespaceResource + "/lang", issues)) {
                    String name = file.getFileName().toString();
                    if (!LOCALE_FILE.matcher(name).matches()) continue;
                    String resourcePath = namespaceResource + "/lang/" + name;
                    if (Files.isSymbolicLink(file)) {
                        issue(issues, Severity.WARNING, "SYMLINK_SKIPPED", rootIndex, resourcePath,
                                "Symbolic-link resource was skipped.");
                        continue;
                    }
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (resourceCount == MAX_RESOURCES) {
                        issue(issues, Severity.ERROR, "RESOURCE_LIMIT", rootIndex, "",
                                "Resource count exceeds the discovery limit; remaining resources were not scanned.");
                        return catalog(source, resources, issues);
                    }
                    resourceCount++;
                    String locale = name.substring(0, name.length() - 5);
                    resources.computeIfAbsent(namespace, ignored -> new ArrayList<>())
                            .add(read(file, rootIndex, resourcePath, locale, issues, budget));
                    if (budget.exhausted) return catalog(source, resources, issues);
                }
            }
        }
        return catalog(source, resources, issues);
    }

    private static boolean hasSymlinkAncestor(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    private static boolean directory(Path path, int rootIndex, String resourcePath,
                                     List<DiscoveryIssue> issues) {
        if (Files.isSymbolicLink(path)) {
            issue(issues, Severity.WARNING, "SYMLINK_SKIPPED", rootIndex, resourcePath,
                    "Symbolic-link directory was skipped.");
            return false;
        }
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static List<Path> children(Path directory, int rootIndex, String resourcePath,
                                       List<DiscoveryIssue> issues) {
        var children = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (children.size() == MAX_DIRECTORY_ENTRIES) {
                    issue(issues, Severity.ERROR, "DIRECTORY_LIMIT", rootIndex, resourcePath,
                            "Directory exceeds the entry limit; its contents were not scanned.");
                    return List.of();
                }
                children.add(child);
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException | SecurityException exception) {
            issue(issues, Severity.ERROR, "DIRECTORY_READ_FAILED", rootIndex, resourcePath,
                    "Directory could not be read.");
            return List.of();
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return children;
    }

    private LanguageResource read(Path file, int rootIndex, String resourcePath, String locale,
                                  List<DiscoveryIssue> issues, ReadBudget budget) {
        byte[] bytes;
        try {
            if (Files.size(file) > maxFileBytes) return tooLarge(rootIndex, resourcePath, locale, issues);
            // JDK ZIP filesystems do not resolve symbolic links and reject NOFOLLOW_LINKS.
            // Other providers must support the no-follow open rather than silently weakening it.
            try (var stream = file.getFileSystem().provider().getScheme().equals("jar")
                    ? Files.newInputStream(file)
                    : Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(maxFileBytes + 1);
            }
            if (bytes.length > maxFileBytes) return tooLarge(rootIndex, resourcePath, locale, issues);
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            issue(issues, Severity.ERROR, "RESOURCE_READ_FAILED", rootIndex, resourcePath,
                    "Language resource could not be read.");
            return new LanguageResource(rootIndex, resourcePath, locale, Map.of(), ResourceStatus.IO_ERROR);
        }
        budget.bytes += bytes.length;
        if (budget.bytes > MAX_TOTAL_FILE_BYTES) {
            budget.exhausted = true;
            issue(issues, Severity.ERROR, "SCAN_BYTE_LIMIT", rootIndex, resourcePath,
                    "Total language bytes exceed the scan limit; remaining resources were not scanned.");
            return new LanguageResource(rootIndex, resourcePath, locale, Map.of(), ResourceStatus.LIMIT_EXCEEDED);
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            issue(issues, Severity.ERROR, "INVALID_UTF8", rootIndex, resourcePath,
                    "Language resource is not valid UTF-8.");
            return new LanguageResource(rootIndex, resourcePath, locale, Map.of(), ResourceStatus.INVALID);
        }
        var entries = new TreeMap<String, String>();
        var keys = new HashSet<String>();
        boolean partial = false;
        int entryCount = 0;
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                if (++entryCount > MAX_TRANSLATION_ENTRIES) {
                    issue(issues, Severity.ERROR, "ENTRY_LIMIT", rootIndex, resourcePath,
                            "Language resource exceeds the translation entry limit.");
                    return new LanguageResource(rootIndex, resourcePath, locale, Map.of(), ResourceStatus.INVALID);
                }
                String key = reader.nextName();
                boolean duplicate = !keys.add(key);
                if (duplicate) {
                    entries.remove(key);
                    partial = true;
                    issue(issues, Severity.ERROR, "DUPLICATE_KEY", rootIndex, resourcePath,
                            "Duplicate translation key was excluded.");
                }
                if (reader.peek() == JsonToken.STRING) {
                    String value = reader.nextString();
                    if (!duplicate) entries.put(key, value);
                } else {
                    reader.skipValue();
                    partial = true;
                    issue(issues, Severity.WARNING, "NON_STRING_VALUE", rootIndex, resourcePath,
                            "Non-string translation value was excluded.");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON content");
        } catch (IOException | IllegalStateException exception) {
            issue(issues, Severity.ERROR, "MALFORMED_JSON", rootIndex, resourcePath,
                    "Language resource must contain one strict JSON object.");
            return new LanguageResource(rootIndex, resourcePath, locale, Map.of(), ResourceStatus.INVALID);
        }
        return new LanguageResource(rootIndex, resourcePath, locale, entries,
                partial ? ResourceStatus.PARTIAL : ResourceStatus.VALID);
    }

    private static LanguageResource tooLarge(int rootIndex, String path, String locale,
                                             List<DiscoveryIssue> issues) {
        issue(issues, Severity.ERROR, "FILE_TOO_LARGE", rootIndex, path,
                "Language resource exceeds the file size limit.");
        return new LanguageResource(rootIndex, path, locale, Map.of(), ResourceStatus.TOO_LARGE);
    }

    private static DiscoveryCatalog catalog(ModSource source, Map<String, List<LanguageResource>> resources,
                                             List<DiscoveryIssue> issues) {
        var namespaces = new ArrayList<NamespaceCatalog>();
        int resourceCount = 0;
        int entryCount = 0;
        boolean incomplete = issues.stream().anyMatch(issue -> switch (issue.code()) {
            case "ROOT_LIMIT", "RESOURCE_LIMIT", "DIRECTORY_LIMIT", "SCAN_BYTE_LIMIT",
                    "DIRECTORY_READ_FAILED", "UNREADABLE_ROOT", "SYMLINK_SKIPPED", "ISSUE_LIMIT" -> true;
            default -> false;
        });
        for (var namespace : resources.entrySet()) {
            var files = namespace.getValue();
            files.sort(Comparator.comparing(LanguageResource::locale).thenComparingInt(LanguageResource::rootIndex));
            var locales = new TreeMap<String, List<LanguageResource>>();
            for (LanguageResource file : files) {
                locales.computeIfAbsent(file.locale(), ignored -> new ArrayList<>()).add(file);
                entryCount += file.entries().size();
                resourceCount++;
            }
            for (var locale : locales.entrySet()) {
                if (locale.getValue().size() > 1) {
                    issue(issues, Severity.ERROR, "DUPLICATE_LOCALE", -1,
                            "assets/" + namespace.getKey() + "/lang/" + locale.getKey() + ".json",
                            "Multiple roots provide this locale; runtime precedence is unresolved.");
                }
            }
            SourceState state;
            Optional<String> selected;
            if (locales.containsKey("en_us")) {
                state = SourceState.ENGLISH;
                selected = Optional.of("en_us");
            } else if (locales.size() == 1) {
                state = SourceState.SINGLE_LOCALE_FALLBACK;
                selected = Optional.of(locales.firstKey());
            } else if (locales.isEmpty()) {
                state = SourceState.NONE;
                selected = Optional.empty();
            } else {
                state = SourceState.AMBIGUOUS;
                selected = Optional.empty();
            }
            if (!locales.containsKey("en_us")) {
                issue(issues, Severity.INFO, "MISSING_ENGLISH", -1, "assets/" + namespace.getKey() + "/lang",
                        "English source is absent; a sole locale is a fallback candidate, otherwise selection is required.");
            }
            boolean usable = !incomplete && selected.map(locales::get)
                    .filter(candidates -> candidates.size() == 1)
                    .map(candidates -> candidates.getFirst().status() == ResourceStatus.VALID).orElse(false);
            namespaces.add(new NamespaceCatalog(namespace.getKey(), files, new SourceSelection(state, selected, usable)));
        }
        if (namespaces.size() > 1) {
            issue(issues, Severity.INFO, "MULTIPLE_NAMESPACES", -1, "assets",
                    "The mod contributes language resources to multiple namespaces.");
        }
        issues.sort(Comparator.comparing(DiscoveryIssue::resourcePath).thenComparingInt(DiscoveryIssue::rootIndex)
                .thenComparing(DiscoveryIssue::code).thenComparing(DiscoveryIssue::message));
        return new DiscoveryCatalog(source.id(), source.version(), namespaces, issues,
                new Counts(namespaces.size(), resourceCount, entryCount, issues.size()), source.displayName());
    }

    private static void issue(List<DiscoveryIssue> issues, Severity severity, String code,
                              int rootIndex, String path, String message) {
        if (issues.size() < MAX_ISSUES) {
            issues.add(new DiscoveryIssue(severity, code, rootIndex, path, message));
        } else if (issues.size() == MAX_ISSUES) {
            issues.add(new DiscoveryIssue(Severity.ERROR, "ISSUE_LIMIT", -1, "",
                    "Additional discovery issues were omitted after the reporting limit."));
        }
    }

    private static final class ReadBudget {
        private long bytes;
        private boolean exhausted;
    }
}

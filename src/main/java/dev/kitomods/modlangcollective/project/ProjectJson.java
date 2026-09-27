package dev.kitomods.modlangcollective.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

import static dev.kitomods.modlangcollective.project.TranslationProject.*;

/** Strict bounded codec; reflection-based deserialization would bypass record validation. */
final class ProjectJson {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_NODES = 1_000_000;

    private ProjectJson() { }

    static TranslationProject decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Project exceeds the byte limit.");
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonElement tree;
            try (var reader = new JsonReader(new StringReader(json))) {
                reader.setStrictness(Strictness.STRICT);
                tree = read(reader, 0, new int[1]);
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing project content.");
            }
            JsonObject root = object(tree);
            fields(root, "schemaVersion", "modId", "modVersion", "targetLocale", "namespaces");
            JsonElement version = root.get("schemaVersion");
            if (!version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                    || !Set.of("1", "2").contains(version.getAsString())) throw new IOException("Unsupported project schema version.");
            boolean legacy = version.getAsString().equals("1");
            var namespaces = new TreeMap<String, Namespace>();
            for (var item : object(root.get("namespaces")).entrySet()) {
                JsonObject namespace = object(item.getValue());
                fields(namespace, "sourceLocale", "sourcePath", "entries");
                var entries = new TreeMap<String, Entry>();
                for (var member : object(namespace.get("entries")).entrySet()) {
                    JsonObject entry = object(member.getValue());
                    fields(entry, "sourceText", "sourceHistory", "translation", "bundledTranslation", "state");
                    if (!entry.get("sourceHistory").isJsonArray()) throw new IOException("Source history must be an array.");
                    var history = new ArrayList<String>();
                    for (JsonElement prior : entry.getAsJsonArray("sourceHistory")) history.add(string(prior));
                    String stateName = string(entry.get("state"));
                    String translation = nullable(entry.get("translation"));
                    if (legacy && stateName.equals("PENDING") && translation != null) {
                        throw new IOException("Version-one pending entries cannot have local text.");
                    }
                    if (legacy && stateName.equals("NEEDS_REVIEW")) stateName = "PENDING";
                    entries.put(member.getKey(), new Entry(string(entry.get("sourceText")), history,
                            translation, nullable(entry.get("bundledTranslation")),
                            State.valueOf(stateName)));
                }
                namespaces.put(item.getKey(), new Namespace(string(namespace.get("sourceLocale")),
                        string(namespace.get("sourcePath")), entries));
            }
            return new TranslationProject(SCHEMA_VERSION, string(root.get("modId")), string(root.get("modVersion")),
                    string(root.get("targetLocale")), namespaces);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw new IOException("Invalid translation project: " + exception.getMessage(), exception);
        }
    }

    static byte[] encode(TranslationProject project) throws IOException {
        var output = new BoundedOutput();
        try (var writer = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.setIndent("  ");
            writer.setSerializeNulls(true);
            writer.beginObject();
            writer.name("schemaVersion").value(project.schemaVersion());
            writer.name("modId").value(project.modId());
            writer.name("modVersion").value(project.modVersion());
            writer.name("targetLocale").value(project.targetLocale());
            writer.name("namespaces").beginObject();
            for (var item : project.namespaces().entrySet()) {
                Namespace namespace = item.getValue();
                writer.name(item.getKey()).beginObject();
                writer.name("sourceLocale").value(namespace.sourceLocale());
                writer.name("sourcePath").value(namespace.sourcePath());
                writer.name("entries").beginObject();
                for (var member : namespace.entries().entrySet()) {
                    Entry entry = member.getValue();
                    writer.name(member.getKey()).beginObject();
                    writer.name("sourceText").value(entry.sourceText());
                    writer.name("sourceHistory").beginArray();
                    for (String prior : entry.sourceHistory()) writer.value(prior);
                    writer.endArray();
                    writer.name("translation").value(entry.translation());
                    writer.name("bundledTranslation").value(entry.bundledTranslation());
                    writer.name("state").value(entry.state().name());
                    writer.endObject();
                }
                writer.endObject();
                writer.endObject();
            }
            writer.endObject();
            writer.endObject();
        }
        output.write('\n');
        byte[] bytes = output.bytes.toByteArray();
        // The writer cannot persist a document the strict reader would reject.
        if (!decode(bytes).equals(project)) throw new IOException("Project serialization did not round-trip.");
        return bytes;
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override public void write(int value) throws IOException {
            if (bytes.size() == MAX_BYTES) throw new IOException("Project exceeds the byte limit.");
            bytes.write(value);
        }

        @Override public void write(byte[] value, int offset, int length) throws IOException {
            if (length > MAX_BYTES - bytes.size()) throw new IOException("Project exceeds the byte limit.");
            bytes.write(value, offset, length);
        }
    }

    private static JsonElement read(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 12 || ++nodes[0] > MAX_NODES) throw new IOException("Project JSON complexity limit exceeded.");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                var object = new JsonObject();
                var names = new HashSet<String>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw new IOException("Duplicate project JSON member.");
                    object.add(name, read(reader, depth + 1, nodes));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                var array = new JsonArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1, nodes));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String value = reader.nextString();
                // Schema version is the sole numeric field.
                if (!Set.of("1", "2").contains(value)) throw new IOException("Unsupported numeric value in project.");
                yield new JsonPrimitive(Integer.parseInt(value));
            }
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Unexpected project JSON token.");
        };
    }

    private static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) throw new IOException("Expected a project JSON object.");
        return value.getAsJsonObject();
    }

    private static String string(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Expected a project JSON string.");
        }
        return value.getAsString();
    }

    private static String nullable(JsonElement value) throws IOException {
        return value != null && value.isJsonNull() ? null : string(value);
    }

    private static void fields(JsonObject value, String... names) throws IOException {
        if (!value.keySet().equals(Set.of(names))) throw new IOException("Missing or unknown project schema fields.");
    }
}

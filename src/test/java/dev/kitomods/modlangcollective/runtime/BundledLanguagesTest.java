package dev.kitomods.modlangcollective.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import dev.kitomods.modlangcollective.editor.TargetLanguages;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checks shipped UI translations against the English source contract. */
class BundledLanguagesTest {
    @Test void everyBundledLanguageRetainsKeysArgumentsAndBrand() throws Exception {
        Path directory = Path.of("src/main/resources/assets/modlangcollective/lang");
        JsonObject source = read(directory.resolve("en_us.json"));
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String locale = file.getFileName().toString().replace(".json", "");
                assertTrue(TargetLanguages.isSupported(locale), "Excluded UI locale: " + locale);
                JsonObject translated = read(file);
                assertEquals(source.keySet(), translated.keySet(), file.toString());
                for (String key : source.keySet()) {
                    assertTrue(translated.get(key).isJsonPrimitive() && translated.get(key).getAsJsonPrimitive().isString(), file + ": " + key);
                    String original = source.get(key).getAsString();
                    String value = translated.get(key).getAsString();
                    assertFalse(value.isBlank(), file + ": " + key);
                    assertTrue(PrintfPlaceholders.compatible(original, value), file + ": " + key);
                    assertFalse(value.contains("\uFFFD"), file + ": " + key);
                }
                assertEquals("ModLang Collective", translated.get("modmenu.nameTranslation.modlangcollective").getAsString());
            }
        }
    }

    private static JsonObject read(Path file) throws Exception {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonReader json = new JsonReader(reader);
            JsonObject result = new JsonObject();
            json.beginObject();
            while (json.hasNext()) {
                String key = json.nextName();
                assertFalse(result.has(key), "Duplicate key in " + file + ": " + key);
                result.add(key, JsonParser.parseReader(json));
            }
            json.endObject();
            return result;
        }
    }
}

package dev.kitomods.modlangcollective.runtime;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Compares argument identity, conversion, and occurrence count without rewriting saved text. */
final class PrintfPlaceholders {
    // Minecraft accepts numeric width/precision and normalizes d/f when loading language JSON.
    private static final Pattern TOKEN = Pattern.compile("%(?:(\\d+)\\$)?([\\d.]*)([sdf%])");

    private PrintfPlaceholders() { }

    static boolean compatible(String source, String translation) {
        Map<String, Integer> expected = signature(source);
        return expected != null && expected.equals(signature(translation));
    }

    private static Map<String, Integer> signature(String text) {
        var result = new TreeMap<String, Integer>();
        int implicit = 1;
        boolean trailingPercent = false;
        for (int cursor = 0; cursor < text.length(); cursor++) {
            if (text.charAt(cursor) != '%') continue;
            var matcher = TOKEN.matcher(text).region(cursor, text.length());
            if (!matcher.lookingAt()) {
                // Literal percentages ("100% complete") are common ordinary UI text. A leading
                // printf token character is ambiguous or unsafe and is rejected conservatively.
                if (cursor + 1 < text.length()) {
                    char next = text.charAt(cursor + 1);
                    if (Character.isLetterOrDigit(next) || next == '$' || next == '.' || next == '-'
                            || next == '+' || next == '#' || next == '<') return null;
                } else trailingPercent = true;
                continue;
            }
            String position = matcher.group(1);
            String numericFormat = matcher.group(2);
            String conversion = matcher.group(3);
            if (!numericFormat.isEmpty() && !conversion.equals("d") && !conversion.equals("f")) return null;
            String identity;
            if (conversion.equals("%")) {
                if (position != null) return null;
                identity = "%";
            } else {
                int argument;
                try {
                    argument = position == null ? implicit++ : Integer.parseInt(position);
                } catch (NumberFormatException exception) {
                    return null;
                }
                if (argument < 1) return null;
                identity = argument + ":" + conversion;
            }
            result.merge(identity, 1, Integer::sum);
            cursor = matcher.end() - 1;
        }
        // Vanilla treats a dangling percent as an invalid format and falls back to literal text,
        // which would suppress any otherwise valid argument substitutions or escaped percents.
        return trailingPercent && !result.isEmpty() ? null : result;
    }
}

package dev.kitomods.modlangcollective.discovery;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Identifies source strings that should remain exactly as supplied by the mod. */
public final class TranslationTextPolicy {
    private static final Pattern FORMATTING = Pattern.compile("(?i)§x(?:§[0-9a-f]){6}|§[0-9a-fk-or]");
    private static final Pattern PRINTF = Pattern.compile("%(?:%|(?:\\d+\\$)?[\\d.]*[sdf])");

    private TranslationTextPolicy() { }

    public static boolean isProtected(String modId, String displayName, String text) {
        if (text == null) return true;
        String normalized = normalize(text);
        if (normalized.isEmpty()) return true;
        if (matches(normalized, modId) || matches(normalized, displayName)) return true;
        String withoutFormatting = FORMATTING.matcher(text).replaceAll("");
        String withoutPlaceholders = PRINTF.matcher(withoutFormatting).replaceAll("");
        return withoutPlaceholders.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    public static boolean isProtected(DiscoveryCatalog catalog, String text) {
        Objects.requireNonNull(catalog, "catalog");
        return isProtected(catalog.modId(), catalog.displayName(), text);
    }

    private static boolean matches(String text, String candidate) {
        return candidate != null && !candidate.isBlank() && text.equals(normalize(candidate));
    }

    private static String normalize(String text) {
        return FORMATTING.matcher(text).replaceAll("").strip().toLowerCase(Locale.ROOT);
    }
}

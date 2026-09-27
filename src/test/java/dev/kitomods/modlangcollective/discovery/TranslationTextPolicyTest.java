package dev.kitomods.modlangcollective.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranslationTextPolicyTest {
    @Test void protectsExactOwnModNameOrIdAfterFormattingAndWhitespaceNormalization() {
        assertTrue(TranslationTextPolicy.isProtected("example_mod", "Example Mod", " Example Mod "));
        assertTrue(TranslationTextPolicy.isProtected("example_mod", "Example Mod", "§aEXAMPLE MOD§r"));
        assertTrue(TranslationTextPolicy.isProtected("example_mod", "Example Mod", " EXAMPLE_MOD "));
        assertFalse(TranslationTextPolicy.isProtected("example_mod", "Example Mod", "Example Mod Configuration"));
        assertFalse(TranslationTextPolicy.isProtected("example_mod", "Example Mod", "Other Mod"));
    }

    @Test void recognizesCompleteLegacyRgbButNotMalformedFormatting() {
        assertTrue(TranslationTextPolicy.isProtected("example", "Example Mod", "§x§f§f§0§0§0§0★"));
        assertTrue(TranslationTextPolicy.isProtected("example", "Example Mod", "§x§f§f§0§0§0§0Example Mod"));
        assertFalse(TranslationTextPolicy.isProtected("example", "Example Mod", "§x§f§f0000★"));
    }

    @Test void protectsSymbolsAndPrintfOnlyTextButKeepsNumbersAndUnicodeWords() {
        for (String value : new String[] {"", "   ", "§a§r", "★ → ✓", "%s", "%1$s %2$d", "%%"}) {
            assertTrue(TranslationTextPolicy.isProtected("example", "Example", value), value);
        }
        assertFalse(TranslationTextPolicy.isProtected("example", "Example", "12345"));
        assertFalse(TranslationTextPolicy.isProtected("example", "Example", "日本語"));
        assertFalse(TranslationTextPolicy.isProtected("example", "Example", "Привет"));
        assertFalse(TranslationTextPolicy.isProtected("example", "Example", "A ★ B"));
        assertFalse(TranslationTextPolicy.isProtected("example", "Example", "%q"));
        assertFalse(TranslationTextPolicy.isProtected("example", "Example", "§z"));
    }
}

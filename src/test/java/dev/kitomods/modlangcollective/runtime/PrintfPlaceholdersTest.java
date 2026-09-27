package dev.kitomods.modlangcollective.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrintfPlaceholdersTest {
    @Test void comparesArgumentIdentityAndMultiplicity() {
        assertTrue(PrintfPlaceholders.compatible("%s %d %f", "%3$f %1$s %2$d"));
        assertTrue(PrintfPlaceholders.compatible("%2$s %s", "%1$s %2$s"));
        assertFalse(PrintfPlaceholders.compatible("%s %d", "%d %s"));
        assertFalse(PrintfPlaceholders.compatible("%s", "%1$s %1$s"));
        assertFalse(PrintfPlaceholders.compatible("%s %d", "%1$s"));
        assertFalse(PrintfPlaceholders.compatible("%s", "%0$s"));
        assertFalse(PrintfPlaceholders.compatible("%s", "%99999999999999$s"));
    }

    @Test void escapedPercentAndFormattingAreNotSilentlyRewritten() {
        assertTrue(PrintfPlaceholders.compatible("%% %s", "§a%1$s %%"));
        assertFalse(PrintfPlaceholders.compatible("%% %s", "%s"));
        assertFalse(PrintfPlaceholders.compatible("%%", "%1$%"));
        assertTrue(PrintfPlaceholders.compatible("Progress: 100%", "§bProgresso: 100%"));
        assertFalse(PrintfPlaceholders.compatible("%s", "%s 100%"));
        assertTrue(PrintfPlaceholders.compatible("Plain", ""));
        assertFalse(PrintfPlaceholders.compatible("%s", ""));
        assertFalse(PrintfPlaceholders.compatible("%s", "%n"));
        assertFalse(PrintfPlaceholders.compatible("%s", "%<s"));
        assertTrue(PrintfPlaceholders.compatible("%.2f %02d", "%2$02d %1$.2f"));
        assertFalse(PrintfPlaceholders.compatible("%s", "%2s"));
    }
}

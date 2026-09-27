package dev.kitomods.modlangcollective.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DiscoveryAuditTest {
    @TempDir Path temporary;

    @Test void rejectsResolvedReportInsideMods() throws Exception {
        Path mods = Files.createDirectory(temporary.resolve("mods"));
        Path original = Files.writeString(mods.resolve("existing.jar"), "preserve");
        Path alias;
        try {
            alias = Files.createSymbolicLink(temporary.resolve("alias"), mods);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links unavailable on this host");
            return;
        }
        assertThrows(IllegalArgumentException.class, () -> DiscoveryAudit.main(new String[] {
                "--mods", alias.toString(), "--report", original.toString()}));
        Path reportLink = Files.createSymbolicLink(temporary.resolve("report.json"), original);
        assertThrows(IllegalArgumentException.class, () -> DiscoveryAudit.main(new String[] {
                "--mods", mods.toString(), "--report", reportLink.toString()}));
        assertEquals("preserve", Files.readString(original));
    }

    @Test void reportReplacementDoesNotTruncateHardLinkTarget() throws Exception {
        Path mods = Files.createDirectory(temporary.resolve("mods"));
        Path original = Files.writeString(mods.resolve("original.txt"), "preserve");
        Path report;
        try {
            report = Files.createLink(temporary.resolve("report.json"), original);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Hard links unavailable on this host");
            return;
        }
        DiscoveryAudit.main(new String[] {"--mods", mods.toString(), "--report", report.toString()});
        assertEquals("preserve", Files.readString(original));
        assertTrue(Files.readString(report).contains("jarCount"));
    }
}

package dev.kitomods.modlangcollective.editor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetLanguagesTest {
    @Test void keepsContemporaryRegionalAndScriptVariants() {
        for (String locale : new String[]{"pt_br", "pt_pt", "es_mx", "de_ch", "be_latn", "zlm_arab", "en_us", "eo_uy", "io_en", "tok", "jbo_en", "isv", "vp_vl", "enp"}) {
            assertTrue(TargetLanguages.isSupported(locale), locale);
        }
    }
    @Test void excludesHistoricalFictionalAndJokeTargets() {
        for (String locale : new String[]{"la_la", "lzh", "rpr", "qid", "qya_aa", "tlh_aa", "en_ud", "en_pt", "lol_us", "enws", "unknown"}) {
            assertFalse(TargetLanguages.isSupported(locale), locale);
        }
    }
}

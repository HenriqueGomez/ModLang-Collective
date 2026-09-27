package dev.kitomods.modlangcollective.client.runtime;

import java.util.List;

/** Internal bridge invoked before the newly loaded language becomes globally visible. */
public interface LanguageOverlayAccess {
    void modlangcollective$applySavedTranslations(List<String> languages);
}

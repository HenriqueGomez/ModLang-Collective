package dev.kitomods.modlangcollective.client.runtime;

import com.google.gson.Gson;
import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.project.ProjectStore;
import dev.kitomods.modlangcollective.runtime.RuntimeTranslations;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import org.slf4j.LoggerFactory;

/** Local projects overlay the active client language after normal resource-pack loading. */
public final class RuntimeLanguageBridge {
    private record Context(ProjectStore store, List<DiscoveryCatalog> catalogs) { }
    public record ApplyReport(String locale, int appliedKeys, List<RuntimeTranslations.Issue> issues) {
        public ApplyReport { issues = List.copyOf(issues); }
    }
    private static volatile Context context;
    private static volatile ApplyReport lastReport;
    private static volatile RuntimeException lastFailure;

    private RuntimeLanguageBridge() { }

    public static ApplyReport latestReport() { return lastReport; }

    public static void initialize(ProjectStore store, List<DiscoveryCatalog> catalogs) {
        context = new Context(store, List.copyOf(catalogs));
    }

    public static Map<String, String> overlay(Map<String, String> vanilla, List<String> languages) {
        Context current = context;
        if (current == null || languages.isEmpty()) return vanilla;
        String locale = languages.getLast();
        try {
            var snapshot = RuntimeTranslations.load(current.store(), current.catalogs(), locale);
            var combined = new HashMap<>(vanilla);
            // Use Minecraft's own parser so numeric placeholders follow vanilla's %d/%f -> %s normalization.
            byte[] json = new Gson().toJson(snapshot.overrides()).getBytes(StandardCharsets.UTF_8);
            Language.loadFromJson(new ByteArrayInputStream(json), combined::put);
            var result = Map.copyOf(combined);
            lastReport = new ApplyReport(locale, snapshot.overrides().size(), snapshot.issues());
            lastFailure = null;
            var logger = LoggerFactory.getLogger("modlangcollective");
            logger.info("Applied {} local translation keys for {}; {} runtime findings.",
                    snapshot.overrides().size(), locale, snapshot.issues().size());
            snapshot.issues().forEach(issue -> logger.warn("Translation runtime [{}] mod={} namespace={} key={}: {}",
                    issue.code(), issue.modId(), issue.namespace(), issue.key(), issue.message()));
            return result;
        } catch (RuntimeException exception) {
            lastReport = null;
            lastFailure = exception;
            LoggerFactory.getLogger("modlangcollective").error(
                    "Local translations could not be applied; normal language resources remain active.", exception);
            return vanilla;
        }
    }

    /** Called on the client thread after persistence; a failed reload never rolls back saved work. */
    public static ApplyReport reloadSavedTranslations() {
        lastReport = null;
        lastFailure = null;
        var minecraft = Minecraft.getInstance();
        minecraft.getLanguageManager().onResourceManagerReload(minecraft.getResourceManager());
        if (lastFailure != null) throw new IllegalStateException("Local language application failed; saved projects are intact.", lastFailure);
        ApplyReport report = lastReport;
        if (report == null) throw new IllegalStateException("The language integration did not report a result; saved projects are intact.");
        return report;
    }
}

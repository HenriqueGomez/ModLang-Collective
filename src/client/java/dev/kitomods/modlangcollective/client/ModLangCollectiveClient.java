package dev.kitomods.modlangcollective.client;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.discovery.LanguageDiscovery;
import dev.kitomods.modlangcollective.discovery.ModSource;
import dev.kitomods.modlangcollective.project.ProjectStore;
import dev.kitomods.modlangcollective.service.ProjectSynchronizer;
import dev.kitomods.modlangcollective.client.runtime.RuntimeLanguageBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Discovers sources, synchronizes local projects, and initializes active-language overlays. */
public final class ModLangCollectiveClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("modlangcollective");
    private static volatile List<DiscoveryCatalog> catalogs = List.of();
    private static final Set<String> BUILT_INS = Set.of("minecraft", "java", "fabricloader");

    public static List<DiscoveryCatalog> catalogs() {
        return catalogs;
    }

    @Override
    public void onInitializeClient() {
        var discovery = new LanguageDiscovery();
        catalogs = FabricLoader.getInstance().getAllMods().stream()
                .filter(mod -> !BUILT_INS.contains(mod.getMetadata().getId()))
                .sorted(Comparator.comparing(mod -> mod.getMetadata().getId()))
                .map(mod -> discovery.scan(new ModSource(mod.getMetadata().getId(),
                        mod.getMetadata().getVersion().getFriendlyString(), mod.getRootPaths(), mod.getMetadata().getName())))
                .toList();
        int resources = catalogs.stream().mapToInt(c -> c.counts().resourceCount()).sum();
        int issues = catalogs.stream().mapToInt(c -> c.counts().issueCount()).sum();
        LOGGER.info("Read-only language discovery: {} mods, {} resources, {} findings. Open the editor through Mod Menu.",
                catalogs.size(), resources, issues);
        for (var catalog : catalogs) {
            for (var issue : catalog.issues()) {
                if (issue.severity() != DiscoveryCatalog.Severity.INFO) {
                    LOGGER.warn("Discovery [{}] mod={} resource={} root={}: {}", issue.code(),
                            catalog.modId(), issue.resourcePath(), issue.rootIndex(), issue.message());
                }
            }
        }
        try {
            var root = FabricLoader.getInstance().getGameDir().resolve("mods").resolve("modlangcollective");
            var store = new ProjectStore(root);
            var results = new ProjectSynchronizer(store).synchronize(catalogs);
            RuntimeLanguageBridge.initialize(store, catalogs);
            LOGGER.info("Translation projects: {} updated, {} unchanged, {} failed. Accepted translations load with the active game language.",
                    results.stream().filter(r -> r.status() == ProjectSynchronizer.Status.UPDATED).count(),
                    results.stream().filter(r -> r.status() == ProjectSynchronizer.Status.UNCHANGED).count(),
                    results.stream().filter(r -> r.status() == ProjectSynchronizer.Status.FAILED).count());
            results.stream().filter(r -> r.status() == ProjectSynchronizer.Status.FAILED).forEach(result ->
                    LOGGER.warn("Project synchronization [{}] mod={} target={}; existing work was retained.",
                            result.failureCode(), result.modId(), result.targetLocale()));
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Translation project storage is unavailable; discovery remains available. Failure type: {}",
                    exception.getClass().getSimpleName());
        }
    }
}

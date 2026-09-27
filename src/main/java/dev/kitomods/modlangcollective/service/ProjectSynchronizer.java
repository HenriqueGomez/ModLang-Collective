package dev.kitomods.modlangcollective.service;

import dev.kitomods.modlangcollective.discovery.DiscoveryCatalog;
import dev.kitomods.modlangcollective.project.ProjectEngine;
import dev.kitomods.modlangcollective.project.ProjectStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Synchronizes existing targets independently; never creates an unsolicited target language. */
public final class ProjectSynchronizer {
    private final ProjectStore store;

    public ProjectSynchronizer(ProjectStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public List<Result> synchronize(List<DiscoveryCatalog> catalogs) {
        var results = new ArrayList<Result>();
        var seen = new HashSet<String>();
        var duplicates = new HashSet<String>();
        for (var catalog : catalogs) {
            if (!seen.add(catalog.modId())) duplicates.add(catalog.modId());
        }
        for (var catalog : catalogs.stream().sorted(Comparator.comparing(DiscoveryCatalog::modId)).toList()) {
            String modId = catalog.modId();
            if (duplicates.contains(modId)) {
                results.add(new Result(modId, null, Status.FAILED, "DUPLICATE_MOD_ID"));
                continue;
            }
            List<String> targets;
            try {
                store.ensureModDirectory(modId);
                targets = store.listTargets(modId);
            } catch (IOException | IllegalArgumentException exception) {
                results.add(failure(modId, null, exception));
                continue;
            }
            for (String target : targets) {
                try {
                    var loaded = store.load(modId, target);
                    if (loaded.isEmpty()) {
                        results.add(new Result(modId, target, Status.FAILED, "PROJECT_DISAPPEARED"));
                        continue;
                    }
                    var existing = loaded.get();
                    var merged = ProjectEngine.merge(existing.project(), catalog);
                    if (merged.equals(existing.project())) {
                        results.add(new Result(modId, target, Status.UNCHANGED, null));
                    } else {
                        store.save(merged, existing.revision());
                        results.add(new Result(modId, target, Status.UPDATED, null));
                    }
                } catch (IOException | IllegalArgumentException exception) {
                    results.add(failure(modId, target, exception));
                }
            }
        }
        return List.copyOf(results);
    }

    private static Result failure(String modId, String target, Exception exception) {
        return new Result(modId, target, Status.FAILED,
                exception instanceof ProjectStore.ConflictException ? "EXTERNAL_EDIT_OR_BUSY"
                        : exception instanceof IOException ? "STORAGE_ERROR" : "INVALID_SOURCE_OR_PROJECT");
    }

    public enum Status { UPDATED, UNCHANGED, FAILED }
    public record Result(String modId, String targetLocale, Status status, String failureCode) { }
}

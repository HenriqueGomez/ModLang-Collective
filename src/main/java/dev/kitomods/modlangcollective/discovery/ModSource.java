package dev.kitomods.modlangcollective.discovery;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** A loaded mod and its resource roots; ownership and filesystem lifetime remain with the caller. */
public record ModSource(String id, String version, List<Path> roots, String displayName) {
    public ModSource(String id, String version, List<Path> roots) {
        this(id, version, roots, id);
    }

    public ModSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(displayName, "displayName");
        roots = List.copyOf(roots);
    }
}

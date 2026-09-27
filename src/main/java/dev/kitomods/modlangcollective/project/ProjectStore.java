package dev.kitomods.modlangcollective.project;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Local persistence with optimistic revisions, cooperative locks, and retained full-byte backups. */
public final class ProjectStore {
    private final Path root;
    private final BeforeReplace beforeReplace;

    public ProjectStore(Path root) {
        this(root, () -> { });
    }

    ProjectStore(Path root, BeforeReplace beforeReplace) {
        this.root = root.toAbsolutePath().normalize();
        this.beforeReplace = Objects.requireNonNull(beforeReplace);
    }

    public Path ensureModDirectory(String modId) throws IOException {
        TranslationProject.validateComponent(modId);
        Path directory = root.resolve(modId);
        ensureDirectory(directory);
        return directory;
    }

    public List<String> listTargets(String modId) throws IOException {
        TranslationProject.validateComponent(modId);
        Path directory = root.resolve(modId);
        checkAncestors(directory);
        if (attributes(directory) == null) return List.of();
        var targets = new ArrayList<String>();
        int count = 0;
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (++count > 100_000) throw new IOException("Project directory entry limit exceeded.");
                String name = child.getFileName().toString();
                if (!name.matches("[a-z0-9_-]{1,64}\\.json")) continue;
                String locale = name.substring(0, name.length() - 5);
                try {
                    TranslationProject.validateComponent(locale);
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                // Loading validates each target independently, so one broken target cannot hide others.
                targets.add(locale);
            }
        } catch (DirectoryIteratorException exception) {
            throw exception.getCause();
        }
        targets.sort(String::compareTo);
        return List.copyOf(targets);
    }

    public Optional<LoadedProject> load(String modId, String targetLocale) throws IOException {
        Path file = path(modId, targetLocale);
        byte[] bytes = read(file);
        if (bytes == null) return Optional.empty();
        TranslationProject project = ProjectJson.decode(bytes);
        identity(project, modId, targetLocale);
        return Optional.of(new LoadedProject(project, digest(bytes)));
    }

    /** A null revision means create-only. Any existing bytes then constitute a conflict. */
    public String save(TranslationProject project, String expectedRevision) throws IOException {
        byte[] bytes = ProjectJson.encode(project);
        Path file = path(project.modId(), project.targetLocale());
        ensureModDirectory(project.modId());
        Path lockPath = file.resolveSibling("." + project.targetLocale() + ".lock");
        checkFile(lockPath);
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS); var lock = channel.tryLock()) {
            if (lock == null) throw new ConflictException("Another process is saving this project.");
            byte[] previous = read(file);
            checkRevision(previous, expectedRevision);
            if (previous != null) identity(ProjectJson.decode(previous), project.modId(), project.targetLocale());
            if (previous != null && java.util.Arrays.equals(previous, bytes)) return digest(bytes);
            Path temporary = file.resolveSibling("." + project.targetLocale() + ".tmp-" + UUID.randomUUID());
            try {
                writeNew(temporary, bytes);
                beforeReplace.run();
                if (previous != null) {
                    Path backup = file.resolveSibling(project.targetLocale() + ".backup-" + UUID.randomUUID() + ".json");
                    writeNew(backup, previous);
                }
                // External editors need not honor our lock. Recheck after all preparatory writes.
                checkRevision(read(file), expectedRevision);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return digest(bytes);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (OverlappingFileLockException exception) {
            throw new ConflictException("Another local writer is saving this project.");
        }
    }

    /** Removes a confirmed local project by retaining its exact bytes in a recoverable archive. */
    public Path remove(String modId, String targetLocale, String expectedRevision) throws IOException {
        if (expectedRevision == null) throw new ConflictException("Removal requires a loaded project revision.");
        Path file = path(modId, targetLocale);
        checkAncestors(file.getParent());
        Path lockPath = file.resolveSibling("." + targetLocale + ".lock");
        checkFile(lockPath);
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS); var lock = channel.tryLock()) {
            if (lock == null) throw new ConflictException("Another process is saving this project.");
            byte[] previous = read(file);
            checkRevision(previous, expectedRevision);
            identity(ProjectJson.decode(previous), modId, targetLocale);
            Path archive = file.resolveSibling(targetLocale + ".removed-" + UUID.randomUUID() + ".json");
            if (checkFile(archive)) throw new IOException("Removal archive already exists.");
            beforeReplace.run();
            checkRevision(read(file), expectedRevision);
            Files.move(file, archive, StandardCopyOption.ATOMIC_MOVE);
            return archive;
        } catch (OverlappingFileLockException exception) {
            throw new ConflictException("Another local writer is saving this project.");
        }
    }

    private Path path(String modId, String locale) {
        TranslationProject.validateComponent(modId);
        TranslationProject.validateComponent(locale);
        return root.resolve(modId).resolve(locale + ".json");
    }

    private static void checkRevision(byte[] bytes, String expected) throws ConflictException {
        String actual = bytes == null ? null : digest(bytes);
        if (!Objects.equals(actual, expected)) throw new ConflictException("The project changed externally; reload it before saving.");
    }

    private static void identity(TranslationProject project, String modId, String locale) throws IOException {
        if (!project.modId().equals(modId) || !project.targetLocale().equals(locale)) {
            throw new IOException("Project identity does not match its storage path.");
        }
    }

    private static byte[] read(Path file) throws IOException {
        checkAncestors(file.getParent());
        if (!checkFile(file)) return null;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(ProjectJson.MAX_BYTES + 1);
            if (bytes.length > ProjectJson.MAX_BYTES) throw new IOException("Project exceeds the byte limit.");
            return bytes;
        }
    }

    private static void writeNew(Path file, byte[] bytes) throws IOException {
        checkAncestors(file.getParent());
        try (var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
    }

    private static void ensureDirectory(Path directory) throws IOException {
        checkAncestors(directory);
        if (attributes(directory) != null) return;
        Path parent = directory.getParent();
        if (parent != null) ensureDirectory(parent);
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException exception) {
            // A cooperative caller may have created it; validate the resulting directory.
        }
        checkAncestors(directory);
    }

    private static void checkAncestors(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            BasicFileAttributes attributes = attributes(current);
            if (attributes == null) continue;
            // On Windows, junctions are reparse points reported as 'other', not symbolic links.
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("Linked or special project directories are not supported.");
            }
            if (!attributes.isDirectory()) {
                throw new IOException("Project parent is not a directory.");
            }
        }
    }

    private static boolean checkFile(Path file) throws IOException {
        BasicFileAttributes attributes = attributes(file);
        if (attributes == null) return false;
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
            throw new IOException("Project path is not a regular file.");
        }
        return true;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            return null;
        } catch (SecurityException exception) {
            throw new IOException("Project path attributes could not be read.", exception);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public record LoadedProject(TranslationProject project, String revision) { }

    public static final class ConflictException extends IOException {
        public ConflictException(String message) { super(message); }
    }

    @FunctionalInterface
    interface BeforeReplace { void run() throws IOException; }
}

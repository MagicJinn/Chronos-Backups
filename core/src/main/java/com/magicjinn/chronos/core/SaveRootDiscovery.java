package com.magicjinn.chronos.core;

import com.magicjinn.chronos.core.config.Config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves which directories to copy and prune for a backup.
 * <p>
 * Uses {@link BackupRuntimeContext#getWorldSaveRoot()} when it already contains
 * {@code level.dat}. Otherwise performs a shallow filesystem scan under
 * {@link BackupRuntimeContext#getRunDirectory()} for sibling save folders
 * (e.g. Bukkit {@code world_nether}, {@code world_the_end}).
 */
public final class SaveRootDiscovery {
    /**
     * Max depth below the run directory (supports
     * {@code worlds/survival/level.dat}).
     */
    private static final int MAX_DISCOVERY_DEPTH = 2;

    // List of directories to skip during discovery. Common directories that are not
    // save roots.
    private static final Set<String> SKIP_DIR_NAMES = Set.of(
            "plugins",
            "logs",
            "libraries",
            "versions",
            "crash-reports",
            "mods",
            "config",
            "defaultconfigs",
            "journeymap",
            ".cache",
            ".git");

    private SaveRootDiscovery() {
    }

    /**
     * Describes which folders should be included in a backup and how they were
     * found.
     *
     * @param copyRoot         The main directory used as the root for the world
     *                         copy. This is what gets passed to the native world
     *                         copying process.
     * @param pruneRoots       The absolute paths to all save root directories (each
     *                         of which contains a {@code level.dat}) that should be
     *                         cleaned up or further processed after the copy. These
     *                         are all the relevant world save roots we care about
     *                         for backup.
     * @param discoveredByScan True if {@code level.dat} was not found in the
     *                         original (primary) save root, and sibling save
     *                         folders were discovered by scanning through
     *                         directories. False if everything was found where it
     *                         was expected.
     */

    public record BackupScope(Path copyRoot, List<Path> pruneRoots, boolean discoveredByScan) {
    }

    public static BackupScope resolve(BackupRuntimeContext context) throws IOException {
        Path primary = context.getWorldSaveRoot().toAbsolutePath().normalize();
        if (hasLevelDat(primary)) {
            return new BackupScope(primary, List.of(primary), false);
        }

        Path container = context.getRunDirectory().toAbsolutePath().normalize();
        List<Path> discovered = discoverSaveRoots(container);
        if (discovered.isEmpty()) {
            throw new IOException(
                    "No level.dat found at primary save root " + primary + " or under run directory " + container);
        }

        if (discovered.size() == 1) {
            Path only = discovered.get(0);
            return new BackupScope(only, List.of(only), true);
        }

        return new BackupScope(container, List.copyOf(discovered), true);
    }

    /** Maps a live-server save root to its path inside a snapshot directory. */
    public static Path snapshotPathForSourceRoot(Path copyRoot, Path sourceRoot, Path snapshotRoot) {
        Path copy = copyRoot.toAbsolutePath().normalize();
        Path source = sourceRoot.toAbsolutePath().normalize();
        Path snapshot = snapshotRoot.toAbsolutePath().normalize();
        if (copy.equals(source)) {
            return snapshot;
        }
        return snapshot.resolve(copy.relativize(source)).normalize();
    }

    static boolean hasLevelDat(Path directory) {
        return directory != null
                && Files.isDirectory(directory)
                && Files.isRegularFile(directory.resolve("level.dat"));
    }

    static List<Path> discoverSaveRoots(Path container) throws IOException {
        if (!Files.isDirectory(container)) {
            return List.of();
        }
        Set<String> skipNames = skipDirNames();
        List<Path> found = new ArrayList<>();
        collectSaveRoots(container, container, 0, skipNames, found);
        found.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return found;
    }

    private static Set<String> skipDirNames() {
        Set<String> skip = new HashSet<>(SKIP_DIR_NAMES);
        String backupFolder = Config.getBackupFolderName();
        if (backupFolder != null && !backupFolder.isBlank()) {
            skip.add(backupFolder.toLowerCase(Locale.ROOT));
        }
        return skip;
    }

    private static void collectSaveRoots(
            Path container,
            Path directory,
            int depth,
            Set<String> skipNames,
            List<Path> found)
            throws IOException {
        if (hasLevelDat(directory)) {
            found.add(directory.toAbsolutePath().normalize());
            return;
        }
        if (depth >= MAX_DISCOVERY_DEPTH) {
            return;
        }
        if (!directory.equals(container)) {
            String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
            if (skipNames.contains(name)) {
                return;
            }
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, Files::isDirectory)) {
            for (Path child : entries) {
                collectSaveRoots(container, child, depth + 1, skipNames, found);
            }
        }
    }
}

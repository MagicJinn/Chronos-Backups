package com.magicjinn.chronos.core;

import com.magicjinn.chronos.core.config.Config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves which directories to copy and prune for a backup.
 * <p>
 * Uses {@link BackupRuntimeContext#getWorldSaveRoot()} when it is the only save
 * folder under {@link BackupRuntimeContext#getRunDirectory()}. Otherwise
 * performs a shallow filesystem scan for every directory that contains
 * {@code level.dat} (e.g. Bukkit {@code world}, {@code world_nether},
 * {@code world_the_end}) and backs them up from a shared container root.
 */
public final class SaveRootDiscovery {
    /**
     * Max depth below the run directory (supports
     * {@code worlds/survival/level.dat}).
     */
    private static final int MAX_DISCOVERY_DEPTH = 2;

    // List of directories to skip during discovery. Common directories that are not
    // save roots.
    private static final Set<String> SKIP_DIR_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
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
            ".git")));

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
     * @param discoveredByScan True when multiple save roots were resolved by
     *                         scanning {@link BackupRuntimeContext#getRunDirectory()},
     *                         or when the primary save root had no {@code level.dat}
     *                         and roots were discovered that way. False when a
     *                         single save root was used as-is.
     */

    public static final class BackupScope {
        private final Path copyRoot;
        private final List<Path> pruneRoots;
        private final boolean discoveredByScan;

        public BackupScope(Path copyRoot, List<Path> pruneRoots, boolean discoveredByScan) {
            this.copyRoot = copyRoot;
            this.pruneRoots = pruneRoots;
            this.discoveredByScan = discoveredByScan;
        }

        public Path copyRoot() {
            return copyRoot;
        }

        public List<Path> pruneRoots() {
            return pruneRoots;
        }

        public boolean discoveredByScan() {
            return discoveredByScan;
        }
    }

    public static BackupScope resolve(BackupRuntimeContext context) throws IOException {
        Path primary = context.getWorldSaveRoot().toAbsolutePath().normalize();
        Path container = context.getRunDirectory().toAbsolutePath().normalize();
        List<Path> discovered = discoverSaveRoots(container);

        if (hasLevelDat(primary) && !containsNormalizedPath(discovered, primary)) {
            discovered = new ArrayList<>(discovered);
            discovered.add(primary);
            discovered.sort(Comparator.comparing(p -> p.getFileName().toString()));
        }

        if (discovered.isEmpty()) {
            throw new IOException(
                    "No level.dat found at primary save root " + primary + " or under run directory " + container);
        }

        if (discovered.size() == 1) {
            Path only = discovered.get(0);
            boolean scanned = !only.equals(primary) || !hasLevelDat(primary);
            return new BackupScope(only, Collections.singletonList(only), scanned);
        }

        return new BackupScope(container, Collections.unmodifiableList(new ArrayList<>(discovered)), true);
    }

    private static boolean containsNormalizedPath(List<Path> paths, Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        for (Path path : paths) {
            if (path.toAbsolutePath().normalize().equals(normalized)) {
                return true;
            }
        }
        return false;
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
            return Collections.emptyList();
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
        if (backupFolder != null && !backupFolder.trim().isEmpty()) {
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

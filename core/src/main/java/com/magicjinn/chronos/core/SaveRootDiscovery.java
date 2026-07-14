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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves full world save containers to copy for a backup.
 * <p>
 * Discovery locates dimension data via {@code region/*.mca}, rolls paths up to
 * save containers ({@code world/}, sibling folders on dedicated servers, or
 * {@code saves/<name>/} on integrated clients), and copies each entire
 * container.
 * Chunk pruning runs only on {@code region/} trees inside the snapshot.
 */
public final class SaveRootDiscovery {
    /**
     * Max depth below each search root (covers
     * {@code dimensions/minecraft/overworld/region}).
     */
    private static final int MAX_DIMENSION_SEARCH_DEPTH = 8;

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
            ".git",
            "region",
            "entities",
            "poi",
            "playerdata",
            "stats",
            "advancements")));

    private SaveRootDiscovery() {
    }

    public static final class BackupScope {
        /** Base path used to lay out multiple save containers inside a snapshot. */
        private final Path snapshotLayoutRoot;
        /**
         * Full world save directories to copy (each includes level data, region, etc.).
         */
        private final List<Path> saveContainers;
        private final boolean discoveredByScan;

        public BackupScope(Path snapshotLayoutRoot, List<Path> saveContainers, boolean discoveredByScan) {
            this.snapshotLayoutRoot = snapshotLayoutRoot;
            this.saveContainers = saveContainers;
            this.discoveredByScan = discoveredByScan;
        }

        /** @deprecated use {@link #snapshotLayoutRoot()} */
        @Deprecated
        public Path copyRoot() {
            return snapshotLayoutRoot;
        }

        public Path snapshotLayoutRoot() {
            return snapshotLayoutRoot;
        }

        /** @deprecated use {@link #saveContainers()} */
        @Deprecated
        public List<Path> pruneRoots() {
            return saveContainers;
        }

        public List<Path> saveContainers() {
            return saveContainers;
        }

        public boolean discoveredByScan() {
            return discoveredByScan;
        }
    }

    public static BackupScope resolve(BackupRuntimeContext context) throws IOException {
        Path primary = context.getWorldSaveRoot().toAbsolutePath().normalize();
        Path runDirectory = context.getRunDirectory().toAbsolutePath().normalize();
        Set<String> skipNames = skipDirNames();

        Set<Path> saveContainers = new LinkedHashSet<>();
        for (Path searchRoot : searchRoots(context, primary, runDirectory, skipNames)) {
            collectFromDimensionData(searchRoot, 0, MAX_DIMENSION_SEARCH_DEPTH, skipNames, saveContainers,
                    primary, runDirectory);
        }

        if (context.isDedicatedServer()) {
            addDedicatedServerContainers(runDirectory, primary, skipNames, saveContainers);
        } else {
            Path clientRoot = integratedClientSaveRoot(context, primary, runDirectory);
            if (looksLikeSaveContainer(clientRoot)) {
                saveContainers.add(clientRoot.toAbsolutePath().normalize());
            }
        }

        if (looksLikeSaveContainer(primary)) {
            saveContainers.add(primary);
        }

        List<Path> discovered = new ArrayList<>(saveContainers);
        discovered.sort(Comparator.comparing(p -> p.getFileName().toString()));

        if (discovered.isEmpty()) {
            throw new IOException(
                    "No world save found at primary save root "
                            + primary
                            + " or under run directory "
                            + runDirectory);
        }

        for (Path root : discovered) {
            if (!Files.isDirectory(root)) {
                throw new IOException("Save container is not a directory: " + root);
            }
        }

        if (discovered.size() == 1) {
            Path only = discovered.get(0);
            boolean scanned = !only.equals(primary);
            return new BackupScope(only, Collections.singletonList(only), scanned);
        }

        return new BackupScope(runDirectory, Collections.unmodifiableList(discovered), true);
    }

    private static void addDedicatedServerContainers(
            Path runDirectory,
            Path primary,
            Set<String> skipNames,
            Set<Path> saveContainers)
            throws IOException {
        if (!Files.isDirectory(runDirectory)) {
            return;
        }
        boolean primaryActive = hasRegionMca(primary) || hasLevelDat(primary);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(runDirectory, Files::isDirectory)) {
            for (Path child : entries) {
                if (shouldSkipSearchRoot(child, skipNames)) {
                    continue;
                }
                if (looksLikeSaveContainer(child)) {
                    saveContainers.add(child.toAbsolutePath().normalize());
                    continue;
                }
                if (primaryActive && Files.isDirectory(child.resolve("region"))) {
                    saveContainers.add(child.toAbsolutePath().normalize());
                }
            }
        }
    }

    private static Set<Path> searchRoots(
            BackupRuntimeContext context,
            Path primary,
            Path runDirectory,
            Set<String> skipNames)
            throws IOException {
        Set<Path> roots = new LinkedHashSet<>();
        if (context.isDedicatedServer()) {
            if (Files.isDirectory(primary)) {
                roots.add(primary);
            }
            if (Files.isDirectory(runDirectory)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(runDirectory, Files::isDirectory)) {
                    for (Path child : entries) {
                        if (shouldSkipSearchRoot(child, skipNames)) {
                            continue;
                        }
                        roots.add(child.toAbsolutePath().normalize());
                    }
                }
            }
            return roots;
        }

        Path integratedRoot = integratedClientSaveRoot(context, primary, runDirectory);
        if (Files.isDirectory(integratedRoot)) {
            roots.add(integratedRoot);
        } else if (Files.isDirectory(primary)) {
            roots.add(primary);
        }
        return roots;
    }

    /**
     * Integrated clients: only the active save under {@code saves/<world>/}, never
     * all of {@code saves/}.
     */
    private static Path integratedClientSaveRoot(
            BackupRuntimeContext context, Path primary, Path runDirectory) {
        Path savesRoot = runDirectory.resolve("saves").resolve(context.getWorldName());
        Path normalized = savesRoot.toAbsolutePath().normalize();
        Path primaryNorm = primary.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return normalized;
        }
        if (primaryNorm.startsWith(runDirectory.resolve("saves").toAbsolutePath().normalize())) {
            return primaryNorm;
        }
        return primaryNorm;
    }

    private static boolean shouldSkipSearchRoot(Path directory, Set<String> skipNames) {
        String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
        return skipNames.contains(name);
    }

    private static void collectFromDimensionData(
            Path directory,
            int depth,
            int maxDepth,
                    Set<String> skipNames,
            Set<Path> saveContainers,
            Path primary,
            Path runDirectory)
            throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        if (hasRegionMca(directory)) {
            saveContainers.add(rollupToContainer(directory, primary, runDirectory));
            return;
        }
        if (depth >= maxDepth) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, Files::isDirectory)) {
            for (Path child : entries) {
                if (shouldSkipSearchRoot(child, skipNames)) {
                    continue;
                }
                collectFromDimensionData(
                        child, depth + 1, maxDepth, skipNames, saveContainers, primary, runDirectory);
            }
        }
    }

    static Path rollupToContainer(Path dimensionRoot, Path primary, Path runDirectory) {
        Path dimension = dimensionRoot.toAbsolutePath().normalize();
        Path primaryNorm = primary.toAbsolutePath().normalize();
        Path runNorm = runDirectory.toAbsolutePath().normalize();

        if (dimension.startsWith(primaryNorm)) {
            return primaryNorm;
        }

        Path current = dimension;
        while (current.getParent() != null && !current.getParent().equals(runNorm)) {
            current = current.getParent();
        }
        return current;
    }

    /**
     * Full save folder: overworld/nested-dim container or a Bukkit-style dimension
     * sibling.
     */
    static boolean looksLikeSaveContainer(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        if (hasLevelDat(directory)) {
            return true;
        }
        if (Files.isDirectory(directory.resolve("dimensions"))) {
            return true;
        }
        return hasRegionMca(directory);
    }

    static boolean hasLevelDat(Path directory) {
        return directory != null
                && Files.isDirectory(directory)
                && (Files.isRegularFile(directory.resolve("level.dat"))
                        || Files.isRegularFile(directory.resolve("level.dat_new")));
    }

    /** Directory contains {@code region/*.mca} (chunk data on disk). */
    static boolean hasRegionMca(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        Path region = directory.resolve("region");
        if (!Files.isDirectory(region)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(region, "*.mca")) {
            return entries.iterator().hasNext();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Set<String> skipDirNames() {
        Set<String> skip = new HashSet<>(SKIP_DIR_NAMES);
        String backupFolder = Config.getBackupFolderName();
        if (backupFolder != null && !backupFolder.trim().isEmpty()) {
            skip.add(backupFolder.toLowerCase(Locale.ROOT));
        }
        return skip;
    }

    /**
     * Maps a live-server save container to its path inside a snapshot directory.
     */
    public static Path snapshotPathForSourceRoot(Path layoutRoot, Path sourceRoot, Path snapshotRoot) {
        Path layout = layoutRoot.toAbsolutePath().normalize();
        Path source = sourceRoot.toAbsolutePath().normalize();
        Path snapshot = snapshotRoot.toAbsolutePath().normalize();
        if (layout.equals(source)) {
            return snapshot;
        }
        return snapshot.resolve(layout.relativize(source)).normalize();
    }
}

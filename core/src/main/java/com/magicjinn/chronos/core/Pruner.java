package com.magicjinn.chronos.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

public final class Pruner {

    /**
     * Java Edition 26.1 Snapshot 6 and later: default dimensions live under
     * {@code dimensions/}
     * (e.g. overworld at {@code dimensions/minecraft/overworld}), not at the world
     * {@code world/} root. Compare against {@code DataVersion} from
     * {@code level.dat}
     * when locating chunk folders.
     *
     * @see <a href="https://minecraft.wiki/w/Java_Edition_level_format">Java
     *      Edition level format</a>
     */
    public static final int DATA_VERSION_WORLD_LAYOUT_26_1_SNAPSHOT_6 = 4774;

    private static final Logger LOG = Logger.getLogger(Pruner.class.getName());

    private static final String REGION_FOLDER_NAME = "region";

    /**
     * Roots for vanilla overworld, Nether, and End (each contains {@code region/},
     * {@code entities/}, {@code poi/}, etc.).
     */
    public static final class DimensionRoots {
        public final Path overworld;
        public final Path nether;
        public final Path end;

        public DimensionRoots(Path overworld, Path nether, Path end) {
            this.overworld = overworld;
            this.nether = nether;
            this.end = end;
        }
    }

    private Pruner() {}

    @Deprecated // TODO: Remove this
    public static String getMinecraftServerVersion(ServerEnvironment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment");
        }
        return environment.getMinecraftVersion();
    }

    public static void PruneMinecraftWorld(Path worldPath, int dataVersion, int spentTimeRequirementSeconds)
            throws IOException {
        if (!Files.isDirectory(worldPath)) {
            return;
        }

        int spentTimeRequirementTicks = spentTimeRequirementSeconds * 20;
        List<DataFolder> dataFolders = findDataFolders(worldPath, dataVersion);
        for (DataFolder dataFolder : dataFolders) {
            LOG.info(dataFolder.toString());
        }

        // Track which chunks have been deleted, and which entities and other data will
        // have to be deleted too.
        HashSet<ChunkCoordinate> deletedChunkCoordinates = new HashSet<>();

        if (dataVersion >= DATA_VERSION_WORLD_LAYOUT_26_1_SNAPSHOT_6) {
            pruneRegionDirectories(dataFolders, spentTimeRequirementTicks);
        } else {
            // TODO: Implement pruning for older data versions
            throw new UnsupportedOperationException("Unsupported data version: " + dataVersion);
        }
    }

    /**
     * Finds all region/entities/poi groups in the save. For data versions >=
     * {@link #DATA_VERSION_WORLD_LAYOUT_26_1_SNAPSHOT_6}, only scans
     * {@code dimensions/}. Older saves may also use region folders at the world
     * root. Looks for region directories under
     * {@code dimensions/<namespace>/<path>/region} recursively. The parent of each
     * {@code region} dir is considered the dimension root.
     */

    public static List<DataFolder> findDataFolders(Path worldRoot, int dataVersion) throws IOException {
        if (!Files.isDirectory(worldRoot)) {
            return Collections.emptyList();
        }

        Set<Path> regionDirectories = new LinkedHashSet<>();
        if (dataVersion >= DATA_VERSION_WORLD_LAYOUT_26_1_SNAPSHOT_6) {
            Path dimensions = worldRoot.resolve("dimensions");
            if (Files.isDirectory(dimensions)) {
                collectRegionDirectoriesUnder(dimensions, regionDirectories);
            }
        } else {
            Path rootRegion = worldRoot.resolve(REGION_FOLDER_NAME);
            if (Files.isDirectory(rootRegion)) {
                regionDirectories.add(rootRegion);
            }
            try (DirectoryStream<Path> topLevel = Files.newDirectoryStream(worldRoot)) {
                for (Path child : topLevel) {
                    if (!Files.isDirectory(child)) {
                        continue;
                    }
                    Path sub = child.resolve(REGION_FOLDER_NAME);
                    if (Files.isDirectory(sub)) {
                        regionDirectories.add(sub);
                    }
                }
            }
            Path dimensions = worldRoot.resolve("dimensions");
            if (Files.isDirectory(dimensions)) {
                collectRegionDirectoriesUnder(dimensions, regionDirectories);
            }
        }

        List<DataFolder> folders = new ArrayList<>(regionDirectories.size());
        for (Path regionDir : regionDirectories) {
            Path dimensionRoot = regionDir.getParent();
            if (dimensionRoot == null) {
                continue;
            }
            folders.add(
                    new DataFolder(
                            regionDir,
                            dimensionRoot.resolve("entities"),
                            dimensionRoot.resolve("poi")));
        }
        return Collections.unmodifiableList(folders);
    }

    /**
     * Collects every {@code region} directory under {@code root} by walking the
     * full subtree (supports {@code dimensions/namespace/dimension/region} and
     * deeper paths).
     */
    private static void collectRegionDirectoriesUnder(Path root, Set<Path> sink) throws IOException {
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (REGION_FOLDER_NAME.equals(dir.getFileName().toString())) {
                            sink.add(dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private static void pruneRegionDirectories(List<DataFolder> dataFolders, int spentTimeRequirementTicks) {
        // Track which chunks have been deleted, and which entities and other data will
        // have to be deleted too.
        for (DataFolder dataFolder : dataFolders) {
            HashSet<ChunkCoordinate> deletedChunkCoordinates = new HashSet<>();

        }

        // TODO: walk regionDirectories → *.mca using spentTimeRequirementTicks
    }

    private class ChunkCoordinate {
        public final int x;
        public final int z;

        public ChunkCoordinate(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    /**
     * Chunk storage for one dimension: Anvil {@code region/}, and the sibling
     * {@code entities/} and {@code poi/} trees when present. Paths are resolved
     * whether or not those folders exist yet on disk.
     */
    private static final class DataFolder {
        public final Path regionDirectory;
        public final Path entitiesDirectory;
        public final Path poiDirectory;

        public DataFolder(Path regionDirectory, Path entitiesDirectory, Path poiDirectory) {
            this.regionDirectory = Objects.requireNonNull(regionDirectory, "regionDirectory");
            this.entitiesDirectory = Objects.requireNonNull(entitiesDirectory, "entitiesDirectory");
            this.poiDirectory = Objects.requireNonNull(poiDirectory, "poiDirectory");
        }

        public String toString() {
            return "DataFolder\n\tregionDirectory: " + regionDirectory + "\n\tentitiesDirectory: " + entitiesDirectory
                    + "\n\tpoiDirectory: " + poiDirectory + "\n";
        }
    }
}

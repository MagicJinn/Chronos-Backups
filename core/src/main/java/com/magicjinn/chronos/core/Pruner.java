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

import net.querz.mca.Chunk;
import net.querz.mca.LoadFlags;
import net.querz.mca.MCAFile;
import net.querz.mca.MCAUtil;
import net.querz.nbt.tag.CompoundTag;

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
    private static final Logger LOG = Logger.getLogger(Pruner.class.getName());

    public static final int DATA_VERSION_WORLD_LAYOUT_26_1_SNAPSHOT_6 = 4774;
    private static final String REGION_FOLDER_NAME = "region";
    private static final String ENTITIES_FOLDER_NAME = "entities";
    private static final String POI_FOLDER_NAME = "poi";
    private static final String INHABITED_TIME_TAG_NAME = "InhabitedTime";

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

    public static void PruneMinecraftWorld(Path worldPath, int dataVersion, int spentTimeRequirementSeconds)
            throws IOException {
        if (!Files.isDirectory(worldPath)) {
            return;
        }

        int spentTimeRequirementTicks = spentTimeRequirementSeconds * 20;
        List<DataFolder> dataFolders = findDataFolders(worldPath, dataVersion);

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
                            dimensionRoot.resolve(ENTITIES_FOLDER_NAME),
                            dimensionRoot.resolve(POI_FOLDER_NAME)));
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
        for (DataFolder dataFolder : dataFolders) {
            // Track which chunks have been deleted, and which entities and other data will
            // have to be deleted too.
            HashSet<ChunkCoordinate> deletedChunkCoordinates = new HashSet<>();

            // loop over every file in region
            try (DirectoryStream<Path> regionFiles = Files.newDirectoryStream(dataFolder.regionDirectory)) {
                for (Path file : regionFiles) {
                    if (!file.getFileName().toString().endsWith(".mca")) {
                        continue;
                    }
                    try {
                        int regionX;
                        int regionZ;
                        try {
                            int[] region = parseRegionCoords(file.getFileName().toString());
                            regionX = region[0];
                            regionZ = region[1];
                        } catch (IllegalArgumentException ex) {
                            LOG.warning("Skipping region file with unexpected name: " + file);
                            continue;
                        }

                        // RAW: modern chunks have no "Level" wrapper; Querz's parsed Chunk would throw.
                        MCAFile mca = MCAUtil.read(file.toFile(), LoadFlags.RAW);
                        int chunkBaseX = MCAUtil.regionToChunk(regionX);
                        int chunkBaseZ = MCAUtil.regionToChunk(regionZ);

                        for (int lz = 0; lz < 32; lz++) {
                            for (int lx = 0; lx < 32; lx++) {
                                Chunk chunk = mca.getChunk(lx, lz);
                                if (chunk == null) {
                                    continue;
                                }
                                CompoundTag chunkRoot = chunk.getHandle();
                                if (chunkRoot == null) {
                                    continue;
                                }
                                long inhabitedTicks = readInhabitedTimeTicks(chunkRoot);
                                if (inhabitedTicks < spentTimeRequirementTicks) {
                                    deletedChunkCoordinates.add(new ChunkCoordinate(chunkBaseX + lx, chunkBaseZ + lz));
                                }
                            }
                        }
                    } catch (IOException e) {
                        LOG.severe("Error reading region file: " + file);
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                LOG.severe("Error listing region directory: " + dataFolder.regionDirectory);
                e.printStackTrace();
            }
        }
    }

    /**
     * Cumulative player time in ticks. Older chunks store this under {@code Level};
     * 1.18+ stores it on the chunk root.
     */
    private static long readInhabitedTimeTicks(CompoundTag chunkRoot) {
        CompoundTag level = chunkRoot.getCompoundTag("Level");
        if (level != null && level.containsKey(INHABITED_TIME_TAG_NAME)) {
            return level.getLong(INHABITED_TIME_TAG_NAME);
        }
        // 1.18+
        if (chunkRoot.containsKey(INHABITED_TIME_TAG_NAME)) {
            return chunkRoot.getLong(INHABITED_TIME_TAG_NAME);
        }
        return 0L;
    }

    /** Parses a region filename ({@literal r.<rx>.<rz>.mca}). */
    private static int[] parseRegionCoords(String fileName) {
        if (!fileName.startsWith("r.") || !fileName.endsWith(".mca")) {
            throw new IllegalArgumentException(fileName);
        }
        String core = fileName.substring(2, fileName.length() - ".mca".length());
        int dot = core.indexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException(fileName);
        }
        int regionX = Integer.parseInt(core.substring(0, dot));
        int regionZ = Integer.parseInt(core.substring(dot + 1));
        return new int[] { regionX, regionZ };
    }

    private static final class ChunkCoordinate {
        final int x;
        final int z;

        ChunkCoordinate(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ChunkCoordinate that = (ChunkCoordinate) o;
            return x == that.x && z == that.z;
        }

        @Override
        public int hashCode() {
            return 31 * x + z;
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

package com.magicjinn.chronos.core;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

// TODO: Consider moving to a faster library, possibly even a non-java one.
/*
- https://github.com/VilleOlof/mca
 */
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
    private static final String DIMENSIONS_FOLDER_NAME = "dimensions";
    private static final String REGION_FOLDER_NAME = "region";
    private static final String ENTITIES_FOLDER_NAME = "entities";
    private static final String POI_FOLDER_NAME = "poi";
    private static final String INHABITED_TIME_TAG_NAME = "InhabitedTime";

    /** Anvil region files store location + timestamp tables (4096 + 4096 bytes) before chunk sectors. */
    private static final long MIN_ANVIL_REGION_FILE_BYTES = 8192L;

    private Pruner() {}

    public static void PruneMinecraftWorld(Path worldPath, int dataVersion, int spentTimeRequirementSeconds)
            throws IOException {
        if (!Files.isDirectory(worldPath)) {
            return;
        }

        int spentTimeRequirementTicks = spentTimeRequirementSeconds * 20;
        List<DataFolder> dataFolders = findDataFolders(worldPath, dataVersion);
        // Layout-specific discovery is handled in findDataFolders; pruning logic is the same for all versions.
        pruneRegionDirectories(dataFolders, spentTimeRequirementTicks);
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
            Path dimensions = worldRoot.resolve(DIMENSIONS_FOLDER_NAME);
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
            Path dimensions = worldRoot.resolve(DIMENSIONS_FOLDER_NAME);
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

    private static void pruneRegionDirectories(List<DataFolder> dataFolders, int spentTimeRequirementTicks)
            throws IOException {
        int prunedChunks = 0; // For logging
        for (DataFolder dataFolder : dataFolders) {
            if (Backupper.shouldAbortBackupWork()) {
                throw new InterruptedIOException("Pruning aborted during shutdown");
            }
            if (!Files.isDirectory(dataFolder.regionDirectory)) {
                continue;
            }
            try (DirectoryStream<Path> regionFiles = Files.newDirectoryStream(dataFolder.regionDirectory)) {
                for (Path regionPath : regionFiles) {
                    if (Backupper.shouldAbortBackupWork()) {
                        throw new InterruptedIOException("Pruning aborted during shutdown");
                    }
                    if (!regionPath.getFileName().toString().endsWith(".mca")) {
                        continue;
                    }
                    try {
                        parseRegionCoords(regionPath.getFileName().toString());
                    } catch (IllegalArgumentException ex) {
                        LOG.warning("Skipping region file with unexpected name: " + regionPath);
                        continue;
                    }

                    if (!hasMinimumAnvilHeader(regionPath)) {
                        continue;
                    }

                    MCAFile regionMca;
                    try {
                        regionMca = MCAUtil.read(regionPath.toFile(), LoadFlags.RAW);
                    } catch (EOFException e) {
                        LOG.warning("Corrupt or truncated region file (EOF), skipping: " + regionPath);
                        continue;
                    } catch (IOException e) {
                        LOG.warning("Failed to read region file, skipping: " + regionPath + " — " + e.getMessage());
                        continue;
                    }

                    List<int[]> slotsToClear = new ArrayList<>();

                    for (int lz = 0; lz < 32; lz++) {
                        for (int lx = 0; lx < 32; lx++) {
                            if ((lz * 32 + lx) % 64 == 0 && Backupper.shouldAbortBackupWork()) {
                                throw new InterruptedIOException("Pruning aborted during shutdown");
                            }
                            Chunk chunk = regionMca.getChunk(lx, lz);
                            if (chunk == null) {
                                continue;
                            }
                            CompoundTag chunkRoot = chunk.getHandle();
                            if (chunkRoot == null) {
                                continue;
                            }
                            long inhabitedTicks = readInhabitedTimeTicks(chunkRoot);
                            if (inhabitedTicks < spentTimeRequirementTicks) {
                                slotsToClear.add(new int[] { lx, lz });
                            }
                        }
                    }

                    if (slotsToClear.isEmpty()) {
                        continue;
                    }

                    for (int[] slot : slotsToClear) {
                        regionMca.setChunk(slot[0], slot[1], null);
                    }
                    writeMcaOrDelete(regionMca, regionPath.toFile());

                    String regionFileName = regionPath.getFileName().toString();
                    clearMatchingSlotsInSiblingMca(dataFolder.entitiesDirectory, regionFileName, slotsToClear);
                    clearMatchingSlotsInSiblingMca(dataFolder.poiDirectory, regionFileName, slotsToClear);

                    prunedChunks += slotsToClear.size();
                }
            }
        }
        LOG.info("Pruned " + prunedChunks + " chunks in backup.");
    }

    /**
     * Region, entities, and POI use the same {@code r.x.z.mca} grid; clearing a slot removes that column's data
     * without re-checking NBT inside entities/poi.
     */
    private static void clearMatchingSlotsInSiblingMca(Path siblingDir, String regionFileName, List<int[]> slotsToClear) {
        if (!Files.isDirectory(siblingDir)) {
            return;
        }
        Path path = siblingDir.resolve(regionFileName);
        if (!Files.isRegularFile(path)) {
            return;
        }
        if (!hasMinimumAnvilHeader(path)) {
            return;
        }
        try {
            MCAFile mca = MCAUtil.read(path.toFile(), LoadFlags.RAW);
            for (int[] slot : slotsToClear) {
                mca.setChunk(slot[0], slot[1], null);
            }
            writeMcaOrDelete(mca, path.toFile());
        } catch (EOFException e) {
            LOG.warning("Corrupt sibling MCA (EOF), leaving unchanged: " + path);
        } catch (IOException e) {
            LOG.warning("Could not update sibling MCA: " + path + " — " + e.getMessage());
        }
    }

    private static boolean hasMinimumAnvilHeader(Path mcaPath) {
        try {
            long size = Files.size(mcaPath);
            if (size < MIN_ANVIL_REGION_FILE_BYTES) {
                return false;
            }
            return true;
        } catch (IOException e) {
            LOG.warning("Could not stat MCA file, skipping: " + mcaPath + " — " + e.getMessage());
            return false;
        }
    }

    private static int countNonNullChunks(MCAFile mca) {
        int n = 0;
        for (int i = 0; i < 1024; i++) {
            if (mca.getChunk(i) != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * Writes the region back or deletes it if no chunk columns remain. {@link MCAUtil#write} skips replacing the file
     * when zero chunks serialize, so an empty region must be a deleted file.
     */
    private static void writeMcaOrDelete(MCAFile mca, File file) throws IOException {
        if (countNonNullChunks(mca) == 0) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        MCAUtil.write(mca, file);
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

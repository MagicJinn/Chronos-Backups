package com.magicjinn.chronos.core;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;

/**
 * Version-agnostic backup implementation.
 */
public final class Backupper {
    private static final Logger LOG = Logger.getLogger(Backupper.class.getName());

    private static final String CHRONOS_FOLDER_NAME = "chronos";
    private static final String CACHE_FOLDER_NAME = "cache";
    private static final String SESSION_LOCK_FILE_NAME = "session.lock";
    /** Loader atomic-write temps — copied mid-rename causes NoSuchFileException on Windows. */
    private static final String NEOFORGE_ATOMIC_TMP_SUFFIX = ".neoforge-tmp";
    private static final String FABRIC_ATOMIC_TMP_SUFFIX = ".fabric-tmp";
    private static final int DEDICATED_SERVER_SLASH_BACKWARDS_AMOUNT = 1;
    /** World root is already an absolute path from the server; walk up to the run directory. */
    private static final int INTEGRATED_FROM_WORLD_ROOT_TO_RUN = 2;
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public static void checkIn() {
        LOG.info("Backupper checking in");
    }

    public static void runBackup(BackupRuntimeContext context) {
        if (context == null) {
            LOG.warning("Backupper skipped: runtime context is unavailable.");
            return;
        }

        Path worldPath = context.getWorldSaveRoot();
        if (!Files.isDirectory(worldPath)) {
            context.logError("Backupper skipped: world path does not exist -> " + worldPath);
            return;
        }

        Path rootPath = resolveRootPathFromWorldPath(worldPath, context.isDedicatedServer());
        Path chronosFolder = rootPath.resolve(CHRONOS_FOLDER_NAME);
        Path cacheFolder = chronosFolder.resolve(CACHE_FOLDER_NAME);
        String backupId = context.getWorldName() + "-" + BACKUP_TIMESTAMP_FORMAT.format(LocalDateTime.now());
        Path cacheSnapshotPath = cacheFolder.resolve(backupId);
        Path zipOutputPath = chronosFolder.resolve(backupId + ".zip");

        context.logInfo("Chronos backup started for world " + context.getWorldName() + " -> " + worldPath);
        context.sendChat("Backup started for " + context.getWorldName());

        BackupWorldController worldController = context.getWorldController();
        boolean attemptedSavingPause = false;
        try {
            Files.createDirectories(cacheFolder);

            if (worldController != null) {
                context.logInfo("Chronos backup: flushing world to disk...");
                worldController.saveAllWorldData(context.getServerHandle());
                context.logInfo("Chronos backup: pausing automatic saves...");
                worldController.setWorldSavingDisabled(context.getServerHandle(), true);
                attemptedSavingPause = true;
            }

            context.logInfo("Chronos backup: copying world into cache (this can take a while)...");
            copyWorldToCache(worldPath, cacheSnapshotPath, context);

            int dataVersion = getDataVersionFromLevelData(cacheSnapshotPath);

            int pruneTimeRequirementSeconds = 60 * 5; // 5 minutes // TODO: Make this configurable
            context.logInfo("Chronos backup: pruning snapshot...");
            Pruner.PruneMinecraftWorld(cacheSnapshotPath, dataVersion, pruneTimeRequirementSeconds);

            context.logInfo("Chronos backup: writing zip archive...");
            zipSnapshot(cacheSnapshotPath, zipOutputPath);

            context.logInfo("Chronos backup completed: " + zipOutputPath);
            context.sendChat("Backup completed.");
        } catch (Throwable t) {
            String detail = t.getMessage();
            if (detail == null || detail.isEmpty()) {
                detail = t.getClass().getName();
            }
            context.logError("Chronos backup failed: " + detail);
            context.sendChat("Backup failed. Check server logs for details.");
            t.printStackTrace();
        } finally {
            if (attemptedSavingPause && worldController != null) {
                context.logInfo("Chronos backup: restoring automatic saves...");
                worldController.setWorldSavingDisabled(context.getServerHandle(), false);
            }
            try {
                deleteDirectory(cacheSnapshotPath);
            } catch (IOException e) {
                context.logError(
                        "Chronos backup cleanup failed for "
                                + cacheSnapshotPath
                                + ": "
                                + e.getMessage());
            }
        }
    }

    private Backupper() {
    }

    private static Path resolveRootPathFromWorldPath(Path worldPath, boolean dedicatedServer) {
        int slashBackwardsAmount =
                dedicatedServer ? DEDICATED_SERVER_SLASH_BACKWARDS_AMOUNT : INTEGRATED_FROM_WORLD_ROOT_TO_RUN;
        Path rootPath = worldPath;
        for (int i = 0; i < slashBackwardsAmount; i++) {
            if (rootPath.getParent() == null) {
                break;
            }
            rootPath = rootPath.getParent();
        }
        return rootPath;
    }

    private static int getDataVersionFromLevelData(Path worldPath) throws IOException {
        Path levelDataPath = worldPath.resolve("level.dat");
        if (!Files.isRegularFile(levelDataPath)) {
            return 0;
        }
        NamedTag namedTag = NBTUtil.read(levelDataPath.toFile());
        if (!(namedTag.getTag() instanceof CompoundTag)) {
            return 0;
        }
        CompoundTag root = (CompoundTag) namedTag.getTag();
        CompoundTag data = root.getCompoundTag("Data");
        if (data == null) {
            return 0;
        }
        return data.getInt("DataVersion");
    }

    private static final int COPY_PROGRESS_FILE_INTERVAL = 2000;

    /**
     * {@link Path#relativize} throws {@link IllegalArgumentException} when roots differ (common on
     * Windows if the walk paths are not normalized the same way as the world root). Fall back to URI
     * relativization used by other backup mods.
     */
    private static Path relativizeToWorldRoot(Path worldRoot, Path fullPath) throws IOException {
        Path root = worldRoot.toAbsolutePath().normalize();
        Path full = fullPath.toAbsolutePath().normalize();
        if (!full.startsWith(root)) {
            throw new IOException("Path is not under world root (cannot copy): root=" + root + " path=" + full);
        }
        try {
            return root.relativize(full);
        } catch (IllegalArgumentException e) {
            URI relativeUri = root.toUri().relativize(full.toUri());
            if (relativeUri.isAbsolute()) {
                throw new IOException("Cannot relativize path under world root: root=" + root + " path=" + full, e);
            }
            return Paths.get(relativeUri);
        }
    }

    private static void assertCacheOutsideWorld(Path worldRoot, Path cacheSnapshotPath) throws IOException {
        Path w = worldRoot.toAbsolutePath().normalize();
        Path c = cacheSnapshotPath.toAbsolutePath().normalize();
        if (c.startsWith(w)) {
            throw new IOException(
                    "Refusing backup: chronos cache folder would live inside the world directory "
                            + "(game/run directory resolution is wrong for this setup). world="
                            + w
                            + " cache="
                            + c);
        }
    }

    private static void copyWorldToCache(Path worldPath, Path cacheSnapshotPath, BackupRuntimeContext context)
            throws IOException {
        Path worldRoot = worldPath.toAbsolutePath().normalize();
        assertCacheOutsideWorld(worldRoot, cacheSnapshotPath);

        deleteDirectory(cacheSnapshotPath);
        Files.createDirectories(cacheSnapshotPath);

        AtomicInteger fileCounter = new AtomicInteger();

        Files.walkFileTree(
                worldRoot,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        Path relative = relativizeToWorldRoot(worldRoot, dir);
                        Path destination = cacheSnapshotPath.resolve(relative);
                        Files.createDirectories(destination);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (SESSION_LOCK_FILE_NAME.equals(file.getFileName().toString())) {
                            return FileVisitResult.CONTINUE;
                        }
                        String leaf = file.getFileName().toString();
                        if (leaf.endsWith(NEOFORGE_ATOMIC_TMP_SUFFIX) || leaf.endsWith(FABRIC_ATOMIC_TMP_SUFFIX)) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path relative = relativizeToWorldRoot(worldRoot, file);
                        Path destination = cacheSnapshotPath.resolve(relative);
                        try {
                            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                        } catch (NoSuchFileException e) {
                            // File disappeared between directory scan and copy (temp rename); omit from backup.
                            LOG.fine("Skipping vanished file during backup copy: " + file);
                        }
                        int n = fileCounter.incrementAndGet();
                        if (context != null && n % COPY_PROGRESS_FILE_INTERVAL == 0) {
                            context.logInfo("Chronos backup: copied " + n + " files so far...");
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        if (context != null) {
                            context.logError(
                                    "Chronos backup: could not read a world file or folder during copy: "
                                            + file
                                            + " — "
                                            + exc);
                        }
                        throw exc;
                    }
                });
        if (context != null) {
            context.logInfo(
                    "Chronos backup: finished copying " + fileCounter.get() + " files into cache.");
        }
    }

    private static void zipSnapshot(Path snapshotPath, Path zipOutputPath) throws IOException {
        Path zipRoot = snapshotPath.toAbsolutePath().normalize();
        Files.deleteIfExists(zipOutputPath);
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(zipOutputPath))) {
            Files.walkFileTree(
                    zipRoot,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Path relative = relativizeToWorldRoot(zipRoot, file);
                            String zipEntryName = relative.toString().replace('\\', '/');
                            zipOutputStream.putNextEntry(new ZipEntry(zipEntryName));
                            Files.copy(file, zipOutputStream);
                            zipOutputStream.closeEntry();
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        Files.walkFileTree(
                path,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}

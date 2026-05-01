package com.magicjinn.chronos.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Version-agnostic backup implementation.
 */
public final class Backupper {
    private static final Logger LOG = Logger.getLogger(Backupper.class.getName());

    private static final String CHRONOS_FOLDER_NAME = "chronos";
    private static final String CACHE_FOLDER_NAME = "cache";
    private static final String SESSION_LOCK_FILE_NAME = "session.lock";
    private static final int DEDICATED_SERVER_SLASH_BACKWARDS_AMOUNT = 1;
    private static final int INTEGRATED_SERVER_SLASH_BACKWARDS_AMOUNT = 2;
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public static void checkIn() {
        LOG.info("Backupper checking in");
    }

    public static void runBackup(BackupRuntimeContext context) {
        if (context == null) {
            LOG.warning("Backupper skipped: runtime context is unavailable.");
            return;
        }

        Path worldPath = resolveWorldPath(context);
        if (!Files.isDirectory(worldPath)) {
            context.logError("Backupper skipped: world path does not exist -> " + worldPath);
            return;
        }

        Path rootPath = resolveRootPath(worldPath, context.isDedicatedServer());
        Path chronosFolder = rootPath.resolve(CHRONOS_FOLDER_NAME);
        Path cacheFolder = chronosFolder.resolve(CACHE_FOLDER_NAME);
        String backupId = context.getWorldName() + "-" + BACKUP_TIMESTAMP_FORMAT.format(LocalDateTime.now());
        Path cacheSnapshotPath = cacheFolder.resolve(backupId);
        Path zipOutputPath = chronosFolder.resolve(backupId + ".zip");

        context.logInfo("Chronos backup started for world " + context.getWorldName() + " -> " + worldPath);
        context.sendChat("Backup started for " + context.getWorldName());

        BackupWorldController worldController = context.getWorldController();
        boolean worldSavingDisabled = false;
        try {
            Files.createDirectories(cacheFolder);

            if (worldController != null) {
                worldController.saveAllWorldData(context.getServerHandle());
                worldSavingDisabled = worldController.setWorldSavingDisabled(context.getServerHandle(), true);
            }

            copyWorldToCache(worldPath, cacheSnapshotPath);
            zipSnapshot(cacheSnapshotPath, zipOutputPath);

            context.logInfo("Chronos backup completed: " + zipOutputPath);
            context.sendChat("Backup completed.");
        } catch (IOException e) {
            context.logError("Chronos backup failed: " + e.getMessage());
            context.sendChat("Backup failed. Check server logs for details.");
            e.printStackTrace();
        } finally {
            if (worldSavingDisabled && worldController != null) {
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

    private static Path resolveRootPath(Path worldPath, boolean dedicatedServer) {
        int slashBackwardsAmount = dedicatedServer
                ? DEDICATED_SERVER_SLASH_BACKWARDS_AMOUNT
                : INTEGRATED_SERVER_SLASH_BACKWARDS_AMOUNT;
        Path rootPath = worldPath;
        for (int i = 0; i < slashBackwardsAmount; i++) {
            if (rootPath.getParent() == null) {
                break;
            }
            rootPath = rootPath.getParent();
        }
        return rootPath;
    }

    private static Path resolveWorldPath(BackupRuntimeContext context) {
        if (context.isDedicatedServer()) {
            return context.getRunDirectory().resolve(context.getWorldName());
        }
        return context.getRunDirectory().resolve("saves").resolve(context.getWorldName());
    }

    private static void copyWorldToCache(Path worldPath, Path cacheSnapshotPath) throws IOException {
        deleteDirectory(cacheSnapshotPath);
        Files.createDirectories(cacheSnapshotPath);

        Files.walkFileTree(
                worldPath,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        Path relative = worldPath.relativize(dir);
                        Path destination = cacheSnapshotPath.resolve(relative);
                        Files.createDirectories(destination);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (SESSION_LOCK_FILE_NAME.equals(file.getFileName().toString())) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path relative = worldPath.relativize(file);
                        Path destination = cacheSnapshotPath.resolve(relative);
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private static void zipSnapshot(Path snapshotPath, Path zipOutputPath) throws IOException {
        Files.deleteIfExists(zipOutputPath);
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(zipOutputPath))) {
            Files.walkFileTree(
                    snapshotPath,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Path relative = snapshotPath.relativize(file);
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

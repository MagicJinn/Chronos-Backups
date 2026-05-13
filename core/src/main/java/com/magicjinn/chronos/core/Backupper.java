package com.magicjinn.chronos.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.magicjinn.chronos.core.config.Config;

/**
 * Version-agnostic backup implementation.
 */
public final class Backupper {
    private static final Logger LOG = Logger.getLogger(Backupper.class.getName());

    private static final String CACHE_FOLDER_NAME = ".cache";
    /**
     * Includes milliseconds so rapid successive backups (e.g. speedtests) get
     * distinct file names.
     * Otherwise {@link #zipSnapshot} would {@code deleteIfExists} the same path and
     * drop prior zips.
     */
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss.SSS");

    private static Path chronosFolder;
    private static Path cacheFolder;

    /** Whether an in-flight copy / prune / zip work should stop. */
    private static volatile boolean shutdownRequested;

    /**
     * User cancel: abort in-flight backup work ({@link #shouldAbortBackupWork()})
     * and/or end an active {@link #speedtest} session. Cleared when a standalone
     * backup finishes unless a speedtest is still running
     */
    private static volatile boolean backupCancelRequested;

    /**
     * Whether {@link #runBackup} is executing its main work (copy > prune > zip).
     */
    private static final AtomicBoolean backupRunActive = new AtomicBoolean(false);

    /**
     * Whether a {@link #speedtest} session is in progress (excludes per-backup
     * {@link #backupRunActive}).
     */
    private static final AtomicBoolean speedtestSessionActive = new AtomicBoolean(false);

    static boolean isShutdownRequested() {
        return shutdownRequested || Thread.currentThread().isInterrupted();
    }

    /**
     * Whether shutdown, user cancel or interrupt should stop running backup.
     */
    static boolean shouldAbortBackupWork() {
        return shutdownRequested
                || backupCancelRequested
                || Thread.currentThread().isInterrupted();
    }

    /**
     * Whether a backup run is currently executing its main work.
     */
    public static boolean isBackupRunActive() {
        return backupRunActive.get();
    }

    public static boolean isSpeedtestSessionActive() {
        return speedtestSessionActive.get();
    }

    /**
     * Sets {@link #backupCancelRequested} when a backup run or a speedtest session
     * is active so
     * in-flight work aborts and/or the speedtest loop stops.
     *
     * @return {@code true} if that cancel signal was applied
     */
    public static boolean requestCancelInFlightBackup() {
        if (backupRunActive.get() || speedtestSessionActive.get()) {
            backupCancelRequested = true;
            return true;
        }
        return false;
    }

    /**
     * Runs backups for {@code s} seconds, or until
     * {@link #requestCancelInFlightBackup()} (e.g.
     * {@code /chronos cancel}) stops the session.
     */
    public static void speedtest(BackupRuntimeContext context, int s) {
        if (context == null) {
            LOG.warning("speedtest skipped: runtime context is unavailable.");
            return;
        }
        if (!speedtestSessionActive.compareAndSet(false, true)) {
            context.sendChat("A Chronos speedtest is already running.");
            return;
        }
        if (backupRunActive.get()) {
            speedtestSessionActive.set(false);
            context.sendChat("A Chronos speedtest or backup is already running.");
            return;
        }
        backupCancelRequested = false;
        long start = System.nanoTime();
        long end = start + s * 1_000_000_000L;
        int backups = 0;
        boolean stoppedEarly = false;
        try {
            while (System.nanoTime() < end && !isShutdownRequested()) {
                if (backupCancelRequested) {
                    stoppedEarly = true;
                    break;
                }
                if (runBackup(context))
                    backups++;
            }
        } finally {
            speedtestSessionActive.set(false);
            backupCancelRequested = false;
        }

        long duration = System.nanoTime() - start;
        String suffix = stoppedEarly ? " (stopped by /chronos cancel)" : "";
        String averages = backups > 0
                ? String.format(Locale.ROOT, ", average duration per backup: %.2f s",
                        (duration / 1_000_000_000.0) / backups)
                : "";
        context.sendChat(
                "Speedtest finished" + suffix + " in " + formatBackupDurationNanos(start) + ": " + backups
                        + " successful backup(s)"
                        + averages);
    }

    /**
     * Clears the shutdown flag when a new world session starts (after a prior
     * stop).
     */
    static void clearShutdownRequest() {
        shutdownRequested = false;
        backupCancelRequested = false;
        backupRunActive.set(false);
        speedtestSessionActive.set(false);
    }

    public static void InitializeBackupper() {
        chronosFolder = Core.RunningDirectory.resolve(Config.getBackupFolderName());
        cacheFolder = chronosFolder.resolve(CACHE_FOLDER_NAME);
        // Create the chronos folder if it doesn't exist
        try {
            Files.createDirectories(chronosFolder);
            Files.createDirectories(cacheFolder);
            try {
                // Attempt to set the cache folder as hidden (Windows only)
                Files.setAttribute(cacheFolder, "dos:hidden", true, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | UnsupportedOperationException ex) {
                // It's OK to silently ignore if unsupported (non-Windows, etc)
            }
        } catch (IOException e) {
            LOG.severe("Failed to create chronos folder: " + e.getMessage());
            return;
        }

        LOG.info("Backupper is ready and on standby...");
    }

    /**
     * Runs a backup and returns true if the backup was successful
     * 
     * @return {@code true} only when a zip archive was written successfully
     */
    public static boolean runBackup(BackupRuntimeContext context) {
        if (context == null) {
            LOG.warning("Backupper skipped: runtime context is unavailable.");
            return false;
        }
        if (isShutdownRequested()) {
            context.logInfo("Chronos backup skipped: shutdown in progress.");
            return false;
        }

        Path worldPath = context.getWorldSaveRoot();
        if (!Files.isDirectory(worldPath)) {
            context.logError("Backupper skipped: world path does not exist -> " + worldPath);
            return false;
        }

        // Claim the run atomically so two queued tasks cannot both start (e.g. double
        // /chronos backup).
        if (!backupRunActive.compareAndSet(false, true)) {
            String message = "Backup skipped: another backup is already running.";
            LOG.warning(message);
            context.sendChat(message);
            return false;
        }
        if (backupCancelRequested) {
            context.logInfo("Chronos backup skipped: cancel in progress.");
            backupRunActive.set(false);
            if (!backupRunActive.get()) {
                backupCancelRequested = false;
            }
            return false;
        }
        try {

            final String backupId = context.getWorldName() + "-" + BACKUP_TIMESTAMP_FORMAT.format(LocalDateTime.now());
        final Path zipOutputPath = chronosFolder.resolve(backupId + ".zip");
        final Path cacheSnapshotPath = cacheFolder.resolve(backupId);

        context.logInfo("Chronos backup started for world " + context.getWorldName() + " -> " + worldPath);
        context.sendChat("Backup started for " + context.getWorldName());

        final long backupStartNanos = System.nanoTime();
        BackupWorldController worldController = context.getWorldController();
        boolean attemptedSavingPause = false;
        boolean backupFinishedSuccessfully = false;
        boolean announceUserCancelInChat = false;
        try {
            Files.createDirectories(cacheFolder);

            if (worldController != null) {
                context.logInfo("Chronos backups: flushing world to disk...");
                worldController.saveAllWorldData(context.getServerHandle());
                context.logInfo("Chronos backups: pausing automatic saves...");
                worldController.setWorldSavingDisabled(context.getServerHandle(), true);
                attemptedSavingPause = true;
            }

            context.logInfo("Chronos backups: copying world into cache (this can take a while)...");
            Path worldRootAbs = worldPath.toAbsolutePath().normalize();
            assertCacheOutsideWorld(worldRootAbs, cacheSnapshotPath);
            deleteDirectory(cacheSnapshotPath);
            Files.createDirectories(cacheSnapshotPath);
            int[] outCopied = new int[1];
            RustPrunerBridge.copyWorldToCache(
                    worldRootAbs,
                    cacheSnapshotPath,
                    Config.getCopyBlacklist(),
                    Config.getPruneMaxWorkerThreads(),
                    outCopied);
            context.logInfo(
                    "Chronos backups: finished copying " + outCopied[0] + " files into cache.");
            if (attemptedSavingPause && worldController != null) {
                context.logInfo("Chronos backups: restoring automatic saves...");
                worldController.setWorldSavingDisabled(context.getServerHandle(), false);
                attemptedSavingPause = false;
            }

            if (Config.getPruneChunksEnabled()) {
                context.logInfo(
                        "Chronos backups: pruning snapshot and writing zip (streaming pruned region files)...");
                Files.deleteIfExists(zipOutputPath);
                RustPrunerBridge.pruneWorldToZip(
                        cacheSnapshotPath,
                        zipOutputPath,
                        Config.getPruneTimeRequirementSeconds(),
                        Config.getPruneMaxWorkerThreads());
            } else {
                context.logInfo("Chronos backups: snapshot pruning disabled by config.");
                context.logInfo("Chronos backups: writing zip archive...");
                zipSnapshot(cacheSnapshotPath, zipOutputPath);
            }

            backupFinishedSuccessfully = true;
            final String duration = formatBackupDurationNanos(backupStartNanos);
            context.logInfo("Chronos backup completed in " + duration + ": " + zipOutputPath);
            context.sendChat("Backup completed in " + duration + ".");
        } catch (InterruptedIOException e) {
            if (shutdownRequested) {
                context.logInfo("Chronos backup aborted (shutdown).");
            } else {
                context.logInfo("Chronos backup aborted (cancelled).");
                announceUserCancelInChat = true;
            }
            Thread.currentThread().interrupt();
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
                // Safety net for interrupted/failed copy paths before the normal early-restore point.
                worldController.setWorldSavingDisabled(context.getServerHandle(), false);
            }
            if (!backupFinishedSuccessfully) {
                try {
                    Files.deleteIfExists(zipOutputPath);
                } catch (IOException e) {
                    context.logError(
                            "Chronos backups: could not remove incomplete zip "
                                    + zipOutputPath
                                    + ": "
                                    + e.getMessage());
                }
            }
            boolean snapshotCacheRemoved = false;
            try {
                deleteDirectory(cacheSnapshotPath);
                snapshotCacheRemoved = true;
            } catch (IOException e) {
                context.logError(
                        "Chronos backup cleanup failed for "
                                + cacheSnapshotPath
                                + ": "
                                + e.getMessage());
            }
            if (announceUserCancelInChat) {
                if (snapshotCacheRemoved) {
                    context.sendChat(
                            "Backup cancelled. The run has fully stopped. Snapshot cache was cleared and any incomplete"
                                    + " zip archive was removed.");
                } else {
                    context.sendChat(
                            "Backup cancelled. The run has stopped, but removing the snapshot cache failed. Check the"
                                    + " server log.");
                }
            }
        }
        return backupFinishedSuccessfully;
    } finally {
        backupCancelRequested = false;
        backupRunActive.set(false);
    }
    }

    private Backupper() {
    }

    /**
     * Elapsed time since {@code startNanos} from {@link System#nanoTime()} for
     * log/chat messages.
     */
    private static String formatBackupDurationNanos(long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        double seconds = elapsedNanos / 1_000_000_000.0;
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.2f s", seconds);
        }
        long mins = (long) (seconds / 60);
        double remainderSeconds = seconds - mins * 60;
        return String.format(Locale.ROOT, "%d min %.1f s", mins, remainderSeconds);
    }

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

    private static void zipSnapshot(Path snapshotPath, Path zipOutputPath) throws IOException {
        Path zipRoot = snapshotPath.toAbsolutePath().normalize();
        Files.deleteIfExists(zipOutputPath);
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(zipOutputPath))) {
            Files.walkFileTree(
                    zipRoot,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (shouldAbortBackupWork()) {
                                throw new InterruptedIOException("Backup zip aborted during shutdown");
                            }
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

    public static void ShutdownBackupper() {
        shutdownRequested = true;

        // Stop scheduled backup checks and cancel queued run-now tasks
        Scheduler.ShutdownScheduler();

        // Delete the contents of the cache folder
        try {
            deleteDirectory(cacheFolder);
        } catch (IOException e) {
            LOG.severe("Failed to delete cache folder: " + e.getMessage());
        }
    }
}

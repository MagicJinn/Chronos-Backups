package com.magicjinn.chronos.core;

import java.io.IOException;
import java.io.InterruptedIOException;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.magicjinn.chronos.core.config.Config;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;

/**
 * Version-agnostic backup implementation.
 */
public final class Backupper {
    private static final Logger LOG = Logger.getLogger(Backupper.class.getName());

    private static final String CHRONOS_FOLDER_NAME = "chronos";
    private static final String CACHE_FOLDER_NAME = ".cache";
    private static final String SESSION_LOCK_FILE_NAME = "session.lock";
    /** Loader atomic-write temps — copied mid-rename causes NoSuchFileException on Windows. */
    private static final String NEOFORGE_ATOMIC_TMP_SUFFIX = ".neoforge-tmp";
    private static final String FABRIC_ATOMIC_TMP_SUFFIX = ".fabric-tmp";
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static Path chronosFolder;
    private static Path cacheFolder;

    /** Whether an in-flight copy / prune / zip work should stop. */
    private static volatile boolean shutdownRequested;

    /**
     * User/API requested abort of the current backup (without unloading the world).
     */
    private static volatile boolean backupCancelRequested;

    /**
     * Whether {@link #runBackup} is executing its main work (copy > prune > zip).
     */
    private static final AtomicBoolean backupRunActive = new AtomicBoolean(false);

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
     * Requests the in-flight backup (if any) to abort. Does nothing when no
     * backup is running.
     *
     * @return {@code true} if a backup was active and will receive the signal
     */
    public static boolean requestCancelInFlightBackup() {
        if (!backupRunActive.get()) {
            return false;
        }
        backupCancelRequested = true;
        return true;
    }

    /**
     * Clears the shutdown flag when a new world session starts (after a prior
     * stop).
     */
    static void clearShutdownRequest() {
        shutdownRequested = false;
        backupCancelRequested = false;
    }

    public static void InitializeBackupper() {
        chronosFolder = Core.RunningDirectory.resolve(CHRONOS_FOLDER_NAME);
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

    public static void runBackup(BackupRuntimeContext context) {
        if (context == null) {
            LOG.warning("Backupper skipped: runtime context is unavailable.");
            return;
        }
        if (isShutdownRequested()) {
            context.logInfo("Chronos backup skipped: shutdown in progress.");
            return;
        }

        Path worldPath = context.getWorldSaveRoot();
        if (!Files.isDirectory(worldPath)) {
            context.logError("Backupper skipped: world path does not exist -> " + worldPath);
            return;
        }

        backupRunActive.set(true);
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
                context.logInfo("Chronos backup: flushing world to disk...");
                worldController.saveAllWorldData(context.getServerHandle());
                context.logInfo("Chronos backup: pausing automatic saves...");
                worldController.setWorldSavingDisabled(context.getServerHandle(), true);
                attemptedSavingPause = true;
            }

            context.logInfo("Chronos backup: copying world into cache (this can take a while)...");
            copyWorldToCache(worldPath, cacheSnapshotPath, context);

            final int dataVersion = getDataVersionFromLevelData(cacheSnapshotPath);

            context.logInfo("Chronos backup: pruning snapshot...");
            Pruner.PruneMinecraftWorld(cacheSnapshotPath, dataVersion, Config.getPruneTimeRequirementSeconds());

            context.logInfo("Chronos backup: writing zip archive...");
            zipSnapshot(cacheSnapshotPath, zipOutputPath);

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
                context.logInfo("Chronos backup: restoring automatic saves...");
                worldController.setWorldSavingDisabled(context.getServerHandle(), false);
            }
            if (!backupFinishedSuccessfully) {
                try {
                    Files.deleteIfExists(zipOutputPath);
                } catch (IOException e) {
                    context.logError(
                            "Chronos backup: could not remove incomplete zip "
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
                            "Backup cancelled. The run has fully stopped; snapshot cache was cleared and any incomplete"
                                    + " zip archive was removed.");
                } else {
                    context.sendChat(
                            "Backup cancelled. The run has stopped, but removing the snapshot cache failed. Check the"
                                    + " server log.");
                }
            }
        }
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

    /**
     * {@code pattern} without {@code /} matches the last path segment; with {@code /}, matches a path prefix under the
     * world root (forward-slash form).
     */
    private static boolean isCopyBlacklisted(Path relativeToWorldRoot, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        Path rel = relativeToWorldRoot.normalize();
        String relSlash = rel.toString().replace('\\', '/');
        if (relSlash.isEmpty() || ".".equals(relSlash)) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            String p = pattern.trim();
            if (p.isEmpty()) {
                continue;
            }
            String pNorm = p.replace('\\', '/');
            if (pNorm.indexOf('/') >= 0) {
                if (relSlash.equals(pNorm) || relSlash.startsWith(pNorm + "/")) {
                    return true;
                }
            } else {
                Path fn = rel.getFileName();
                if (fn != null && p.equals(fn.toString())) {
                    return true;
                }
            }
        }
        return false;
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

        final List<String> copyBlacklist = Config.getCopyBlacklist();
        AtomicInteger fileCounter = new AtomicInteger();

        Files.walkFileTree(
                worldRoot,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        if (shouldAbortBackupWork()) {
                            throw new InterruptedIOException("Backup copy aborted during shutdown");
                        }
                        Path relative = relativizeToWorldRoot(worldRoot, dir);
                        if (isCopyBlacklisted(relative, copyBlacklist)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        Path destination = cacheSnapshotPath.resolve(relative);
                        Files.createDirectories(destination);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (shouldAbortBackupWork()) {
                            throw new InterruptedIOException("Backup copy aborted during shutdown");
                        }
                        if (SESSION_LOCK_FILE_NAME.equals(file.getFileName().toString())) {
                            return FileVisitResult.CONTINUE;
                        }
                        String leaf = file.getFileName().toString();
                        if (leaf.endsWith(NEOFORGE_ATOMIC_TMP_SUFFIX) || leaf.endsWith(FABRIC_ATOMIC_TMP_SUFFIX)) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path relative = relativizeToWorldRoot(worldRoot, file);
                        if (isCopyBlacklisted(relative, copyBlacklist)) {
                            return FileVisitResult.CONTINUE;
                        }
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

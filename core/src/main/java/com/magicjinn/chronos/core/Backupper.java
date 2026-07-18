package com.magicjinn.chronos.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.magicjinn.chronos.core.config.CompressionMethod;
import com.magicjinn.chronos.core.config.Config;
import com.magicjinn.cloudintegration.CloudSync;

/**
 * Version-agnostic backup implementation.
 * <p>
 * Minecraft-specific work (world flush, save pause/resume, chat) runs on the
 * server thread. Copy, prune, zip, and retention trimming run on a dedicated
 * worker thread while {@link #tickBackupTracker()} monitors progress each tick.
 */
public final class Backupper {
    private static final Logger LOG = Logger.getLogger(Backupper.class.getName());

    private static final String CACHE_FOLDER_NAME = ".cache";

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
     * Whether a backup run is in progress (from {@link #tryBeginBackup} until
     * {@link #tickBackupTracker} finishes it).
     */
    private static final AtomicBoolean backupRunActive = new AtomicBoolean(false);

    /**
     * Whether a {@link #speedtest} session is in progress (excludes per-backup
     * {@link #backupRunActive}).
     */
    private static final AtomicBoolean speedtestSessionActive = new AtomicBoolean(false);

    private static volatile InFlightBackup inFlightBackup;
    private static volatile SpeedtestSession activeSpeedtest;
    private static volatile PendingBackupBegin pendingBackupBegin;

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
     * Whether a backup run is currently active.
     */
    public static boolean isBackupRunActive() {
        return backupRunActive.get();
    }

    static boolean hasPendingBackupBegin() {
        return pendingBackupBegin != null;
    }

    /**
     * Whether a speedtest session is currently active.
     */
    public static boolean isSpeedtestSessionActive() {
        return speedtestSessionActive.get();
    }

    /**
     * Sets {@link #backupCancelRequested} when a backup run or a speedtest session
     * is active so in-flight work aborts and/or the speedtest loop stops.
     *
     * @return {@code true} if that cancel signal was applied
     */
    public static boolean requestCancelInFlightBackup() {
        if (backupRunActive.get() || speedtestSessionActive.get()) {
            backupCancelRequested = true;
            pendingBackupBegin = null;
            InFlightBackup run = inFlightBackup;
            if (run != null) {
                run.proceedAfterSavesRestored.countDown();
                Thread worker = run.workerThread;
                if (worker != null) {
                    worker.interrupt();
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Claims the speedtest session before queueing work on {@link Scheduler}'s
     * executor.
     *
     * @return {@code false} when a backup or speedtest is already active
     */
    public static boolean tryBeginSpeedtestSession() {
        if (backupRunActive.get()) {
            return false;
        }
        return speedtestSessionActive.compareAndSet(false, true);
    }

    /**
     * Starts a non-blocking speedtest session. Progress is driven by
     * {@link #tickSpeedtestSession()} on the server thread.
     */
    public static void beginSpeedtestSession(BackupRuntimeContext context, int seconds) {
        if (context == null) {
            LOG.warning("speedtest skipped: runtime context is unavailable.");
            speedtestSessionActive.set(false);
            return;
        }
        if (!speedtestSessionActive.get()) {
            LOG.warning("speedtest skipped: session was not claimed.");
            return;
        }
        backupCancelRequested = false;
        SpeedtestSession session = new SpeedtestSession();
        session.context = context;
        session.startNanos = System.nanoTime();
        session.endNanos = session.startNanos + seconds * 1_000_000_000L;
        activeSpeedtest = session;
    }

    /**
     * Advances an active speedtest session. Call from the server thread each tick.
     */
    public static void tickSpeedtestSession() {
        SpeedtestSession session = activeSpeedtest;
        if (session == null) {
            return;
        }

        if (backupCancelRequested || isShutdownRequested()) {
            finishSpeedtestSession(session, true);
            return;
        }

        if (session.waitingForBackup) {
            if (isBackupRunActive() || hasPendingBackupBegin()) {
                return;
            }
            if (session.lastBackupSucceeded) {
                session.backups++;
            }
            session.waitingForBackup = false;
            session.lastBackupSucceeded = false;
            if (session.expired || System.nanoTime() >= session.endNanos) {
                finishSpeedtestSession(session, false);
                return;
            }
        }

        if (System.nanoTime() >= session.endNanos) {
            if (isBackupRunActive() || hasPendingBackupBegin()) {
                session.expired = true;
                return;
            }
            finishSpeedtestSession(session, false);
            return;
        }

        if (!isBackupRunActive() && !hasPendingBackupBegin()) {
            tryBeginBackup(session.context);
        }
        if (isBackupRunActive() || hasPendingBackupBegin()) {
            session.waitingForBackup = true;
        }
    }

    private static void finishSpeedtestSession(SpeedtestSession session, boolean stoppedEarly) {
        activeSpeedtest = null;
        speedtestSessionActive.set(false);
        backupCancelRequested = false;

        long duration = System.nanoTime() - session.startNanos;
        String suffix = stoppedEarly ? " (stopped by /chronos cancel)" : "";
        String averages = session.backups > 0
                ? String.format(Locale.ROOT, ", average duration per backup: %.2f s",
                        (duration / 1_000_000_000.0) / session.backups)
                : "";
        String summary = "Speedtest finished" + suffix + " in " + formatBackupDurationNanos(session.startNanos) + ": "
                + session.backups
                + " successful backup(s)"
                + averages;
        session.context.logInfo(summary);
        session.context.sendChat(summary);
        // One catch-up sync after the burst, never per speedtest backup.
        CloudSync.requestSync();
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
        inFlightBackup = null;
        activeSpeedtest = null;
        pendingBackupBegin = null;
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
     * Starts a backup on the current (server) thread: validates paths, flushes the
     * world, pauses automatic saves, then hands copy/prune/zip work to a worker
     * thread. Completion is handled by {@link #tickBackupTracker()}.
     *
     * @return {@code true} when backup work was started
     */
    public static boolean tryBeginBackup(BackupRuntimeContext context) {
        if (context == null) {
            LOG.warning("Backupper skipped: runtime context is unavailable.");
            return false;
        }
        if (isShutdownRequested()) {
            context.logInfo("Chronos backup skipped: shutdown in progress.");
            return false;
        }

        PendingBackupBegin pending = pendingBackupBegin;
        if (pending != null) {
            if (pending.context != context) {
                pendingBackupBegin = null;
                pending = null;
            } else {
                return pending.tick();
            }
        }

        pending = new PendingBackupBegin(context);
        pendingBackupBegin = pending;
        return pending.tick();
    }

    private static void clearPendingBackupBegin() {
        pendingBackupBegin = null;
    }

    private static final class PendingBackupBegin {
        private enum Phase {
            FLUSH,
            RESOLVE,
            PAUSE,
            START
        }

        private final BackupRuntimeContext context;
        private Phase phase = Phase.FLUSH;
        private boolean flushLogged;
        private boolean pauseLogged;
        private SaveRootDiscovery.BackupScope scope;

        private PendingBackupBegin(BackupRuntimeContext context) {
            this.context = context;
        }

        private boolean tick() {
            BackupWorldController worldController = context.getWorldController();
            Object serverHandle = context.getServerHandle();

            while (true) {
                switch (phase) {
                    case FLUSH:
                        if (worldController != null && !worldController.prepareWorldFlush(serverHandle)) {
                            if (!flushLogged) {
                                context.logInfo("Chronos backups: flushing world to disk...");
                                flushLogged = true;
                            }
                            return false;
                        }
                        phase = Phase.RESOLVE;
                        break;
                    case RESOLVE:
                        try {
                            scope = SaveRootDiscovery.resolve(context);
                        } catch (IOException e) {
                            context.logError("Backupper skipped: " + e.getMessage());
                            clearPendingBackupBegin();
                            return false;
                        }
                        Path worldPath = scope.snapshotLayoutRoot();
                        if (!Files.isDirectory(worldPath)) {
                            context.logError("Backupper skipped: world path does not exist -> " + worldPath);
                            clearPendingBackupBegin();
                            return false;
                        }
                        if (scope.discoveredByScan()) {
                            context.logInfo(
                                    "Chronos backups: discovered "
                                            + scope.saveContainers().size()
                                            + " save container(s) under run directory.");
                        }
                        phase = Phase.PAUSE;
                        break;
                    case PAUSE:
                        if (worldController != null) {
                            if (!pauseLogged) {
                                context.logInfo("Chronos backups: pausing automatic saves...");
                                pauseLogged = true;
                            }
                            if (!worldController.preparePauseSaves(serverHandle, true)) {
                                return false;
                            }
                        }
                        phase = Phase.START;
                        break;
                    case START:
                        if (!backupRunActive.compareAndSet(false, true)) {
                            String message = "Backup skipped: another backup is already running.";
                            LOG.warning(message);
                            context.sendChat(message);
                            clearPendingBackupBegin();
                            return false;
                        }
                        if (backupCancelRequested) {
                            context.logInfo("Chronos backup skipped: cancel in progress.");
                            releaseBackupRunClaim();
                            clearPendingBackupBegin();
                            return false;
                        }
                        clearPendingBackupBegin();
                        return startBackupWorker(context, scope, worldController, serverHandle);
                    default:
                        throw new IllegalStateException("Unknown backup prep phase: " + phase);
                }
            }
        }
    }

    private static boolean startBackupWorker(
            BackupRuntimeContext context,
            SaveRootDiscovery.BackupScope scope,
            BackupWorldController worldController,
            Object serverHandle) {
        Path worldPath = scope.snapshotLayoutRoot();

        final CompressionMethod compressionMethod = Config.getCompressionMethod();
        final String safeWorldDirName = ChronosBackupArtifacts.sanitizeWorldDirName(context.getWorldName());
        final Path worldBackupDir = chronosFolder.resolve(safeWorldDirName);
        // Milliseconds keep rapid successive backups (e.g. speedtests) distinct.
        final String backupId = ChronosBackupArtifacts.newBackupId(context.getWorldName());

        final Path zipOutputPath;
        final Path cacheSnapshotPath;
        final Path folderOutputPath;
        if (compressionMethod == CompressionMethod.ZIP) {
            cacheSnapshotPath = cacheFolder.resolve(backupId);
            zipOutputPath = worldBackupDir.resolve(ChronosBackupArtifacts.zipFileName(backupId));
            folderOutputPath = null;
        } else {
            cacheSnapshotPath = null;
            zipOutputPath = null;
            folderOutputPath = worldBackupDir.resolve(backupId);
        }

        InFlightBackup run = new InFlightBackup();
        run.context = context;
        run.compressionMethod = compressionMethod;
        run.worldBackupDir = worldBackupDir;
        run.zipOutputPath = zipOutputPath;
        run.cacheSnapshotPath = cacheSnapshotPath;
        run.folderOutputPath = folderOutputPath;
        run.worldPath = worldPath;
        run.saveContainers = scope.saveContainers();
        run.worldController = worldController;
        run.serverHandle = serverHandle;
        run.backupStartNanos = System.nanoTime();

        try {
            Files.createDirectories(worldBackupDir);
            Files.createDirectories(cacheFolder);

            context.logInfo("Chronos backup started for world " + context.getWorldName() + " -> " + worldPath);
            context.sendChat("Backup started for " + context.getWorldName());

            if (run.worldController != null) {
                run.attemptedSavingPause = true;
            }

            inFlightBackup = run;
            Thread worker = new Thread(() -> runHeavyBackupWork(run), "Chronos-Backup-Worker");
            run.workerThread = worker;
            worker.start();
            return true;
        } catch (Throwable t) {
            finalizeInFlightBackup(run, t);
            return false;
        }
    }

    /**
     * Monitors the active backup worker from the server thread. Restores automatic
     * saves after the cache copy finishes, then completes the run when the worker
     * exits.
     */
    public static void tickBackupTracker() {
        InFlightBackup run = inFlightBackup;
        if (run == null) {
            return;
        }

        if (run.copyComplete && run.attemptedSavingPause && !run.savesRestoredOnMainThread) {
            run.context.logInfo("Chronos backups: restoring automatic saves...");
            if (run.worldController != null
                    && !run.worldController.preparePauseSaves(run.serverHandle, false)) {
                return;
            }
            run.attemptedSavingPause = false;
            run.savesRestoredOnMainThread = true;
            run.proceedAfterSavesRestored.countDown();
        }

        if (!run.workerFinished) {
            return;
        }

        finalizeInFlightBackup(run, run.workerError);
    }

    private static void pruneSnapshotSaveRoots(InFlightBackup run, Path snapshotRoot) throws IOException {
        int pruneSeconds = Config.getPruneTimeRequirementSeconds();
        int pruneThreads = Config.getPruneMaxWorkerThreads();
        for (Path sourceRoot : run.saveContainers) {
            Path snapshotSaveRoot = SaveRootDiscovery.snapshotPathForSourceRoot(
                    run.worldPath, sourceRoot, snapshotRoot);
            RustPrunerBridge.pruneWorld(
                    snapshotSaveRoot,
                    pruneSeconds,
                    pruneThreads);
        }
    }

    private static void runHeavyBackupWork(InFlightBackup run) {
        try {
            Path layoutRootAbs = run.worldPath.toAbsolutePath().normalize();

            if (run.compressionMethod == CompressionMethod.ZIP) {
                run.context.logInfo("Chronos backups: copying world into cache (this can take a while)...");
                assertCacheOutsideSaveContainers(run.saveContainers, layoutRootAbs, run.cacheSnapshotPath);
                deleteDirectory(run.cacheSnapshotPath);
                Files.createDirectories(run.cacheSnapshotPath);
                int[] outCopied = new int[1];
                copySaveContainersToSnapshot(
                        layoutRootAbs,
                        run.saveContainers,
                        run.cacheSnapshotPath,
                        Config.getCopyBlacklist(),
                        Config.getPruneMaxWorkerThreads(),
                        outCopied);
                run.context.logInfo(
                        "Chronos backups: finished copying " + outCopied[0] + " files into cache.");
            } else {
                run.context.logInfo(
                        "Chronos backups: copying world to backup folder...");
                assertCacheOutsideSaveContainers(run.saveContainers, layoutRootAbs, run.folderOutputPath);
                deleteDirectory(run.folderOutputPath);
                Files.createDirectories(run.folderOutputPath);
                int[] outCopied = new int[1];
                copySaveContainersToSnapshot(
                        layoutRootAbs,
                        run.saveContainers,
                        run.folderOutputPath,
                        Config.getCopyBlacklist(),
                        Config.getPruneMaxWorkerThreads(),
                        outCopied);
                run.context.logInfo(
                        "Chronos backups: finished copying " + outCopied[0] + " files into backup folder.");
            }

            run.copyComplete = true;

            try {
                run.proceedAfterSavesRestored.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Backup worker interrupted waiting for save restore");
            }

            if (shouldAbortBackupWork()) {
                throw new InterruptedIOException("Backup aborted");
            }

            Path snapshotRoot = run.compressionMethod == CompressionMethod.ZIP
                    ? run.cacheSnapshotPath
                    : run.folderOutputPath;
            if (run.compressionMethod == CompressionMethod.ZIP) {
                if (Config.getPruneChunksEnabled()) {
                    run.context.logInfo(
                            "Chronos backups: pruning snapshot and writing zip...");
                    Files.deleteIfExists(run.zipOutputPath);
                    boolean singleSaveContainer = run.saveContainers.size() == 1
                            && SaveRootDiscovery.snapshotPathForSourceRoot(
                                            run.worldPath, run.saveContainers.get(0), snapshotRoot)
                                    .equals(snapshotRoot);
                    if (singleSaveContainer) {
                        RustPrunerBridge.pruneWorldToZip(
                                snapshotRoot,
                                run.zipOutputPath,
                                Config.getPruneTimeRequirementSeconds(),
                                Config.getPruneMaxWorkerThreads());
                    } else {
                        pruneSnapshotSaveRoots(run, snapshotRoot);
                        zipSnapshot(snapshotRoot, run.zipOutputPath);
                    }
                } else {
                    run.context.logInfo("Chronos backups: snapshot pruning disabled by config.");
                    run.context.logInfo("Chronos backups: writing zip archive...");
                    zipSnapshot(run.cacheSnapshotPath, run.zipOutputPath);
                }
            } else if (Config.getPruneChunksEnabled()) {
                run.context.logInfo("Chronos backups: pruning snapshot in backup folder...");
                pruneSnapshotSaveRoots(run, snapshotRoot);
            } else {
                run.context.logInfo("Chronos backups: snapshot pruning disabled by config.");
            }

            run.backupFinishedSuccessfully = true;
            trimOldBackupsAfterNewSuccess(run.worldBackupDir, Config.getMaxStoredBackups(), run.context);
        } catch (InterruptedIOException e) {
            if (shutdownRequested) {
                run.context.logInfo("Chronos backup aborted (shutdown).");
            } else {
                run.context.logInfo("Chronos backup aborted (cancelled).");
                run.announceUserCancelInChat = true;
            }
        } catch (Throwable t) {
            run.workerError = t;
        } finally {
            run.workerFinished = true;
            run.proceedAfterSavesRestored.countDown();
        }
    }

    private static void finalizeInFlightBackup(InFlightBackup run, Throwable startupFailure) {
        SpeedtestSession speedtest = activeSpeedtest;
        if (speedtest != null && speedtest.waitingForBackup) {
            speedtest.lastBackupSucceeded = run.backupFinishedSuccessfully;
        }

        boolean backupFinishedSuccessfully = run.backupFinishedSuccessfully;
        boolean announceUserCancelInChat = run.announceUserCancelInChat;
        try {
            if (startupFailure != null && !run.workerFinished) {
                String detail = startupFailure.getMessage();
                if (detail == null || detail.isEmpty()) {
                    detail = startupFailure.getClass().getName();
                }
                run.context.logError("Chronos backup failed: " + detail);
                run.context.sendChat("Backup failed. Check server logs for details.");
                startupFailure.printStackTrace();
                return;
            }

            if (run.workerError != null) {
                Throwable t = run.workerError;
                String detail = t.getMessage();
                if (detail == null || detail.isEmpty()) {
                    detail = t.getClass().getName();
                }
                run.context.logError("Chronos backup failed: " + detail);
                run.context.sendChat("Backup failed. Check server logs for details.");
                t.printStackTrace();
                return;
            }

            if (backupFinishedSuccessfully) {
                final String duration = formatBackupDurationNanos(run.backupStartNanos);
                if (run.compressionMethod == CompressionMethod.ZIP) {
                    run.context.logInfo("Chronos backup completed in " + duration + ": " + run.zipOutputPath);
                } else {
                    run.context.logInfo("Chronos backup completed in " + duration + ": " + run.folderOutputPath);
                }
                run.context.sendChat("Backup completed in " + duration + ".");
                // Skip sync if a speedtest is in progress
                if (!isSpeedtestSessionActive()) {
                    CloudSync.requestSync();
                }
            }
        } finally {
            if (run.attemptedSavingPause && run.worldController != null) {
                run.worldController.setWorldSavingDisabled(run.serverHandle, false);
            }
            if (!backupFinishedSuccessfully) {
                if (run.zipOutputPath != null) {
                    try {
                        Files.deleteIfExists(run.zipOutputPath);
                    } catch (IOException e) {
                        run.context.logError(
                                "Chronos backups: could not remove incomplete zip "
                                        + run.zipOutputPath
                                        + ": "
                                        + e.getMessage());
                    }
                }
                if (run.folderOutputPath != null) {
                    try {
                        deleteDirectory(run.folderOutputPath);
                    } catch (IOException e) {
                        run.context.logError(
                                "Chronos backups: could not remove incomplete folder backup "
                                        + run.folderOutputPath
                                        + ": "
                                        + e.getMessage());
                    }
                }
            }
            boolean snapshotCacheRemoved = true;
            if (run.cacheSnapshotPath != null) {
                try {
                    deleteDirectory(run.cacheSnapshotPath);
                } catch (IOException e) {
                    snapshotCacheRemoved = false;
                    run.context.logError(
                            "Chronos backup cleanup failed for "
                                    + run.cacheSnapshotPath
                                    + ": "
                                    + e.getMessage());
                }
            }
            if (announceUserCancelInChat) {
                if (snapshotCacheRemoved) {
                    run.context.sendChat(
                            "Backup cancelled. The run has fully stopped. Working snapshot files were cleared and"
                                    + " any incomplete backup output was removed.");
                } else {
                    run.context.sendChat(
                            "Backup cancelled. The run has stopped, but cleaning up temporary snapshot files"
                                    + " failed. Check the server log.");
                }
            }

            inFlightBackup = null;
            releaseBackupRunClaim();
        }
    }

    private static void releaseBackupRunClaim() {
        backupCancelRequested = false;
        backupRunActive.set(false);
    }

    private Backupper() {
    }

    private static final class InFlightBackup {
        BackupRuntimeContext context;
        CompressionMethod compressionMethod;
        Path worldBackupDir;
        Path zipOutputPath;
        Path cacheSnapshotPath;
        Path folderOutputPath;
        Path worldPath;
        List<Path> saveContainers = Collections.emptyList();
        BackupWorldController worldController;
        Object serverHandle;
        long backupStartNanos;
        boolean attemptedSavingPause;
        volatile boolean copyComplete;
        volatile boolean savesRestoredOnMainThread;
        final CountDownLatch proceedAfterSavesRestored = new CountDownLatch(1);
        volatile boolean workerFinished;
        volatile boolean backupFinishedSuccessfully;
        volatile boolean announceUserCancelInChat;
        volatile Throwable workerError;
        Thread workerThread;
    }

    private static final class SpeedtestSession {
        BackupRuntimeContext context;
        long startNanos;
        long endNanos;
        int backups;
        boolean waitingForBackup;
        boolean lastBackupSucceeded;
        boolean expired;
    }

    /**
     * After a successful backup, deletes oldest zip/folder snapshots in
     * {@code worldBackupDir} if
     * more than {@code maxStored} remain. {@code maxStored} < 1 disables trimming.
     */
    private static void trimOldBackupsAfterNewSuccess(
            Path worldBackupDir,
            int maxStored,
            BackupRuntimeContext context) {
        if (maxStored < 1 || worldBackupDir == null || !Files.isDirectory(worldBackupDir)) {
            return;
        }
        try {
            List<Path> backups = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(worldBackupDir)) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    // Only Chronos-named artifacts. Never delete unrelated user files.
                    if (!ChronosBackupArtifacts.isChronosBackupName(name)) {
                        continue;
                    }
                    if (Files.isRegularFile(p) && name.endsWith(".zip")) {
                        backups.add(p);
                    } else if (Files.isDirectory(p)) {
                        backups.add(p);
                    }
                }
            }
            if (backups.size() <= maxStored) {
                return;
            }
            backups.sort(Comparator.comparingLong((Path p) -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return Long.MIN_VALUE;
                }
            }).reversed());
            for (int i = maxStored; i < backups.size(); i++) {
                Path victim = backups.get(i);
                try {
                    if (Files.isDirectory(victim)) {
                        deleteDirectory(victim);
                    } else {
                        Files.deleteIfExists(victim);
                    }
                } catch (IOException ex) {
                    context.logError(
                            "Chronos backups: could not delete old backup " + victim + ": " + ex.getMessage());
                }
            }
        } catch (IOException e) {
            context.logError(
                    "Chronos backups: could not trim old backups in " + worldBackupDir + ": " + e.getMessage());
        }
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
     * {@link Path#relativize} throws {@link IllegalArgumentException} when roots
     * differ (common on Windows if the walk paths are not normalized the same way
     * as the world root). Fall back to URI relativization used by other backup
     * mods.
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

    private static void copySaveContainersToSnapshot(
            Path layoutRoot,
            List<Path> saveContainers,
            Path snapshotRoot,
            List<String> copyBlacklist,
            int maxCopyWorkerThreads,
            int[] outCopied)
            throws IOException {
        List<Path> sources = saveContainersToCopy(layoutRoot, saveContainers);
        int totalCopied = 0;
        for (Path sourceRoot : sources) {
            Path dest = SaveRootDiscovery.snapshotPathForSourceRoot(layoutRoot, sourceRoot, snapshotRoot);
            Files.createDirectories(dest);
            int[] part = new int[1];
            RustPrunerBridge.copyWorldToCache(
                    sourceRoot, dest, copyBlacklist, maxCopyWorkerThreads, part);
            totalCopied += part[0];
        }
        outCopied[0] = totalCopied;
    }

    private static List<Path> saveContainersToCopy(Path layoutRoot, List<Path> saveContainers) {
        if (saveContainers == null || saveContainers.isEmpty()) {
            return Collections.singletonList(layoutRoot);
        }
        if (saveContainers.size() == 1) {
            return Collections.singletonList(saveContainers.get(0));
        }
        return saveContainers;
    }

    private static void assertCacheOutsideSaveContainers(
            List<Path> saveContainers,
            Path layoutRoot,
            Path cacheSnapshotPath)
            throws IOException {
        Path cache = cacheSnapshotPath.toAbsolutePath().normalize();
        for (Path sourceRoot : saveContainersToCopy(layoutRoot, saveContainers)) {
            Path source = sourceRoot.toAbsolutePath().normalize();
            if (cache.startsWith(source)) {
                throw new IOException(
                        "Refusing backup: chronos cache folder would live inside the world directory "
                                + "(game/run directory resolution is wrong for this setup). world="
                                + source
                                + " cache="
                                + cache);
            }
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

        InFlightBackup run = inFlightBackup;
        if (run != null) {
            run.proceedAfterSavesRestored.countDown();
            Thread worker = run.workerThread;
            if (worker != null) {
                worker.interrupt();
            }
        }

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

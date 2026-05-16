package com.magicjinn.chronos.core;

import java.time.Instant;
import java.util.logging.Logger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.magicjinn.chronos.core.config.Config;

/**
 * Schedules backup work and delegates to {@link Backupper}.
 */
public final class Scheduler {
    /** Result of enqueueing work on the backup scheduler executor. */
    public enum EnqueueResult {
        /** A worker will run the requested task soon. */
        QUEUED,
        /** Scheduler has no world context (server not ready or shut down). */
        NO_RUNTIME,
        /** A backup or speedtest is already running. */
        ALREADY_RUNNING
    }

    private static final Logger LOG = Logger.getLogger(Scheduler.class.getName());

    private static ScheduledExecutorService backupScheduler = Executors.newScheduledThreadPool(1);
    private static volatile BackupRuntimeContext runtimeContext;

    private static final long BACKUP_INITIAL_DELAY_SECONDS = 10;
    private static final long BACKUP_CHECK_INTERVAL_SECONDS = 1;
    // Set to current time, to prevent immediate backup on startup
    private static long secondsSinceLastBackup = getCurrentTimeSeconds();

    /** Active world/runtime context, or {@code null} when the scheduler has not been started. */
    public static BackupRuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    public static void InitializeScheduler(BackupRuntimeContext context) {

        Backupper.clearShutdownRequest();

        // Just in case the scheduler is already running, shutdown it
        ShutdownScheduler();

        runtimeContext = context; // Store the context for use in the runnable task
        backupScheduler = Executors.newScheduledThreadPool(1);

        Runnable backupTask = () -> {
            try {
                if (!Config.getScheduleBackups())
                    return;
                if (Backupper.isSpeedtestSessionActive()) {
                    return;
                }
                long currentTimeSeconds = getCurrentTimeSeconds();
                long interval = Config.getBackupIntervalSeconds();
                if (currentTimeSeconds - secondsSinceLastBackup >= interval) {
                    secondsSinceLastBackup = currentTimeSeconds;
                    Backupper.runBackup(runtimeContext);
                }
            } catch (Exception e) {
                logError("Error scheduling backup: " + e.getMessage());
                e.printStackTrace();
            }
        };

        // Check whether we should run a backup every second. This is handy so the user
        // could change backup interval on the fly
        backupScheduler.scheduleAtFixedRate(backupTask, BACKUP_INITIAL_DELAY_SECONDS, BACKUP_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        context.logInfo("Scheduler is ready and on standby...");
    }

    public static void ShutdownScheduler() {
        runtimeContext = null;
        if (backupScheduler == null || backupScheduler.isShutdown()) {
            return;
        }
        backupScheduler.shutdown();
        try {
            if (!backupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                backupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            backupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Queues a manual backup unless none is active and runtime exists.
     * <p>
     * Checks {@link Backupper#isBackupRunActive()} so a second manual request is
     * cancelled instead of sitting in the queue until the current backup finishes.
     */
    public static EnqueueResult tryEnqueueManualBackup() {
        BackupRuntimeContext context = runtimeContext;
        if (context == null) {
            return EnqueueResult.NO_RUNTIME;
        }
        if (Backupper.isBackupRunActive() || Backupper.isSpeedtestSessionActive()) {
            return EnqueueResult.ALREADY_RUNNING;
        }
        secondsSinceLastBackup = getCurrentTimeSeconds();
        backupScheduler.execute(() -> Backupper.runBackup(context));
        return EnqueueResult.QUEUED;
    }

    /**
     * Queues a speedtest on the same single-thread backup executor as manual and scheduled
     * backups so only one backup/speedtest session runs at a time.
     */
    public static EnqueueResult tryEnqueueSpeedtest(int seconds) {
        BackupRuntimeContext context = runtimeContext;
        if (context == null) {
            return EnqueueResult.NO_RUNTIME;
        }
        if (Backupper.isBackupRunActive() || Backupper.isSpeedtestSessionActive()) {
            return EnqueueResult.ALREADY_RUNNING;
        }
        if (!Backupper.tryBeginSpeedtestSession()) {
            return EnqueueResult.ALREADY_RUNNING;
        }
        backupScheduler.execute(() -> Backupper.runSpeedtestSession(context, seconds));
        return EnqueueResult.QUEUED;
    }

    private Scheduler() {}

    private static long getCurrentTimeSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static void logError(String message) {
        if (runtimeContext != null) {
            runtimeContext.logError(message);
            return;
        }
        LOG.severe(message);
    }
}
package com.magicjinn.chronos.core;

import java.time.Instant;
import java.util.logging.Logger;
import com.magicjinn.chronos.core.config.Config;

/**
 * Schedules backup work on the server thread and delegates to
 * {@link Backupper}.
 */

public final class Scheduler {
    /** Result of starting backup work on the server thread. */
    public enum EnqueueResult {
        /** Backup or speedtest was started. */
        QUEUED,

        /** Scheduler has no world context (server not ready or shut down). */
        NO_RUNTIME,

        /** A backup or speedtest is already running. */
        ALREADY_RUNNING
    }

    private static final Logger LOG = Logger.getLogger(Scheduler.class.getName());

    private static BackupRuntimeContext runtimeContext;

    // Set to current time, to prevent immediate backup on startup
    private static long secondsSinceLastBackup = getCurrentTimeSeconds();

    /**
     * Active world/runtime context, or {@code null} when the scheduler has not been
     * started.
     */
    public static BackupRuntimeContext getRuntimeContext() {
        if (runtimeContext == null)
            throw new IllegalStateException("Runtime context is null");
        return runtimeContext;
    }

    public static void InitializeScheduler(BackupRuntimeContext context) {

        Backupper.clearShutdownRequest();

        ShutdownScheduler();

        runtimeContext = context;

        context.logInfo("Scheduler is ready and on standby...");
    }

    public static void tickScheduler() {
        try {
            if (!Config.getScheduleBackups())
                return;

            if (Backupper.isSpeedtestSessionActive())
                return;

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
    }

    public static void ShutdownScheduler() {
        runtimeContext = null;
    }

    /**
     * Runs a manual backup on the current thread unless none is active and runtime
     * exists.
     * <p>
     * Checks {@link Backupper#isBackupRunActive()} so a second manual request is
     * rejected instead of overlapping with the current backup.
     */
    public static EnqueueResult tryEnqueueManualBackup() {
        BackupRuntimeContext context = runtimeContext;
        if (context == null)
            return EnqueueResult.NO_RUNTIME;

        if (Backupper.isBackupRunActive() || Backupper.isSpeedtestSessionActive())
            return EnqueueResult.ALREADY_RUNNING;

        secondsSinceLastBackup = getCurrentTimeSeconds();
        context.sendChat("Manual backup started.");
        Backupper.runBackup(context);

        return EnqueueResult.QUEUED;
    }

    /**
     * Runs a speedtest on the current thread so only one backup/speedtest session
     * runs at a time.
     */
    public static EnqueueResult tryEnqueueSpeedtest(int seconds) {
        BackupRuntimeContext context = runtimeContext;

        if (context == null)
            return EnqueueResult.NO_RUNTIME;

        if (Backupper.isBackupRunActive() || Backupper.isSpeedtestSessionActive())
            return EnqueueResult.ALREADY_RUNNING;

        if (!Backupper.tryBeginSpeedtestSession())
            return EnqueueResult.ALREADY_RUNNING;

        context.sendChat("Speedtest started for " + seconds + " s.");
        Backupper.runSpeedtestSession(context, seconds);

        return EnqueueResult.QUEUED;
    }

    private Scheduler() {
    }

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

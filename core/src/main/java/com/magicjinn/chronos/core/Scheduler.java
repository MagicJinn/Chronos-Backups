package com.magicjinn.chronos.core;

import java.time.Instant;
import java.util.logging.Logger;
import com.magicjinn.chronos.core.config.Config;

/**
 * Schedules backup work on the server thread. Heavy copy/prune/zip work runs on
 * a worker thread. {@link Backupper#tickBackupTracker()} monitors it each tick.
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
            Backupper.tickBackupTracker();
            Backupper.tickSpeedtestSession();

            BackupRuntimeContext context = runtimeContext;
            if (context != null && Backupper.hasPendingBackupBegin()) {
                Backupper.tryBeginBackup(context);
            }

            if (!Config.getScheduleBackups())
                return;

            if (Backupper.isSpeedtestSessionActive())
                return;

            if (Backupper.isBackupRunActive())
                return;

            long currentTimeSeconds = getCurrentTimeSeconds();
            long interval = Config.getBackupIntervalSeconds();

            if (currentTimeSeconds - secondsSinceLastBackup >= interval) {
                secondsSinceLastBackup = currentTimeSeconds;
                Backupper.tryBeginBackup(runtimeContext);
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
        Backupper.tryBeginBackup(context);

        return EnqueueResult.QUEUED;
    }

    /**
     * Starts a speedtest session that runs on the server tick loop so only one
     * backup/speedtest session runs at a time.
     */
    public static EnqueueResult tryEnqueueSpeedtest(int seconds) {
        BackupRuntimeContext context = runtimeContext;

        if (context == null)
            return EnqueueResult.NO_RUNTIME;

        if (Backupper.isBackupRunActive() || Backupper.isSpeedtestSessionActive())
            return EnqueueResult.ALREADY_RUNNING;

        // Send chat message before claiming the session (in case of a crash)
        context.sendChat("Speedtest started for " + seconds + " s.");

        if (!Backupper.tryBeginSpeedtestSession())
            return EnqueueResult.ALREADY_RUNNING;

        Backupper.beginSpeedtestSession(context, seconds);

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

package com.magicjinn.chronos.core;

import java.time.Instant;
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

    private static BackupRuntimeContext runtimeContext;

    /**
     * Cleared on {@link #ShutdownScheduler()} so each world session logs readiness
     * once.
     */
    private static boolean readyLogged;

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

    /**
     * Active world/runtime context, or {@code null} when no world session is
     * running. Prefer this from cloud setup code that may run before a world loads.
     */
    public static BackupRuntimeContext tryGetRuntimeContext() {
        return runtimeContext;
    }

    public static void InitializeScheduler(BackupRuntimeContext context) {

        Backupper.clearShutdownRequest();

        ShutdownScheduler();

        runtimeContext = context;
    }

    public static void tickScheduler() {
        try {
            BackupRuntimeContext context = runtimeContext;
            if (context != null && !readyLogged) {
                readyLogged = true;
                context.logInfo("Scheduler is ready and on standby...");
            }

            Backupper.tickBackupTracker();
            Backupper.tickSpeedtestSession();

            if (context != null && Backupper.hasPendingBackupBegin())
                Backupper.tryBeginBackup(context);

            if (runtimeContext == null || !Config.getScheduleBackups())
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
        } finally {
            // Deliver chat queued during this tick (e.g. "Backup started").
            Backupper.flushPendingServerChats();
        }
    }

    public static void ShutdownScheduler() {
        runtimeContext = null;
        readyLogged = false;
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

        ChronosLogger.error(message);
    }
}

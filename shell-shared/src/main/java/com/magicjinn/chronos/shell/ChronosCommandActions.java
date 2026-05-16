package com.magicjinn.chronos.shell;

import com.magicjinn.chronos.core.Backupper;
import com.magicjinn.chronos.core.BackupRuntimeContext;
import com.magicjinn.chronos.core.Scheduler;
import com.magicjinn.chronos.core.Scheduler.EnqueueResult;

/**
 * Shared implementation for {@code /chronos} subcommands. Loader modules register Brigadier (or
 * legacy Forge) trees that delegate here.
 */
public final class ChronosCommandActions {
    /** Short hint for legacy {@code ICommand} usage lines. */
    public static final String USAGE_LINE = "/"
            + ChronosCommandLiterals.ROOT
            + " "
            + ChronosCommandLiterals.BACKUP
            + " | /"
            + ChronosCommandLiterals.ROOT
            + " "
            + ChronosCommandLiterals.CANCEL
            + " | /"
            + ChronosCommandLiterals.ROOT
            + " "
            + ChronosCommandLiterals.SPEEDTEST
            + " <s>";

    private ChronosCommandActions() {}

    public static String messageManualBackupStarted() {
        return BackupRuntimeContext.CHAT_PREFIX + "Manual backup started.";
    }

    public static String messageRuntimeInactive() {
        return BackupRuntimeContext.CHAT_PREFIX + "Backup runtime is not active yet.";
    }

    public static String messageCancelNothingRunning() {
        return BackupRuntimeContext.CHAT_PREFIX + "No backup is in progress.";
    }

    public static String messageManualBackupAlreadyRunning() {
        return BackupRuntimeContext.CHAT_PREFIX + "A backup is already in progress. Request was not queued.";
    }

    public static String messageSpeedtestStarted(int seconds) {
        return BackupRuntimeContext.CHAT_PREFIX + "Speedtest started for " + seconds + " s.";
    }

    public static String messageSpeedtestAlreadyRunning() {
        return BackupRuntimeContext.CHAT_PREFIX
                + "A Chronos speedtest or backup is already running.";
    }

    public static String messageChronosUsage() {
        return BackupRuntimeContext.CHAT_PREFIX + "Usage: " + USAGE_LINE;
    }

    public static String messageUnknownSubcommand(String sub) {
        return BackupRuntimeContext.CHAT_PREFIX + "Unknown subcommand \"" + sub + "\". " + USAGE_LINE;
    }

    public static String messageSpeedtestUsage() {
        return BackupRuntimeContext.CHAT_PREFIX
                + "Usage: /"
                + ChronosCommandLiterals.ROOT
                + " "
                + ChronosCommandLiterals.SPEEDTEST
                + " <s>";
    }

    public static String messageSpeedtestInvalidInteger(String token) {
        return BackupRuntimeContext.CHAT_PREFIX + "Not an integer: \"" + token + "\".";
    }

    /** Queues a manual backup when the world scheduler is active and no backup is running. */
    public static EnqueueResult tryStartManualBackup() {
        return Scheduler.tryEnqueueManualBackup();
    }

    /** Signals the current in-flight backup to stop, no-op if none is running. */
    public static boolean requestCancelInFlightBackup() {
        return Backupper.requestCancelInFlightBackup();
    }

    /**
     * {@code s} is seconds (integer) from {@code /chronos speedtest <s>}.
     */
    public static EnqueueResult tryStartSpeedtest(int s) {
        return Scheduler.tryEnqueueSpeedtest(s);
    }
}

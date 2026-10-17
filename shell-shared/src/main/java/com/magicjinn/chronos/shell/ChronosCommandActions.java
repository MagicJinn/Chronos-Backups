package com.magicjinn.chronos.shell;

import com.magicjinn.chronos.core.Backupper;
import com.magicjinn.chronos.core.Scheduler;
import com.magicjinn.chronos.core.Scheduler.EnqueueResult;
import com.magicjinn.chronos.core.config.Config;

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

    public static String messageRuntimeInactive() {
        return Config.getChatPrefix() + "Backup runtime is not active yet.";
    }

    public static String messageCancelNothingRunning() {
        return Config.getChatPrefix() + "No backup is in progress.";
    }

    public static String messageManualBackupAlreadyRunning() {
        return Config.getChatPrefix() + "A backup is already in progress. Request was not queued.";
    }

    public static String messageSpeedtestAlreadyRunning() {
        return Config.getChatPrefix()
                + "A Chronos speedtest or backup is already running.";
    }

    public static String messageChronosUsage() {
        return Config.getChatPrefix() + "Usage: " + USAGE_LINE;
    }

    public static String messageUnknownSubcommand(String sub) {
        return Config.getChatPrefix() + "Unknown subcommand \"" + sub + "\". " + USAGE_LINE;
    }

    public static String messageSpeedtestUsage() {
        return Config.getChatPrefix()
                + "Usage: /"
                + ChronosCommandLiterals.ROOT
                + " "
                + ChronosCommandLiterals.SPEEDTEST
                + " <s>";
    }

    public static String messageSpeedtestInvalidInteger(String token) {
        return Config.getChatPrefix() + "Not an integer: \"" + token + "\".";
    }

    /** Queues a manual backup when the world scheduler is active and no backup is running. */
    public static EnqueueResult tryStartManualBackup() {
        Backupper.tickBackupTracker();
        try {
            return Scheduler.tryEnqueueManualBackup();
        } finally {
            Backupper.flushPendingServerChats();
        }
    }

    /** Signals the current in-flight backup to stop, no-op if none is running. */
    public static boolean requestCancelInFlightBackup() {
        Backupper.tickBackupTracker();
        try {
            return Backupper.requestCancelInFlightBackup();
        } finally {
            Backupper.flushPendingServerChats();
        }
    }

    /**
     * {@code s} is seconds (integer) from {@code /chronos speedtest <s>}.
     */
    public static EnqueueResult tryStartSpeedtest(int s) {
        Backupper.tickBackupTracker();
        try {
            return Scheduler.tryEnqueueSpeedtest(s);
        } finally {
            Backupper.flushPendingServerChats();
        }
    }
}

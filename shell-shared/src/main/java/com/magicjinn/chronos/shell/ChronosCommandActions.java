package com.magicjinn.chronos.shell;

import com.magicjinn.chronos.core.Backupper;
import com.magicjinn.chronos.core.Core;
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
            + " <s> | /"
            + ChronosCommandLiterals.ROOT
            + " "
            + ChronosCommandLiterals.RELOAD;

    private ChronosCommandActions() {}

    private static String chatPrefix() {
        return Config.live().chatPrefix;
    }

    public static String messageRuntimeInactive() {
        return chatPrefix() + "Backup runtime is not active yet.";
    }

    public static String messageCancelNothingRunning() {
        return chatPrefix() + "No backup is in progress.";
    }

    public static String messageManualBackupAlreadyRunning() {
        return chatPrefix() + "A backup is already in progress. Request was not queued.";
    }

    public static String messageSpeedtestAlreadyRunning() {
        return chatPrefix()
                + "A Chronos speedtest or backup is already running.";
    }

    public static String messageChronosUsage() {
        return chatPrefix() + "Usage: " + USAGE_LINE;
    }

    public static String messageUnknownSubcommand(String sub) {
        return chatPrefix() + "Unknown subcommand \"" + sub + "\". " + USAGE_LINE;
    }

    public static String messageSpeedtestUsage() {
        return chatPrefix()
                + "Usage: /"
                + ChronosCommandLiterals.ROOT
                + " "
                + ChronosCommandLiterals.SPEEDTEST
                + " <s>";
    }

    public static String messageSpeedtestInvalidInteger(String token) {
        return chatPrefix() + "Not an integer: \"" + token + "\".";
    }

    public static String messageReloadApplied() {
        return chatPrefix() + "Reloaded chronos.toml.";
    }

    public static String messageReloadFailed() {
        return chatPrefix()
                + "Could not reload chronos.toml. Keeping previous config. Check the console for further details.";
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

    /**
     * Forces a config reload from disk. Returns {@code true} when a new config was
     * applied.
     */
    public static boolean tryReloadConfig() {
        return Core.reloadConfigNow();
    }
}

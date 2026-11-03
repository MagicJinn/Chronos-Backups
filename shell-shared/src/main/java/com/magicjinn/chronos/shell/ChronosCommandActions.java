package com.magicjinn.chronos.shell;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.magicjinn.chronos.core.Backupper;
import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.core.Scheduler;
import com.magicjinn.chronos.core.Scheduler.EnqueueResult;
import com.magicjinn.chronos.core.config.CompressionMethod;
import com.magicjinn.chronos.core.config.Config;

/**
 * Shared implementation for {@code /chronos} subcommands. Loader modules register Brigadier (or
 * legacy Forge) trees that delegate here.
 */
public final class ChronosCommandActions {
    /** Short hint for legacy {@code ICommand} usage lines. */
    public static final String USAGE_LINE = "/"
            + ChronosCommandLiterals.ROOT + " " + ChronosCommandLiterals.BACKUP
            + " | /"
            + ChronosCommandLiterals.ROOT + " " + ChronosCommandLiterals.CANCEL
            + " | /"
            + ChronosCommandLiterals.ROOT + " " + ChronosCommandLiterals.SPEEDTEST + " <s>"
            + " | /"
            + ChronosCommandLiterals.ROOT + " " + ChronosCommandLiterals.LIST
            + " | /"
            + ChronosCommandLiterals.ROOT + " " + ChronosCommandLiterals.EXPORT
            + " <tag>"
            + " | /"
            + ChronosCommandLiterals.ROOT + " " + ChronosCommandLiterals.RELOAD;

    /** Newest backups shown by {@code /chronos list} and export tab-complete. */
    private static final int BACKUP_LIST_UI_LIMIT = 10;

    private static final String GIT_OLDER_TAGS_HINT = "Only " + BACKUP_LIST_UI_LIMIT
            + " backups are shown. For older backups, open the backup folder in a Git client/CLI.";

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

    public static String messageGitModeRequired() {
        return chatPrefix()
                + "This command requires compressionMethod = \"git\" in config/chronos.toml.";
    }

    public static String messageExportUsage() {
        return chatPrefix()
                + "Usage: /"
                + ChronosCommandLiterals.ROOT + " " + ChronosCommandLiterals.EXPORT + " <tag>";
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

    /** Lists backups for the active world (git tags, or zip/folder names). */
    public static String tryListBackups() {
        if (Scheduler.tryGetRuntimeContext() == null)
            return messageRuntimeInactive();

        boolean git = Config.getCompressionMethod() == CompressionMethod.GIT;
        try {
            List<String> names = git
                    ? Backupper.listGitBackupTags()
                    : Backupper.listLocalBackupNames();
            if (names.isEmpty())
                return chatPrefix()
                        + (git ? "No git backup snapshots yet." : "No backups yet.");

            StringBuilder sb = new StringBuilder();
            sb.append(chatPrefix())
                    .append(git ? "Git snapshots (newest first):" : "Backups (newest first):");
            int limit = Math.min(names.size(), BACKUP_LIST_UI_LIMIT);
            for (int i = 0; i < limit; i++) {
                sb.append("\n  ").append(names.get(i));
            }
            // If above limit, print a hint on how to view older ones
            if (names.size() > limit) {
                sb.append("\n  ... ").append(names.size() - limit).append(" older not shown.");
                if (git)
                    sb.append(" ").append(GIT_OLDER_TAGS_HINT);
            }

            return sb.toString();
        } catch (Exception e) {
            return chatPrefix() + "Failed to list backups: " + e.getMessage();
        }
    }

    /** Exports a git tag to backups/exported/. */
    public static String tryExportGitBackup(String tag) {
        if (Config.getCompressionMethod() != CompressionMethod.GIT)
            return messageGitModeRequired();

        if (tag == null || tag.trim().isEmpty())
            return messageExportUsage();

        if (Scheduler.tryGetRuntimeContext() == null)
            return messageRuntimeInactive();

        try {
            Path dest = Backupper.exportGitBackup(tag.trim());
            return chatPrefix()
                    + "Exported snapshot to " + dest + " (live world was not modified).";
        } catch (Exception e) {
            return chatPrefix() + "Export failed: " + e.getMessage();
        }
    }

    /**
     * Tab-completion candidates for {@code /chronos export <tag>}. Empty when not
     * in git mode or the repo is unavailable.
     */
    public static List<String> suggestExportTags(String prefix) {
        if (Config.getCompressionMethod() != CompressionMethod.GIT)
            return Collections.emptyList();

        if (Scheduler.tryGetRuntimeContext() == null)
            return Collections.emptyList();

        try {
            List<String> tags = Backupper.listGitBackupTags();
            if (tags.isEmpty())
                return Collections.emptyList();

            List<String> newest = tags.size() <= BACKUP_LIST_UI_LIMIT
                    ? tags
                    : tags.subList(0, BACKUP_LIST_UI_LIMIT);
            if (prefix == null || prefix.isEmpty())
                return new ArrayList<String>(newest);

            String p = prefix.toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<String>();
            for (String tag : newest) {
                if (tag.toLowerCase(Locale.ROOT).startsWith(p))
                    out.add(tag);
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
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

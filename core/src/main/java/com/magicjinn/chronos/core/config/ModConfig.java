package com.magicjinn.chronos.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.magicjinn.chronos.core.ChronosBackupArtifacts;

/**
 * In-memory loaded values for {@code chronos.toml}.
 * Add a public field here, then register it once in {@link ChronosTomlSpec}.
 * Read live values via {@link Config#live()}. Use {@link Config#snapshot()} for
 * in-flight backups.
 */
public final class ModConfig {
    /* Single folder name under the run directory that will contain the backups */
    public String backupFolderName = ChronosBackupArtifacts.DEFAULT_BACKUP_FOLDER_NAME;

    public boolean pruneChunks = true;
    public int pruneTimeRequirementSeconds = 60 * 2; // 2 minutes of playtime
    /**
     * Maximum worker threads for pruning.
     * 0 (or less) means "auto" (pruner picks a sensible default).
     */
    public int pruneMaxWorkerThreads = 0;
    /**
     * When false, automatic backups are disabled ({@code /chronos} manual backups
     * still work).
     */
    public boolean scheduleBackups = true;
    /** Seconds between automatic backup runs (whole numbers only). */
    public int backupIntervalSeconds = 60 * 30; // 30 minutes

    /**
     * Maximum number of backup artifacts kept per world (local and remote).
     * Not enforced in git mode. Values less than 1 disable automatic deletion of older backups.
     */
    public int maxStoredBackups = 5;

    /**
     * {@code zip}: copy to cache then write a {@code .zip} in the world's backup
     * subdirectory.
     * {@code none}: copy the world tree directly into a folder named like the zip
     * basename (no cache, no archive).
     * {@code git}: incremental Git repository under the world backup folder
     */
    public CompressionMethod compressionMethod = CompressionMethod.ZIP;

    /**
     * When true (default), git-mode prefers native {@code git} on PATH when available.
     * When false, git-mode always uses bundled gitoxide.
     */
    public boolean allowNativeGit = true;

    /** When true, local backups will be kept even if a cloud integration is enabled, and upload succeeds. */
    public boolean shouldKeepLocalBackups = true;

    /** Whether backups should be uploaded to Google Drive. */
    public boolean googleDriveEnabled = false;

    /**
     * World-relative path segments or names to skip when copying the save for a
     * backup. A single segment (no {@code /}) matches any file or folder with that
     * name, while paths with {@code /} match as a prefix under the world root.
     */
    public List<String> copyBlacklist = new ArrayList<>(
            Arrays.asList(
                    ".trash",
                    "voxy",
                    "voxyserver",
                    "dynmap",
                    "bluemap",
                    "DistantHorizons.sqlite",
                    "DistantHorizons.sqlite-wal",
                    "DistantHorizons.sqlite-shm"));

    /**
     * Permission level (0-4) required to run {@code /chronos}.
     */
    public int commandRequiredPermissionLevel = 4;

    /**
     * Text prepended to Chronos server chat on Minecraft 1.13+ ({@code /tellraw}).
     * Unused on 1.12 and below, which broadcast with {@code /say} instead.
     */
    public String chatPrefix = "[Chronos] ";

    /**
     * When true, Chronos polls {@code chronos.toml} about every
     * {@code POLL_INTERVAL_SECONDS} milliseconds and reloads after the file
     * size/mtime have settled. {@code /chronos reload} always works.
     */
    public boolean autoReload = true;

    /**
     * Deep-enough copy for in-flight backup snapshots and atomic swaps. Why is
     * there not a built in method for this in Java? OR ANY LANGUAGE?
     */
    public ModConfig copy() {
        ModConfig out = new ModConfig();
        out.backupFolderName = backupFolderName;
        out.pruneChunks = pruneChunks;
        out.pruneTimeRequirementSeconds = pruneTimeRequirementSeconds;
        out.pruneMaxWorkerThreads = pruneMaxWorkerThreads;
        out.scheduleBackups = scheduleBackups;
        out.backupIntervalSeconds = backupIntervalSeconds;
        out.maxStoredBackups = maxStoredBackups;
        out.compressionMethod = compressionMethod;
        out.allowNativeGit = allowNativeGit;
        out.shouldKeepLocalBackups = shouldKeepLocalBackups;
        out.googleDriveEnabled = googleDriveEnabled;
        out.copyBlacklist = copyBlacklist == null ? new ArrayList<String>() : new ArrayList<String>(copyBlacklist);
        out.commandRequiredPermissionLevel = commandRequiredPermissionLevel;
        out.chatPrefix = chatPrefix;
        out.autoReload = autoReload;
        return out;
    }
}

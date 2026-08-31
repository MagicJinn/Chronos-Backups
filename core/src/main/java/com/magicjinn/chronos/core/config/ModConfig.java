package com.magicjinn.chronos.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.magicjinn.chronos.core.ChronosBackupArtifacts;

/**
 * In-memory loaded values for {@code chronos.toml}.
 * Add a public field here, then register it once in {@link ChronosTomlSpec}.
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
     * Values less than 1 disable automatic deletion of older backups.
     */
    public int maxStoredBackups = 5;

    /**
     * {@code zip}: copy to cache then write a {@code .zip} in the world's backup
     * subdirectory.
     * {@code none}: copy the world tree directly into a folder named like the zip
     * basename (no cache, no archive).
     */
    public CompressionMethod compressionMethod = CompressionMethod.ZIP;

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
                    "voxy",
                    "voxyserver",
                    "dynmap",
                    "bluemap",
                    "DistantHorizons.sqlite",
                    "DistantHorizons.sqlite-wal",
                    "DistantHorizons.sqlite-shm",
                    "ledger.sqlite"));

    /**
     * Permission level (0-4) required to run {@code /chronos}.
     */
    public int commandRequiredPermissionLevel = 4;
}

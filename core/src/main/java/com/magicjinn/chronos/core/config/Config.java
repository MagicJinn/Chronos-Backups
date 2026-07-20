package com.magicjinn.chronos.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.magicjinn.chronos.core.Core;

/**
 * Loads {@code config/chronos.toml}. Reads with Night Config (on the loader
 * classpath for all platforms).
 */
public final class Config {
    private static final Logger LOG = Logger.getLogger(Config.class.getName());

    public static ModConfig modConfig;

    private static final ModConfig BUILTIN_DEFAULTS = new ModConfig();

    private static final String CONFIG_FOLDER_NAME = "config";
    private static final String CONFIG_FILE_NAME = "chronos.toml";

    public static void InitializeConfig() {
        Path configPath = Core.RunningDirectory.resolve(CONFIG_FOLDER_NAME).resolve(CONFIG_FILE_NAME);
        ModConfig defaults = new ModConfig();
        if (needsDefaultConfigFile(configPath)) {
            writeTomlDocument(configPath, defaults); // writeTomlDocument will create parent dirs if needed
        }
        modConfig = loadFromToml(configPath, defaults);
    }

    /**
     * Writes defaults when the file is missing or empty. An empty file can be left
     * behind by a failed write, a race,
     * or tooling that creates {@code config/chronos.toml} before the mod runs.
     */
    private static boolean needsDefaultConfigFile(Path configPath) {
        try {
            if (!Files.isRegularFile(configPath)) {
                return true;
            }
            return Files.size(configPath) == 0;
        } catch (IOException e) {
            LOG.warning("Could not inspect " + CONFIG_FILE_NAME + ", will try to write defaults: " + e.getMessage());
            return true;
        }
    }

    private static void writeTomlDocument(Path path, ModConfig config) {
        try {
            Path parent = path.getParent();
            if (parent != null)
                Files.createDirectories(parent);
            Files.write(
                    path,
                    ChronosTomlSpec.renderDocument(config).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOG.warning("Could not write " + CONFIG_FILE_NAME + " (using in-memory values): " + e.getMessage());
        }
    }

    private static ModConfig loadFromToml(Path configPath, ModConfig defaults) {
        try (FileConfig cfg = FileConfig.of(configPath, TomlFormat.instance())) {
            cfg.load();
            ModConfig out = ChronosTomlSpec.load(cfg, defaults);
            int fileVersion = cfg.getIntOrElse(ChronosTomlSpec.KEY_CONFIG_VERSION, 0);
            if (fileVersion != ChronosTomlSpec.CONFIG_VERSION) {
                LOG.info("Updating " + CONFIG_FILE_NAME + " from format v" + fileVersion + " to v"
                        + ChronosTomlSpec.CONFIG_VERSION);
                writeTomlDocument(configPath, out);
            }
            return out;
        } catch (Exception e) {
            LOG.severe("Failed to load " + CONFIG_FILE_NAME + ", using defaults: " + e.getMessage());
            return defaults;
        }
    }

    /**
     * When true, the scheduler may trigger automatic backups on the configured
     * interval. When false, only manual {@code /chronos} backups run.
     */
    public static boolean getScheduleBackups() {
        return modConfig != null ? modConfig.scheduleBackups : BUILTIN_DEFAULTS.scheduleBackups;
    }

    /**
     * Seconds between automatic backups. When {@link #modConfig} is unset (e.g.
     * init ordering), returns the same
     * built-in defaults as a fresh {@link ModConfig}.
     */
    public static int getBackupIntervalSeconds() {
        return modConfig != null ? modConfig.backupIntervalSeconds : BUILTIN_DEFAULTS.backupIntervalSeconds;
    }

    /**
     * Whether backup snapshots should run chunk pruning.
     */
    public static boolean getPruneChunksEnabled() {
        return modConfig != null ? modConfig.pruneChunks : BUILTIN_DEFAULTS.pruneChunks;
    }

    /**
     * Minimum in-world playtime (seconds) before a chunk may no longer be pruned. When
     * {@link #modConfig} is unset, returns the
     * same built-in defaults as a fresh {@link ModConfig}.
     */
    public static int getPruneTimeRequirementSeconds() {
        return modConfig != null ? modConfig.pruneTimeRequirementSeconds : BUILTIN_DEFAULTS.pruneTimeRequirementSeconds;
    }

    /**
     * Maximum pruning worker threads.
     * 0 means auto, values less than 0 are clamped to 0.
     */
    public static int getPruneMaxWorkerThreads() {
        int raw = modConfig != null ? modConfig.pruneMaxWorkerThreads : BUILTIN_DEFAULTS.pruneMaxWorkerThreads;
        return Math.max(0, raw);
    }

    /**
     * Path patterns excluded when copying the world for backup. When {@link #modConfig} is unset,
     * returns the same built-in defaults as a fresh {@link ModConfig}.
     */
    public static List<String> getCopyBlacklist() {
        List<String> src = modConfig != null && modConfig.copyBlacklist != null ? modConfig.copyBlacklist
                : BUILTIN_DEFAULTS.copyBlacklist;
        return Collections.unmodifiableList(new ArrayList<>(src));
    }

    /**
     * Clamped to 0-4 (vanilla permission levels). When {@link #modConfig} is unset,
     * returns built-in default (4).
     */
    public static int getCommandRequiredPermissionLevel() {
        int raw = modConfig != null ? modConfig.commandRequiredPermissionLevel
                : BUILTIN_DEFAULTS.commandRequiredPermissionLevel;
        return Math.max(0, Math.min(4, raw));
    }

    /**
     * The name of the folder that will contain the backups. When {@link #modConfig}
     * is unset, returns built-in default ("chronos").
     */
    public static String getBackupFolderName() {
        return modConfig != null ? modConfig.backupFolderName : BUILTIN_DEFAULTS.backupFolderName;
    }

    /**
     * Maximum backups per world locally and on each enabled cloud destination.
     * Values below 1 disable automatic deletion of older backups.
     */
    public static int getMaxStoredBackups() {
        return modConfig != null ? modConfig.maxStoredBackups : BUILTIN_DEFAULTS.maxStoredBackups;
    }

    /** Compression method used for backups. */
    public static CompressionMethod getCompressionMethod() {
        return modConfig != null ? modConfig.compressionMethod : BUILTIN_DEFAULTS.compressionMethod;
    }

    /** When true, local backups will be kept even if a cloud integration is enabled, and upload succeeds. */
    public static boolean shouldKeepLocalBackups() {
        return modConfig != null ? modConfig.shouldKeepLocalBackups : BUILTIN_DEFAULTS.shouldKeepLocalBackups;
    }

    /** When true, Chronos may authorize and upload backups to Google Drive. */
    public static boolean isGoogleDriveEnabled() {
        return modConfig != null ? modConfig.googleDriveEnabled : BUILTIN_DEFAULTS.googleDriveEnabled;
    }

    private Config() {
    }
}

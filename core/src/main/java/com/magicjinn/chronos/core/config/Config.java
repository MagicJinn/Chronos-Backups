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
            ModConfig out = new ModConfig();
            out.pruneChunks = cfg.getOrElse(ChronosTomlSpec.KEY_PRUNE_CHUNKS, defaults.pruneChunks);
            out.pruneTimeRequirementSeconds = cfg.getIntOrElse(ChronosTomlSpec.KEY_PRUNE_TIME_REQUIREMENT_SECONDS,
                    defaults.pruneTimeRequirementSeconds);
            out.pruneMaxWorkerThreads = cfg.getIntOrElse(
                    ChronosTomlSpec.KEY_PRUNE_MAX_WORKER_THREADS,
                    defaults.pruneMaxWorkerThreads);
            out.backupIntervalSeconds = cfg.getIntOrElse(ChronosTomlSpec.KEY_BACKUP_INTERVAL_SECONDS,
                    defaults.backupIntervalSeconds);
            out.commandRequiredPermissionLevel = cfg.getIntOrElse(
                    ChronosTomlSpec.KEY_COMMAND_REQUIRED_PERMISSION_LEVEL,
                    defaults.commandRequiredPermissionLevel);
            loadCopyBlacklist(cfg, out, defaults);
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

    private static void loadCopyBlacklist(FileConfig cfg, ModConfig out, ModConfig defaults) {
        Object raw = cfg.get(ChronosTomlSpec.KEY_COPY_BLACKLIST);
        if (!(raw instanceof List)) {
            out.copyBlacklist = new ArrayList<>(defaults.copyBlacklist);
            return;
        }
        List<?> fromFile = (List<?>) raw;
        out.copyBlacklist = new ArrayList<>();
        for (Object o : fromFile) {
            if (o == null) {
                continue;
            }
            String s = o.toString().trim();
            if (!s.isEmpty()) {
                out.copyBlacklist.add(s);
            }
        }
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
     * 0 means auto; values less than 0 are clamped to 0.
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
     * Clamped to 0–4 (vanilla permission levels). When {@link #modConfig} is unset,
     * returns built-in default (4).
     */
    public static int getCommandRequiredPermissionLevel() {
        int raw = modConfig != null ? modConfig.commandRequiredPermissionLevel
                : BUILTIN_DEFAULTS.commandRequiredPermissionLevel;
        return Math.max(0, Math.min(4, raw));
    }

    private Config() {
    }
}

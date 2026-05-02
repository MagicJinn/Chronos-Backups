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

    private static final String CONFIG_FILE_NAME = "chronos.toml";

    public static void InitializeConfig() {
        Path configFolder = Core.RunningDirectory.resolve("config");
        try {
            Files.createDirectories(configFolder);
        } catch (IOException e) {
            LOG.severe("Failed to create config folder: " + e.getMessage());
        }

        Path configPath = configFolder.resolve(CONFIG_FILE_NAME);
        ModConfig defaults = new ModConfig();
        if (needsDefaultConfigFile(configPath)) {
            writeTomlDocument(configPath, defaults);
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
            out.pruneTimeRequirementSeconds = cfg.getIntOrElse(ChronosTomlSpec.KEY_PRUNE_TIME_REQUIREMENT_SECONDS,
                    defaults.pruneTimeRequirementSeconds);
            out.backupIntervalSeconds = cfg.getIntOrElse(ChronosTomlSpec.KEY_BACKUP_INTERVAL_SECONDS,
                    defaults.backupIntervalSeconds);
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
        ModConfig c = modConfig;
        return c != null ? c.backupIntervalSeconds : BUILTIN_DEFAULTS.backupIntervalSeconds;
    }

    /**
     * Minimum in-world playtime (seconds) before a chunk may no longer be pruned. When
     * {@link #modConfig} is unset, returns the
     * same built-in defaults as a fresh {@link ModConfig}.
     */
    public static int getPruneTimeRequirementSeconds() {
        ModConfig c = modConfig;
        return c != null ? c.pruneTimeRequirementSeconds : BUILTIN_DEFAULTS.pruneTimeRequirementSeconds;
    }

    /**
     * Path patterns excluded when copying the world for backup. When {@link #modConfig} is unset,
     * returns the same built-in defaults as a fresh {@link ModConfig}.
     */
    public static List<String> getCopyBlacklist() {
        ModConfig c = modConfig;
        List<String> src = c != null && c.copyBlacklist != null ? c.copyBlacklist : BUILTIN_DEFAULTS.copyBlacklist;
        return Collections.unmodifiableList(new ArrayList<>(src));
    }

    private Config() {
    }
}

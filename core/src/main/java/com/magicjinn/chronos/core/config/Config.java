package com.magicjinn.chronos.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.magicjinn.chronos.core.ChronosBackupArtifacts;
import com.magicjinn.chronos.core.ChronosLogger;
import com.magicjinn.chronos.core.Core;

/**
 * Loads {@code config/chronos.toml}. Reads with Night Config (on the loader
 * classpath for all platforms). Polls the file during the server tick loop and
 * reloads when its size or last-modified time has settled.
 */
public final class Config {
    private static final AtomicReference<ModConfig> MOD_CONFIG = new AtomicReference<ModConfig>();

    private static final ModConfig BUILTIN_DEFAULTS = new ModConfig();

    private static final String CONFIG_FOLDER_NAME = "config";
    private static final String CONFIG_FILE_NAME = "chronos.toml";

    /** How often to inspect the config file for external edits. */
    private static final long POLL_INTERVAL_MS = 1000L;

    private static long lastPollEpochMs;
    private static long trackedSize = -1L;
    private static long trackedMtimeMs = -1L;
    private static long pendingSize = -1L;
    private static long pendingMtimeMs = -1L;
    private static boolean hasPendingChange;

    /**
     * Successful live reload. Previous values, and the newly applied ones.
     */
    public static final class ReloadOutcome {
        public final ModConfig previous;
        public final ModConfig current;

        public ReloadOutcome(ModConfig previous, ModConfig current) {
            this.previous = previous;
            this.current = current;
        }
    }

    public static void InitializeConfig() {
        Path configPath = configPath();
        ModConfig defaults = new ModConfig();
        if (needsDefaultConfigFile(configPath)) {
            writeTomlDocument(configPath, defaults); // writeTomlDocument will create parent dirs if needed
        }
        ModConfig loaded = loadFromToml(configPath, defaults);
        sanitizeLoadedBackupFolderName(loaded);
        setNewConfig(loaded);
        recordFingerprint(configPath);
        clearPendingChange();
        lastPollEpochMs = System.currentTimeMillis();
    }

    /**
     * Called from the server tick loop. Uses a settle debounce. After a size/mtime
     * change is seen, the next poll reloads only if the fingerprint is unchanged.
     */
    // Will not run when the server is paused, but this is OK! Backups do not run
    // during this period either, meaning that as soon as the server is unpaused,
    // the config will be reloaded before the next backup runs.
    public static Optional<ReloadOutcome> pollForFileChanges() {
        if (!live().autoReload)
            return Optional.empty();

        long now = System.currentTimeMillis();
        if (now - lastPollEpochMs < POLL_INTERVAL_MS)
            return Optional.empty();

        lastPollEpochMs = now;

        Path configPath = configPath();
        Fingerprint finger = readFingerprint(configPath);
        if (finger == null)
            return Optional.empty();

        boolean differsFromTracked = finger.size != trackedSize || finger.mtimeMs != trackedMtimeMs;
        if (!differsFromTracked && !hasPendingChange)
            return Optional.empty();

        if (!hasPendingChange || finger.size != pendingSize || finger.mtimeMs != pendingMtimeMs) {
            // First sighting of this fingerprint (or still changing). Wait one more poll.
            pendingSize = finger.size;
            pendingMtimeMs = finger.mtimeMs;
            hasPendingChange = true;
            return Optional.empty();
        }

        // If we reach here, the fingerprint is stable across two polls. Safe to
        // proceed.
        return tryApplyReload(configPath, finger,
                "Detected settled change in " + CONFIG_FILE_NAME + ", reloaded config.");
    }

    /**
     * Immediate reload for {@code /chronos reload}. Ignores {@code autoReload} and
     * debounce. Parse failure keeps the previous config, never rewrites the file.
     */
    public static Optional<ReloadOutcome> forceReloadFromDisk() {
        Path configPath = configPath();
        Fingerprint finger = readFingerprint(configPath);
        if (finger == null) {
            ChronosLogger.warn(CONFIG_FILE_NAME + " is missing. Cannot reload.");
            return Optional.empty();
        }
        if (finger.size == 0L) {
            ChronosLogger.warn(
                    CONFIG_FILE_NAME + " is empty. Keeping previous config.");
            trackedSize = finger.size;
            trackedMtimeMs = finger.mtimeMs;
            clearPendingChange();
            return Optional.empty();
        }
        return tryApplyReload(configPath, finger, "Forced reload of " + CONFIG_FILE_NAME + ".");
    }

    private static Optional<ReloadOutcome> tryApplyReload(Path configPath, Fingerprint finger, String successLog) {
        if (finger.size == 0L) {
            ChronosLogger.warn(
                    "Failed to reload "
                            + CONFIG_FILE_NAME
                            + ": file is empty. Keeping previous config.");
            trackedSize = finger.size;
            trackedMtimeMs = finger.mtimeMs;
            clearPendingChange();
            return Optional.empty();
        }

        ModConfig previous = live();
        ModConfig defaults = previous;
        ModConfig loaded;
        try {
            loaded = tryLoadForReload(configPath, defaults);
        } catch (Exception e) {
            String reason = e.getMessage() == null || e.getMessage().isEmpty() ? e.toString() : e.getMessage();
            ChronosLogger.warn(
                    "Failed to reload "
                            + CONFIG_FILE_NAME
                            + ": "
                            + reason
                            + ". Keeping previous config.");
            trackedSize = finger.size;
            trackedMtimeMs = finger.mtimeMs;
            clearPendingChange();
            return Optional.empty();
        }

        sanitizeLoadedBackupFolderName(loaded);
        List<String> changes = ChronosTomlSpec.describeChanges(previous, loaded);
        setNewConfig(loaded);
        recordFingerprint(configPath);
        clearPendingChange();

        ChronosLogger.info(successLog);
        if (changes.isEmpty()) {
            ChronosLogger.info("No setting values changed (comments or whitespace only).");
        } else {
            for (String change : changes) {
                ChronosLogger.info("  " + change);
            }
        }
        ChronosTomlSpec.logRestartRequiredChanges(previous, loaded);
        return Optional.of(new ReloadOutcome(previous, loaded));
    }

    private static void setNewConfig(ModConfig next) {
        MOD_CONFIG.set(next);
    }

    /**
     * Live config. Never null. Falls back to built-in defaults before
     * {@link #InitializeConfig()} or if the reference was cleared.
     */
    public static ModConfig live() {
        ModConfig c = MOD_CONFIG.get();
        return c != null ? c : BUILTIN_DEFAULTS;
    }

    private static void clearPendingChange() {
        hasPendingChange = false;
        pendingSize = -1L;
        pendingMtimeMs = -1L;
    }

    private static final class Fingerprint {
        final long size;
        final long mtimeMs;

        Fingerprint(long size, long mtimeMs) {
            this.size = size;
            this.mtimeMs = mtimeMs;
        }
    }

    private static Fingerprint readFingerprint(Path configPath) {
        try {
            if (!Files.isRegularFile(configPath)) {
                return null;
            }
            return new Fingerprint(Files.size(configPath), Files.getLastModifiedTime(configPath).toMillis());
        } catch (IOException e) {
            return null;
        }
    }

    private static Path configPath() {
        return Core.RunningDirectory.resolve(CONFIG_FOLDER_NAME).resolve(CONFIG_FILE_NAME);
    }

    private static void recordFingerprint(Path configPath) {
        Fingerprint finger = readFingerprint(configPath);
        if (finger == null) {
            trackedSize = -1L;
            trackedMtimeMs = -1L;
            return;
        }
        trackedSize = finger.size;
        trackedMtimeMs = finger.mtimeMs;
    }

    /**
     * Clamp {@code backupFolderName} to a single safe segment under the run dir.
     */
    private static void sanitizeLoadedBackupFolderName(ModConfig config) {
        if (config == null)
            return;

        String raw = config.backupFolderName;
        String trimmed = raw == null ? "" : raw.trim();
        String safe = ChronosBackupArtifacts.sanitizeBackupFolderName(raw);
        if (!safe.equals(trimmed)) {
            ChronosLogger.warn(
                    "Invalid "
                            + ChronosTomlSpec.KEY_BACKUP_FOLDER_NAME
                            + " \""
                            + raw
                            + "\", using \""
                            + safe
                            + "\".");
        }
        config.backupFolderName = safe;
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
            ChronosLogger.warn("Could not inspect " + CONFIG_FILE_NAME + ", will try to write defaults: " + e.getMessage());
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
            ChronosLogger.warn("Could not write " + CONFIG_FILE_NAME + " (using in-memory values): " + e.getMessage());
        }
    }

    private static ModConfig loadFromToml(Path configPath, ModConfig defaults) {
        try (FileConfig cfg = FileConfig.of(configPath, TomlFormat.instance())) {
            cfg.load();
            ModConfig out = ChronosTomlSpec.load(cfg, defaults);
            int fileVersion = cfg.getIntOrElse(ChronosTomlSpec.KEY_CONFIG_VERSION, 0);
            if (fileVersion != ChronosTomlSpec.CONFIG_VERSION) {
                ChronosLogger.info("Updating " + CONFIG_FILE_NAME + " from format v" + fileVersion + " to v"
                        + ChronosTomlSpec.CONFIG_VERSION);
                writeTomlDocument(configPath, out);
            }
            return out;
        } catch (Exception e) {
            ChronosLogger.error("Failed to load " + CONFIG_FILE_NAME + ", using defaults: " + e.getMessage());
            return defaults;
        }
    }

    /**
     * Reload. NEVER substitutes built-in defaults on failure (keeps the
     * previous config). Also migrates the on-disk format when the version key
     * differs.
     */
    private static ModConfig tryLoadForReload(Path configPath, ModConfig defaults) throws Exception {
        try (FileConfig cfg = FileConfig.of(configPath, TomlFormat.instance())) {
            cfg.load();
            ModConfig out = ChronosTomlSpec.load(cfg, defaults);
            int fileVersion = cfg.getIntOrElse(ChronosTomlSpec.KEY_CONFIG_VERSION, 0);
            if (fileVersion != ChronosTomlSpec.CONFIG_VERSION)
                ChronosLogger.info("Updating " + CONFIG_FILE_NAME + " from format v" + fileVersion + " to v"
                        + ChronosTomlSpec.CONFIG_VERSION);

            writeTomlDocument(configPath, out);

            return out;
        }
    }

    /**
     * Clamped to 0-4 (vanilla permission levels).
     */
    public static int getCommandRequiredPermissionLevel() {
        return Math.max(0, Math.min(4, live().commandRequiredPermissionLevel));
    }

    /**
     * Single folder name under the run directory for backups. Always sanitized.
     */
    public static String getBackupFolderName() {
        return ChronosBackupArtifacts.sanitizeBackupFolderName(live().backupFolderName);
    }

    /** Snapshot of the live config for an in-flight backup. */
    public static ModConfig snapshot() {
        return live().copy();
    }

    /**
     * Test helper. Next {@link #pollForFileChanges()} ignores the poll interval.
     */
    static void resetPollThrottleForTests() {
        lastPollEpochMs = 0L;
    }

    /** Test helper. Clear settle-debounce state. */
    static void clearPendingChangeForTests() {
        clearPendingChange();
    }

    /** Test helper. Read the live reference (may be null). */
    static ModConfig peekForTests() {
        return MOD_CONFIG.get();
    }

    /** Test helper. Restore a previous live config */
    static void restoreForTests(ModConfig previous) {
        MOD_CONFIG.set(previous);
    }

    private Config() {
    }
}

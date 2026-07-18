package com.magicjinn.chronos.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.electronwill.nightconfig.core.Config;

/**
 * Single place for {@code chronos.toml} keys, comments, load, and render.
 * Default values stay on {@link ModConfig} fields. To add a setting, add the field
 * on {@link ModConfig}, then one entry in {@link #ENTRIES}.
 */
public final class ChronosTomlSpec {
    public static final String KEY_SHOULD_KEEP_LOCAL_BACKUPS = "shouldKeepLocalBackups";
    public static final String KEY_BACKUP_FOLDER_NAME = "backupFolderName";
    public static final String KEY_PRUNE_CHUNKS = "pruneChunks";
    public static final String KEY_PRUNE_TIME_REQUIREMENT_SECONDS = "pruneTimeRequirementSeconds";
    public static final String KEY_PRUNE_MAX_WORKER_THREADS = "pruneMaxWorkerThreads";
    public static final String KEY_SCHEDULE_BACKUPS = "scheduleBackups";
    public static final String KEY_BACKUP_INTERVAL_SECONDS = "backupIntervalSeconds";
    public static final String KEY_MAX_STORED_BACKUPS = "maxStoredBackups";
    public static final String KEY_COMPRESSION_METHOD = "compressionMethod";
    public static final String KEY_COPY_BLACKLIST = "copyBlacklist";
    public static final String KEY_GOOGLE_DRIVE_ENABLED = "googleDriveEnabled";
    public static final String KEY_COMMAND_REQUIRED_PERMISSION_LEVEL = "commandRequiredPermissionLevel";
    public static final String KEY_CONFIG_VERSION = "configVersion";

    /** Internal config format version. Bump when the on-disk layout changes. */
    public static final int CONFIG_VERSION = 2;

    /**
     * Stable order = on-disk order. Add new user settings here (and the field on
     * {@link ModConfig}).
     */
    private static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
            string(KEY_BACKUP_FOLDER_NAME,
                    (c, v) -> c.backupFolderName = v,
                    c -> c.backupFolderName,
                    "# The name of the folder that will contain the backups.",
                    "# Example: \"chronos\""),
            bool(KEY_PRUNE_CHUNKS,
                    (c, v) -> c.pruneChunks = v,
                    c -> c.pruneChunks,
                    "# Whether chunk pruning is enabled for backup snapshots.",
                    "# If false, Chronos copies the world snapshot without pruning any chunk data."),
            integer(KEY_PRUNE_TIME_REQUIREMENT_SECONDS,
                    (c, v) -> c.pruneTimeRequirementSeconds = v,
                    c -> c.pruneTimeRequirementSeconds,
                    "# Minimum playtime (in seconds) for a region to count toward snapshot pruning.",
                    "# The lower the value, the more chunks are kept in the backup."),
            integer(KEY_PRUNE_MAX_WORKER_THREADS,
                    (c, v) -> c.pruneMaxWorkerThreads = v,
                    c -> c.pruneMaxWorkerThreads,
                    "# Max worker threads used by native pruning.",
                    "# 0 uses auto thread selection, positive values set the maximum parallelism."),
            bool(KEY_SCHEDULE_BACKUPS,
                    (c, v) -> c.scheduleBackups = v,
                    c -> c.scheduleBackups,
                    "# Whether to run backups on a timer. If false, only manual /chronos backups run."),
            integer(KEY_BACKUP_INTERVAL_SECONDS,
                    (c, v) -> c.backupIntervalSeconds = v,
                    c -> c.backupIntervalSeconds,
                    "# Seconds between automatic backup runs (whole numbers only).",
                    "# Example: 1800 = every 30 minutes, 3600 = hourly."),
            integer(KEY_MAX_STORED_BACKUPS,
                    (c, v) -> c.maxStoredBackups = v,
                    c -> c.maxStoredBackups,
                    "# Maximum backups kept per world.",
                    "# After a successful backup, oldest snapshots are removed if this would be exceeded.",
                    "# Recommended value: 5. Values lower than 3 can be used to save space, but risks data loss if a catastrophic error occured several backups ago.",
                    "# Values below 1 disable automatic removal."),
            compressionMethod(
                    "# Snapshot storage: \"zip\" or \"none\".",
                    "# \"zip\" uses zip for compression. \"none\" stores an uncompressed folder."),
            bool(KEY_GOOGLE_DRIVE_ENABLED,
                    (c, v) -> c.googleDriveEnabled = v,
                    c -> c.googleDriveEnabled,
                    "# Whether backups should be uploaded to Google Drive.",
                    "# Requires OAuth credentials to be set up. On mod startup, check the console for instructions."),
            bool(KEY_SHOULD_KEEP_LOCAL_BACKUPS,
                    (c, v) -> c.shouldKeepLocalBackups = v,
                    c -> c.shouldKeepLocalBackups,
                    "# When true, keep local backups even if a cloud upload succeeds."),
            stringList(KEY_COPY_BLACKLIST,
                    (c, v) -> c.copyBlacklist = v,
                    c -> c.copyBlacklist,
                    "# Paths to exclude from the backup snapshot copy (names anywhere under the world, or relative paths",
                    "# with /)."),
            integer(KEY_COMMAND_REQUIRED_PERMISSION_LEVEL,
                    (c, v) -> c.commandRequiredPermissionLevel = v,
                    c -> c.commandRequiredPermissionLevel,
                    "# Minimum permission level (0-4) required to run /chronos.",
                    "# 4 matches highly sensitive vanilla commands (e.g. /stop), 0 allows any command source that can run commands.")));

    public static ModConfig load(Config cfg, ModConfig defaults) {
        ModConfig out = new ModConfig();
        for (Entry entry : ENTRIES) {
            entry.load(cfg, out, defaults);
        }
        return out;
    }

    /**
     * Full file body: stable key order, comments tuned for reading in a text
     * editor. The format version key is last so the on-disk layout stays easy to
     * read from top to bottom.
     */
    public static String renderDocument(ModConfig config) {
        List<String> lines = new ArrayList<String>();
        lines.add("# Chronos Backup");
        lines.add("#");
        lines.add(
                "# These values load once when Minecraft starts (client or dedicated server).");
        lines.add("# Editing this file requires a full restart. Reloading the world does not apply changes.");
        lines.add("#");
        for (Entry entry : ENTRIES) {
            entry.render(lines, config);
            lines.add("");
        }
        lines.add("# Internal: Config format version (updated automatically when the layout changes).");
        lines.add(KEY_CONFIG_VERSION + " = " + CONFIG_VERSION);
        lines.add("");
        return String.join("\n", lines);
    }

    private static Entry string(final String key, final StringSet set, final StringGet get,
            final String... comments) {
        return new Entry() {
            @Override
            public void load(Config cfg, ModConfig out, ModConfig defaults) {
                set.set(out, cfg.getOrElse(key, get.get(defaults)));
            }

            @Override
            public void render(List<String> lines, ModConfig config) {
                addComments(lines, comments);
                lines.add(key + " = " + tomlStringLiteral(get.get(config)));
            }
        };
    }

    private static Entry bool(final String key, final BoolSet set, final BoolGet get,
            final String... comments) {
        return new Entry() {
            @Override
            public void load(Config cfg, ModConfig out, ModConfig defaults) {
                set.set(out, cfg.getOrElse(key, get.get(defaults)));
            }

            @Override
            public void render(List<String> lines, ModConfig config) {
                addComments(lines, comments);
                lines.add(key + " = " + get.get(config));
            }
        };
    }

    private static Entry integer(final String key, final IntSet set, final IntGet get,
            final String... comments) {
        return new Entry() {
            @Override
            public void load(Config cfg, ModConfig out, ModConfig defaults) {
                set.set(out, cfg.getIntOrElse(key, get.get(defaults)));
            }

            @Override
            public void render(List<String> lines, ModConfig config) {
                addComments(lines, comments);
                lines.add(key + " = " + get.get(config));
            }
        };
    }

    private static Entry compressionMethod(final String... comments) {
        return new Entry() {
            @Override
            public void load(Config cfg, ModConfig out, ModConfig defaults) {
                out.compressionMethod = CompressionMethod.fromTomlValue(
                        cfg.get(KEY_COMPRESSION_METHOD),
                        defaults.compressionMethod);
            }

            @Override
            public void render(List<String> lines, ModConfig config) {
                addComments(lines, comments);
                lines.add(KEY_COMPRESSION_METHOD + " = "
                        + tomlStringLiteral(config.compressionMethod.name().toLowerCase()));
            }
        };
    }

    private static Entry stringList(final String key, final ListSet set, final ListGet get,
            final String... comments) {
        return new Entry() {
            @Override
            public void load(Config cfg, ModConfig out, ModConfig defaults) {
                Object raw = cfg.get(key);
                if (!(raw instanceof List)) {
                    set.set(out, new ArrayList<String>(get.get(defaults)));
                    return;
                }
                List<?> fromFile = (List<?>) raw;
                List<String> parsed = new ArrayList<String>();
                for (Object o : fromFile) {
                    if (o == null) {
                        continue;
                    }
                    String s = o.toString().trim();
                    if (!s.isEmpty()) {
                        parsed.add(s);
                    }
                }
                set.set(out, parsed);
            }

            @Override
            public void render(List<String> lines, ModConfig config) {
                addComments(lines, comments);
                lines.add(key + " = [");
                List<String> list = get.get(config);
                if (list != null) {
                    for (int i = 0; i < list.size(); i++) {
                        String row = "  " + tomlStringLiteral(list.get(i));
                        if (i + 1 < list.size()) {
                            row += ",";
                        }
                        lines.add(row);
                    }
                }
                lines.add("]");
            }
        };
    }

    private static void addComments(List<String> lines, String[] comments) {
        for (String comment : comments) {
            lines.add(comment);
        }
    }
    
    private interface Entry {
        void load(Config cfg, ModConfig out, ModConfig defaults);

        void render(List<String> lines, ModConfig config);
    }

    private interface StringGet {
        String get(ModConfig c);
    }

    private interface StringSet {
        void set(ModConfig c, String v);
    }

    private interface BoolGet {
        boolean get(ModConfig c);
    }

    private interface BoolSet {
        void set(ModConfig c, boolean v);
    }

    private interface IntGet {
        int get(ModConfig c);
    }

    private interface IntSet {
        void set(ModConfig c, int v);
    }

    private interface ListGet {
        List<String> get(ModConfig c);
    }

    private interface ListSet {
        void set(ModConfig c, List<String> v);
    }

    private static String tomlStringLiteral(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private ChronosTomlSpec() {
    }
}

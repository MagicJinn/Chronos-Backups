package com.magicjinn.chronos.core.config;

/**
 * Single place for {@code chronos.toml} key names and the user-facing text
 * written on first run and on format migration.
 * Default numeric values stay in {@link ModConfig}. This class is responsible
 * for the structure of the config file. (see {@link #CONFIG_VERSION}).
 */
public final class ChronosTomlSpec {
    public static final String KEY_PRUNE_TIME_REQUIREMENT_SECONDS = "pruneTimeRequirementSeconds";
    public static final String KEY_BACKUP_INTERVAL_SECONDS = "backupIntervalSeconds";
    public static final String KEY_CONFIG_VERSION = "configVersion";

    // Track the internal config format version, update a config when outdated
    public static final int CONFIG_VERSION = 1;

    /**
     * Full file body: stable key order, comments tuned for reading in a text
     * editor.
     * The format version key is last so the on-disk layout stays easy to read from
     * top to bottom.
     */
    public static String renderDocument(ModConfig config) {
        return String.join("\n",
                "# Chronos Backup",
                "#",
                "# These values load once when Minecraft starts (client or dedicated server). Editing this file requires a full restart—reloading the world does not apply changes.",
                "#",
                "# Minimum playtime (in seconds) for a region to count toward snapshot pruning.",
                "# The lower the value, the more chunks are kept in the backup.",
                KEY_PRUNE_TIME_REQUIREMENT_SECONDS + " = " + config.pruneTimeRequirementSeconds,
                "",
                "# Seconds between automatic backup runs (whole numbers only).",
                "# Example: 1800 = every 30 minutes; 3600 = hourly.",
                KEY_BACKUP_INTERVAL_SECONDS + " = " + config.backupIntervalSeconds,
                "",
                "# Internal: Config format version (updated automatically when the layout changes).",
                KEY_CONFIG_VERSION + " = " + CONFIG_VERSION,
                "");
    }

    private ChronosTomlSpec() {
    }
}

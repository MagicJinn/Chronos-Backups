package com.magicjinn.chronos.core.config;

import java.util.List;

/**
 * Single place for {@code chronos.toml} key names and the user-facing text
 * written on first run and on format migration.
 * Default numeric values stay in {@link ModConfig}. This class is responsible
 * for the structure of the config file. (see {@link #CONFIG_VERSION}).
 */
public final class ChronosTomlSpec {
    public static final String KEY_PRUNE_TIME_REQUIREMENT_SECONDS = "pruneTimeRequirementSeconds";
    public static final String KEY_BACKUP_INTERVAL_SECONDS = "backupIntervalSeconds";
    public static final String KEY_COPY_BLACKLIST = "copyBlacklist";
    public static final String KEY_COMMAND_REQUIRED_PERMISSION_LEVEL = "commandRequiredPermissionLevel";
    public static final String KEY_CONFIG_VERSION = "configVersion";

    // Track the internal config format version, update a config when outdated
    public static final int CONFIG_VERSION = 4; // TODO: reset to 1 on 1.0.0

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
                "# Paths to exclude from the backup snapshot copy (names anywhere under the world, or relative paths",
                "# with /).",
                renderCopyBlacklistArray(config),
                "",
                "# Minimum permission level (0–4) required to run /chronos.",
                "# 4 matches highly sensitive vanilla commands (e.g. /stop); 0 allows any command source that can run commands.",
                KEY_COMMAND_REQUIRED_PERMISSION_LEVEL + " = " + config.commandRequiredPermissionLevel,
                "",
                "# Internal: Config format version (updated automatically when the layout changes).",
                KEY_CONFIG_VERSION + " = " + CONFIG_VERSION,
                "");
    }

    private static String renderCopyBlacklistArray(ModConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append(KEY_COPY_BLACKLIST).append(" = [\n");
        List<String> list = config.copyBlacklist;
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                String s = list.get(i);
                sb.append("  ").append(tomlStringLiteral(s));
                if (i + 1 < list.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
        }
        sb.append(']');
        return sb.toString();
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

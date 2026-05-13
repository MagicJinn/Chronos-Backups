package com.magicjinn.chronos.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ModConfig {
    /* The name of the folder that will contain the backups */
    public String backupFolderName = "chronos";

    public boolean pruneChunks = true;
    public int pruneTimeRequirementSeconds = 60 * 2; // 2 minutes of playtime
    /**
     * Maximum worker threads for pruning.
     * 0 (or less) means "auto" (pruner picks a sensible default).
     */
    public int pruneMaxWorkerThreads = 0;
    /** When false, automatic backups are disabled ({@code /chronos} manual backups still work). */
    public boolean scheduleBackups = true;
    /** Seconds between automatic backup runs (whole numbers only). */
    public int backupIntervalSeconds = 60 * 30; // 30 minutes

    /**
     * Permission level (0–4) required to run {@code /chronos}.
     */
    public int commandRequiredPermissionLevel = 4;

    /**
     * World-relative path segments or names to skip when copying the save for a
     * backup. A single segment (no {@code /}) matches any file or folder with that
     * name, while paths with {@code /} match as a prefix under the world root.
     */
    public List<String> copyBlacklist = new ArrayList<>(
            Arrays.asList(
                    "voxyserver",
                    "dynmap",
                    "bluemap",
                    "DistantHorizons.sqlite",
                    "DistantHorizons.sqlite-wal",
                    "DistantHorizons.sqlite-shm",
                    "ledger.sqlite"));
}

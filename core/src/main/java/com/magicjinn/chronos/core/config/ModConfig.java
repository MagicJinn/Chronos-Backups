package com.magicjinn.chronos.core.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ModConfig {
    public int pruneTimeRequirementSeconds = 60 * 5; // 5 minutes of playtime
    public int backupIntervalSeconds = 60 * 30; // 30 minutes

    /**
     * Parallel MCA prune workers. {@code 0} means automatic (CPU count, capped).
     */
    public int pruneWorkerThreads = 0;

    /**
     * World-relative path segments or names to skip when copying the save for a backup.
     * A single segment (no {@code /}) matches any file or folder with that name; paths with
     * {@code /} match as a prefix under the world root.
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

package com.magicjinn.chronos.core;

import java.nio.file.Path;

/**
 * Loader-provided facts about the running server (paths, world id, dedicated vs integrated).
 */
public interface ServerEnvironment {
    boolean isDedicatedServer();

    String getWorldName();

    Path getRunDirectory();

    /** Absolute path to the primary save root (overworld folder). */
    Path getWorldSaveRoot();
}

package com.magicjinn.chronos.core;

import java.nio.file.Path;

/**
 * Loader-provided facts about the running server (paths, world id, dedicated vs integrated).
 */
public interface ServerEnvironment {
    boolean isDedicatedServer();

    String getWorldName();

    Path getRunDirectory();

    /** Absolute path to the save root (directory containing {@code level.dat}). */
    Path getWorldSaveRoot();
}

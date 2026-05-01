package com.magicjinn.chronos.core;

import java.nio.file.Path;

/**
 * Loader-provided facts about the running server (paths, world id, dedicated vs integrated).
 */
public interface ServerEnvironment {
    boolean isDedicatedServer();

    String getWorldName();

    Path getRunDirectory();

    /** The game version of the running server (e.g. {@code 1.21.1} or {@code 1.12.2}). */
    String getMinecraftVersion();
}

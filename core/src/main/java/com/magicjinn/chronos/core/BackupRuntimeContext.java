package com.magicjinn.chronos.core;

import java.nio.file.Path;

/**
 * Runtime information needed to resolve backup targets.
 */
public final class BackupRuntimeContext {
    private final boolean dedicatedServer;
    private final String worldName;
    private final Path runDirectory;

    public BackupRuntimeContext(boolean dedicatedServer, String worldName, Path runDirectory) {
        this.dedicatedServer = dedicatedServer;
        this.worldName = worldName;
        this.runDirectory = runDirectory;
    }

    public boolean isDedicatedServer() {
        return dedicatedServer;
    }

    public String getWorldName() {
        return worldName;
    }

    public Path getRunDirectory() {
        return runDirectory;
    }
}

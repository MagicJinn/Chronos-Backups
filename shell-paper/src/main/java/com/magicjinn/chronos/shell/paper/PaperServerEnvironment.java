package com.magicjinn.chronos.shell.paper;

import com.magicjinn.chronos.core.ServerEnvironment;
import java.nio.file.Path;
import org.bukkit.Server;
import org.bukkit.World;

/** Bukkit {@link Server} paths for Paper (all supported versions). */
public final class PaperServerEnvironment implements ServerEnvironment {
    private static final String DEFAULT_WORLD_NAME = "world";

    private final Server server;

    public PaperServerEnvironment(Server server) {
        this.server = server;
    }

    @Override
    public boolean isDedicatedServer() {
        return true;
    }

    @Override
    public String getWorldName() {
        if (!server.getWorlds().isEmpty()) {
            return server.getWorlds().get(0).getName();
        }
        return DEFAULT_WORLD_NAME;
    }

    @Override
    public Path getRunDirectory() {
        return server.getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    @Override
    public Path getWorldSaveRoot() {
        World world = server.getWorlds().isEmpty() ? null : server.getWorlds().get(0);
        if (world != null) {
            return world.getWorldFolder().toPath().toAbsolutePath().normalize();
        }
        return getRunDirectory().resolve(getWorldName()).normalize();
    }
}

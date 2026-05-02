package com.magicjinn.chronos.shell.mojmap;

import com.magicjinn.chronos.core.ServerEnvironment;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Mojmap {@link MinecraftServer} — shared by Fabric and NeoForge lines using
 * official mappings.
 */
public final class MojmapServerEnvironment implements ServerEnvironment {
    private static final String DEFAULT_WORLD_NAME = "world";

    private final MinecraftServer server;

    public MojmapServerEnvironment(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isDedicatedServer() {
        return server.isDedicatedServer();
    }

    @Override
    public String getWorldName() {
        try {
            String name = server.getWorldData().getLevelName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        } catch (RuntimeException ignored) {
            // Fall through.
        }
        return DEFAULT_WORLD_NAME;
    }

    @Override
    public Path getRunDirectory() {
        return server.getServerDirectory().toAbsolutePath().normalize();
    }

    @Override
    public Path getWorldSaveRoot() {
        Path root = server.getWorldPath(LevelResource.ROOT);
        return root.toAbsolutePath().normalize();
    }
}

package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.ServerEnvironment;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.ForgeVersion;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.WorldServer;

/** MCP 1.12 {@link MinecraftServer} snapshot passed through stable mappings. */
public final class ForgeServerEnvironment implements ServerEnvironment {
    private static final String DEFAULT_WORLD_NAME = "world";

    private final MinecraftServer server;

    public ForgeServerEnvironment(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isDedicatedServer() {
        return server.isDedicatedServer();
    }

    @Override
    public String getWorldName() {
        if (server.isDedicatedServer()) {
            DedicatedServer dedicated = (DedicatedServer) server;
            String folder = dedicated.getFolderName();
            if (folder != null && !folder.trim().isEmpty()) {
                return folder;
            }
            return DEFAULT_WORLD_NAME;
        }

        WorldServer[] worlds = server.worlds;
        if (worlds != null && worlds.length > 0 && worlds[0] != null) {
            String name = worlds[0].getWorldInfo().getWorldName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        }
        return DEFAULT_WORLD_NAME;
    }

    @Override
    public Path getRunDirectory() {
        return server.getDataDirectory().toPath().toAbsolutePath().normalize();
    }
}

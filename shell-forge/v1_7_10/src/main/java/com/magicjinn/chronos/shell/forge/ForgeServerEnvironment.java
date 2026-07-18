package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.ServerEnvironment;
import com.magicjinn.chronos.shell.ChronosConstants;
import java.io.File;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.WorldServer;

public final class ForgeServerEnvironment implements ServerEnvironment {
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
            return ChronosConstants.DEFAULT_WORLD_NAME;
        }

        WorldServer[] worlds = server.worldServers;
        if (worlds != null && worlds.length > 0 && worlds[0] != null) {
            String name = worlds[0].getWorldInfo().getWorldName();
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
        }
        return ChronosConstants.DEFAULT_WORLD_NAME;
    }

    @Override
    public Path getRunDirectory() {
        return server.getFile(".").toPath().toAbsolutePath().normalize();
    }

    @Override
    public Path getWorldSaveRoot() {
        if (server.isDedicatedServer()) {
            return getRunDirectory().resolve(getWorldName()).normalize();
        }
        WorldServer[] worlds = server.worldServers;
        if (worlds != null && worlds.length > 0 && worlds[0] != null) {
            File dir = worlds[0].getSaveHandler().getWorldDirectory();
            if (dir != null) {
                return dir.toPath().toAbsolutePath().normalize();
            }
        }
        return getRunDirectory().resolve("saves").resolve(getWorldName()).normalize();
    }
}

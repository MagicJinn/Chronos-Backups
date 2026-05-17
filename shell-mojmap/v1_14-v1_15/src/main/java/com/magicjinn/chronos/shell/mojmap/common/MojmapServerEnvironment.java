package com.magicjinn.chronos.shell.mojmap.common;

import com.magicjinn.chronos.core.ServerEnvironment;
import java.io.File;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Minecraft 1.14-1.17 - paths derived from
 * {@link ServerLevel#getLevelStorage()} (no {@code LevelResource}).
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
        String id = server.getLevelIdName();
        if (id != null && !id.trim().isEmpty()) {
            return id;
        }
        return DEFAULT_WORLD_NAME;
    }

    @Override
    public Path getRunDirectory() {
        Object root = server.getServerDirectory();
        if (root instanceof Path) {
            return ((Path) root).toAbsolutePath().normalize();
        }
        return ((File) root).toPath().toAbsolutePath().normalize();
    }

    @Override
    public Path getWorldSaveRoot() {
        ServerLevel overworld = server.getLevel(DimensionType.OVERWORLD);
        if (overworld != null) {
            return overworld.getLevelStorage().getFolder().toPath().toAbsolutePath().normalize();
        }
        if (server.isDedicatedServer()) {
            return getRunDirectory().resolve(getWorldName()).normalize();
        }
        return getRunDirectory().resolve("saves").resolve(getWorldName()).normalize();
    }
}

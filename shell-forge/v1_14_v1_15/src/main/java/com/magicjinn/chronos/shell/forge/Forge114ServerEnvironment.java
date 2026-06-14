package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.ServerEnvironment;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;

/** Forge 1.14.x server paths without linking 1.14.4-only {@code getLevel} descriptors. */
public final class Forge114ServerEnvironment implements ServerEnvironment {
    private final MinecraftServer server;

    public Forge114ServerEnvironment(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isDedicatedServer() {
        return server.isDedicatedServer();
    }

    @Override
    public String getWorldName() {
        return Forge114Reflect.getWorldName(server);
    }

    @Override
    public Path getRunDirectory() {
        return Forge114Reflect.getRunDirectory(server);
    }

    @Override
    public Path getWorldSaveRoot() {
        return Forge114Reflect.getWorldSaveRoot(server);
    }
}

package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.ServerEnvironment;
import com.magicjinn.chronos.shell.mojmap.common.MojmapServerEnvironment;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;

public final class Forge114ServerEnvironment implements ServerEnvironment {
    private final MojmapServerEnvironment delegate;

    public Forge114ServerEnvironment(MinecraftServer server) {
        this.delegate = new MojmapServerEnvironment(server);
    }

    @Override
    public boolean isDedicatedServer() {
        return delegate.isDedicatedServer();
    }

    @Override
    public String getWorldName() {
        return delegate.getWorldName();
    }

    @Override
    public Path getRunDirectory() {
        return delegate.getRunDirectory();
    }

    @Override
    public Path getWorldSaveRoot() {
        return delegate.getWorldSaveRoot();
    }
}


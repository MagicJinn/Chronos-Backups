package com.magicjinn.chronos.shell.mojmap.common;

import com.magicjinn.chronos.core.ShellMessenger;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/** Minecraft 1.16-1.17 logging and chat using /tellraw. */
public final class MojmapShellMessenger implements ShellMessenger {
    private final Supplier<MinecraftServer> server;

    public MojmapShellMessenger(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public void sendChat(String command) {
        MinecraftServer mcServer = server.get();
        if (mcServer == null || command == null || command.trim().isEmpty()) {
            return;
        }
        mcServer.getCommands().performCommand(
                mcServer.createCommandSourceStack(),
                "/" + command);
    }
}

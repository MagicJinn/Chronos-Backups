package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.ShellMessenger;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/** Shared Forge shell logging/chat for Minecraft 1.9-1.10. */
public final class ForgeShellMessenger implements ShellMessenger {
    private final Supplier<MinecraftServer> server;

    public ForgeShellMessenger(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public void sendChat(String command) {
        MinecraftServer mcServer = server.get();
        if (mcServer == null || command == null || command.trim().isEmpty()) {
            return;
        }
        mcServer.getCommandManager().executeCommand(mcServer, command);
    }
}

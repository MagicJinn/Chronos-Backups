package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.ShellMessenger;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/**
 * Forge 1.13 logging and chat. Chronos only invokes this from the server tick
 * drain, so no off-thread scheduling is needed.
 */
public final class ForgeShellMessenger implements ShellMessenger {
    private final Supplier<MinecraftServer> server;

    public ForgeShellMessenger(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public void sendChat(String command) {
        MinecraftServer mcServer = server.get();
        if (mcServer == null || command == null || command.trim().isEmpty())
            return;

        mcServer.getCommandManager().handleCommand(mcServer.getCommandSource(), command);
    }
}

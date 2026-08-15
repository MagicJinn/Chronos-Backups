package com.magicjinn.chronos.shell.mojmap.common;

import com.magicjinn.chronos.core.ChronosLogger;
import com.magicjinn.chronos.core.ShellMessenger;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/** Logging and chat for Mojmap servers (Fabric / NeoForge) using Brigadier dispatch. */
public final class MojmapShellMessenger implements ShellMessenger {
    private final Supplier<MinecraftServer> server;

    public MojmapShellMessenger(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public void sendChat(String command) {
        MinecraftServer mcServer = server.get();
        if (mcServer == null || command == null || command.trim().isEmpty())
            return;
        CommandSourceStack source = mcServer.createCommandSourceStack();
        ParseResults<CommandSourceStack> parsed = mcServer.getCommands().getDispatcher().parse(command, source);
        try {
            mcServer.getCommands().getDispatcher().execute(parsed);
        } catch (CommandSyntaxException e) {
            ChronosLogger.error("Failed to execute chat command: " + e.getMessage());
        }
    }
}

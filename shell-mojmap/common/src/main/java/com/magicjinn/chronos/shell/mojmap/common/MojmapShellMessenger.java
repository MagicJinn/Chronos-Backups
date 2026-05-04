package com.magicjinn.chronos.shell.mojmap.common;

import com.magicjinn.chronos.core.ShellMessenger;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logging and chat for Mojmap servers (Fabric / NeoForge). */
public final class MojmapShellMessenger implements ShellMessenger {
    private static final Logger LOG = LoggerFactory.getLogger("ChronosBackup");

    private final Supplier<MinecraftServer> server;

    public MojmapShellMessenger(Supplier<MinecraftServer> server) {
        this.server = server;
    }

    @Override
    public void logInfo(String message) {
        LOG.info(message);
    }

    @Override
    public void logError(String message) {
        LOG.error(message);
    }

    @Override
    public void sendChat(String message) {
        MinecraftServer mcServer = server.get();
        if (mcServer == null || message == null || message.trim().isEmpty()) {
            return;
        }
        mcServer.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }
}

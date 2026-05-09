package com.magicjinn.chronos.shell.mojmap.common;

import com.magicjinn.chronos.core.ShellMessenger;
import com.magicjinn.chronos.shell.ChronosConstants;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Logging and chat for Mojmap servers (Fabric / NeoForge). */
public final class MojmapShellMessenger implements ShellMessenger {
    private static final Logger LOG = LogManager.getLogger(ChronosConstants.LOG_NAME);

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

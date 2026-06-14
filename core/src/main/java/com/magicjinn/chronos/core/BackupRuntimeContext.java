package com.magicjinn.chronos.core;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Runtime information needed to resolve backup targets.
 */
public final class BackupRuntimeContext {
    public static final String CHAT_PREFIX = "[Chronos] ";

    private static final Logger LOG = Logger.getLogger(BackupRuntimeContext.class.getName());

    private final ServerEnvironment environment;
    private final Object serverHandle;
    private final BackupWorldController worldController;
    private final Consumer<String> logInfoSink;
    private final Consumer<String> logErrorSink;
    private final Consumer<String> chatSink;
    private final ChatCommandStyle chatCommandStyle;

    public BackupRuntimeContext(
            ServerEnvironment environment,
            Object serverHandle,
            BackupWorldController worldController,
            Consumer<String> logInfoSink,
            Consumer<String> logErrorSink,
            Consumer<String> chatSink) {
        this(
                environment,
                serverHandle,
                worldController,
                logInfoSink,
                logErrorSink,
                chatSink,
                ChatCommandStyle.MODERN_TELLRAW);
    }

    public BackupRuntimeContext(
            ServerEnvironment environment,
            Object serverHandle,
            BackupWorldController worldController,
            Consumer<String> logInfoSink,
            Consumer<String> logErrorSink,
            Consumer<String> chatSink,
            ChatCommandStyle chatCommandStyle) {
        this.environment = environment;
        this.serverHandle = serverHandle;
        this.worldController = worldController;
        this.logInfoSink = logInfoSink;
        this.logErrorSink = logErrorSink;
        this.chatSink = chatSink;
        this.chatCommandStyle = chatCommandStyle != null ? chatCommandStyle : ChatCommandStyle.MODERN_TELLRAW;
    }

    public boolean isDedicatedServer() {
        return environment.isDedicatedServer();
    }

    public String getWorldName() {
        return environment.getWorldName();
    }

    public Path getRunDirectory() {
        return environment.getRunDirectory();
    }

    public Path getWorldSaveRoot() {
        return environment.getWorldSaveRoot();
    }

    public Object getServerHandle() {
        return serverHandle;
    }

    public BackupWorldController getWorldController() {
        return worldController;
    }

    public void logInfo(String message) {
        if (logInfoSink != null) {
            logInfoSink.accept(message);
            return;
        }
        LOG.info(message);
    }

    public void logError(String message) {
        if (logErrorSink != null) {
            logErrorSink.accept(message);
            return;
        }
        LOG.severe(message);
    }

    public void sendChat(String message) {
        if (chatSink != null) {
            String command = chatCommandStyle == ChatCommandStyle.LEGACY_SAY
                    ? ChatHelper.makeLegacySay(message)
                    : ChatHelper.makeModernTellraw(CHAT_PREFIX + message);
            chatSink.accept(command);
        }
    }
}

package com.magicjinn.chronos.core;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Runtime information needed to resolve backup targets.
 */
public final class BackupRuntimeContext {
    public static final String CHAT_PREFIX = "[Chronos] ";

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
        ChronosLogger.info(message);
    }

    public void logError(String message) {
        if (logErrorSink != null) {
            logErrorSink.accept(message);
            return;
        }
        ChronosLogger.error(message);
    }

    /**
     * Queues a chat line for delivery on the next Chronos server-tick drain.
     * Never dispatches commands on the calling thread (safe from the backup
     * worker).
     */
    public void sendChat(String message) {
        if (message == null || message.isEmpty())
            return;

        Backupper.enqueueServerChat(this, message);
    }

    /**
     * Formats and dispatches a chat line via the shell messenger. Only
     * {@link Backupper} should call this while draining the server-tick queue.
     */
    void deliverChat(String message) {
        if (chatSink == null || message == null || message.isEmpty())
            return;

        String command = chatCommandStyle == ChatCommandStyle.LEGACY_SAY
                ? ChatHelper.makeLegacySay(message)
                : ChatHelper.makeModernTellraw(CHAT_PREFIX + message);
        chatSink.accept(command);
    }
}

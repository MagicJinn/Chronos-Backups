package com.magicjinn.chronos.core;

/**
 * Loader hooks for log lines and in-game chat used by the backup runtime.
 */
public interface ShellMessenger {
    default void logInfo(String message) {
        ChronosLogger.info(message);
    }

    default void logError(String message) {
        ChronosLogger.error(message);
    }

    void sendChat(String message);
}

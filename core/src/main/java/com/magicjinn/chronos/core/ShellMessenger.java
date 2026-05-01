package com.magicjinn.chronos.core;

/**
 * Loader hooks for log lines and in-game chat used by the backup runtime.
 */
public interface ShellMessenger {
    void logInfo(String message);

    void logError(String message);

    void sendChat(String message);
}

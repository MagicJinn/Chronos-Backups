package com.magicjinn.chronos.shell;

import com.magicjinn.chronos.core.BackupRuntimeContext;
import com.magicjinn.chronos.core.BackupWorldController;
import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.core.Scheduler;
import com.magicjinn.chronos.core.ServerEnvironment;
import com.magicjinn.chronos.core.ShellMessenger;
import java.util.logging.Logger;

/**
 * Glue between loader-specific hooks and the version-agnostic core.
 */
public final class HookBridge {
    private static final Logger FALLBACK_LOG = Logger.getLogger(HookBridge.class.getName());

    private static final ShellMessenger FALLBACK_MESSENGER =
            new ShellMessenger() {
                @Override
                public void logInfo(String message) {
                    FALLBACK_LOG.info(message);
                }

                @Override
                public void logError(String message) {
                    FALLBACK_LOG.severe(message);
                }

                @Override
                public void sendChat(String message) {
                    // No server handle / messenger — chat is unavailable.
                }
            };

    public static void worldStarted(
            ServerEnvironment environment,
            Object serverHandle,
            ShellMessenger messenger,
            BackupWorldController worldController) {
        if (environment == null) {
            throw new IllegalStateException("Missing ServerEnvironment when calling HookBridge.worldStarted.");
        }
        if (worldController == null) {
            throw new IllegalStateException(
                    "Missing BackupWorldController when calling HookBridge.worldStarted.");
        }
        BackupRuntimeContext context = buildRuntimeContext(environment, serverHandle, messenger, worldController);
        context.logInfo("Chronos is initializing...");
        Core.OnWorldStarted(context);
    }

    public static void worldStopped() {
        Core.OnWorldStopped();
    }

    private HookBridge() {}

    private static BackupRuntimeContext buildRuntimeContext(
            ServerEnvironment environment,
            Object serverHandle,
            ShellMessenger messenger,
            BackupWorldController worldController) {
        ShellMessenger resolved = messenger != null ? messenger : FALLBACK_MESSENGER;
        return new BackupRuntimeContext(
                environment,
                serverHandle,
                worldController,
                resolved::logInfo,
                resolved::logError,
                resolved::sendChat);
    }
}

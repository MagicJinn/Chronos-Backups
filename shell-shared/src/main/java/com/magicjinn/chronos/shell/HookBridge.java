package com.magicjinn.chronos.shell;

import com.magicjinn.chronos.core.BackupRuntimeContext;
import com.magicjinn.chronos.core.BackupWorldController;
import com.magicjinn.chronos.core.Scheduler;
import com.magicjinn.chronos.core.ServerEnvironment;
import com.magicjinn.chronos.core.ShellMessenger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Glue between loader-specific hooks and the version-agnostic core.
 */
public final class HookBridge {
    private static final Logger FALLBACK_LOG = Logger.getLogger(HookBridge.class.getName());

    private static final AtomicBoolean WORLD_START_FIRED = new AtomicBoolean(false);

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
        if (!WORLD_START_FIRED.compareAndSet(false, true)) {
            return;
        }
        if (environment == null) {
            throw new IllegalStateException("Missing ServerEnvironment when calling HookBridge.worldStarted.");
        }
        if (worldController == null) {
            throw new IllegalStateException(
                    "Missing BackupWorldController when calling HookBridge.worldStarted.");
        }
        BackupRuntimeContext context = buildRuntimeContext(environment, serverHandle, messenger, worldController);
        context.logInfo("Hook checking in");
        Scheduler.onWorldStarted(context);
    }

    public static void worldStopped() {
        WORLD_START_FIRED.set(false);
        Scheduler.onWorldStopped();
    }

    private HookBridge() {}

    private static BackupRuntimeContext buildRuntimeContext(
            ServerEnvironment environment,
            Object serverHandle,
            ShellMessenger messenger,
            BackupWorldController worldController) {
        ShellMessenger resolved = messenger != null ? messenger : FALLBACK_MESSENGER;
        return new BackupRuntimeContext(
                environment.isDedicatedServer(),
                environment.getWorldName(),
                environment.getRunDirectory(),
                serverHandle,
                worldController,
                resolved::logInfo,
                resolved::logError,
                resolved::sendChat);
    }
}

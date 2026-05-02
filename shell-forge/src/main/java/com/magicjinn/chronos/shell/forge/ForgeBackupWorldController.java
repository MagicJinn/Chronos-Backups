package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.BackupWorldController;
import java.util.concurrent.ExecutionException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * Forge 1.12 — flush players and loaded chunks ({@link MinecraftServer#saveAllWorlds}),
 * then toggle {@link WorldServer#disableLevelSaving} while copying files.
 *
 * <p>All touches to the server and worlds run on the server thread; calling {@link
 * MinecraftServer#saveAllWorlds} from a worker thread is undefined and often results in a silent
 * no-op or inconsistent saves.
 */
public final class ForgeBackupWorldController implements BackupWorldController {
    private static final String SERVER_THREAD_NAME = "Server thread";

    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        runOnServerThread(
                server,
                () -> {
                    server.getPlayerList().saveAllPlayerData();
                    // Full dimension flush to disk (closest to modern MinecraftServer.saveEverything).
                    // true = suppress per-chunk save spam in logs during scheduled backups.
                    server.saveAllWorlds(true);
                });
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return false;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        boolean[] updatedAny = new boolean[1];
        runOnServerThread(
                server,
                () -> {
                    WorldServer[] worlds = server.worlds;
                    if (worlds == null) {
                        return;
                    }
                    for (WorldServer world : worlds) {
                        if (world == null) {
                            continue;
                        }
                        world.disableLevelSaving = disabled;
                        updatedAny[0] = true;
                    }
                });
        return updatedAny[0];
    }

    private static void runOnServerThread(MinecraftServer server, Runnable task) {
        if (SERVER_THREAD_NAME.equals(Thread.currentThread().getName())) {
            task.run();
            return;
        }
        try {
            server.addScheduledTask(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RuntimeException(cause != null ? cause : e);
        }
    }
}

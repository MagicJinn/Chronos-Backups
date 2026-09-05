package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.BackupWorldController;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * Forge 1.12 - flush players and loaded chunks
 * ({@link MinecraftServer#saveAllWorlds}), then toggle
 * {@link WorldServer#disableLevelSaving} while copying files.
 */
public final class ForgeBackupWorldController implements BackupWorldController {

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
                    // Full dimension flush to disk (closest to modern
                    // MinecraftServer.saveEverything).
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
        // Chronos only calls world ops from the server tick drain.
        task.run();
    }
}

package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.BackupWorldController;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * Forge 1.13 - flush players and chunks
 * ({@link MinecraftServer#saveAllWorlds}), then toggle
 * {@link WorldServer#disableLevelSaving} while copying files. Uses Forge's
 * world map so every loaded dimension is covered, including mod-registered
 * {@link net.minecraft.world.dimension.DimensionType}s.
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
                    // MinecraftServer.saveEverything). true = suppress per-chunk save spam in logs
                    // during scheduled backups.
                    server.saveAllWorlds(true);
                });
    }

    @Override
    @SuppressWarnings("deprecation") // 1.13 doesn't recieve updates anymore
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return false;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        boolean[] updatedAny = new boolean[1];
        runOnServerThread(
                server,
                () -> {
                    for (WorldServer world : server.forgeGetWorldMap().values()) {
                        if (world != null) {
                            world.disableLevelSaving = disabled;
                            updatedAny[0] = true;
                        }
                    }
                });
        return updatedAny[0];
    }

    private static void runOnServerThread(MinecraftServer server, Runnable task) {
        // Chronos only calls world ops from the server tick drain.
        task.run();
    }
}

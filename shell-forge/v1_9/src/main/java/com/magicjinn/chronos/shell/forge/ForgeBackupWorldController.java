package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.BackupWorldController;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/** Forge-specific world save toggle used by backup flow. */
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
                    WorldServer[] worlds = server.worldServers;
                    if (worlds == null) {
                        return;
                    }
                    for (WorldServer world : worlds) {
                        if (world == null) {
                            continue;
                        }
                        try {
                            world.saveAllChunks(true, null);
                        }catch (Exception e) {
                            System.err.println("Exception while saving world chunks: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
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
                    WorldServer[] worlds = server.worldServers;
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

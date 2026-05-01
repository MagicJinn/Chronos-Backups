package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.BackupWorldController;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * Forge 1.12 — flush players and loaded chunks ({@link MinecraftServer#saveAllWorlds}),
 * then toggle {@link WorldServer#disableLevelSaving} while copying files.
 */
public final class ForgeBackupWorldController implements BackupWorldController {
    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        server.getPlayerList().saveAllPlayerData();
        // Full dimension flush to disk (closest to modern MinecraftServer.saveEverything).
        // true = suppress per-chunk save spam in logs during scheduled backups.
        server.saveAllWorlds(true);
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return false;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;

        WorldServer[] worlds = server.worlds;
        if (worlds == null) {
            return false;
        }

        boolean updatedAny = false;
        for (WorldServer world : worlds) {
            if (world == null) {
                continue;
            }
            world.disableLevelSaving = disabled;
            updatedAny = true;
        }
        return updatedAny;
    }
}

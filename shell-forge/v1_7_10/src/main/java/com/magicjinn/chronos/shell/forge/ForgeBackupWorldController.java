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
        server.getConfigurationManager().saveAllPlayerData();
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return false;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        WorldServer[] worlds = server.worldServers;
        boolean updatedAny = false;
        if (worlds == null) {
            return false;
        }
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

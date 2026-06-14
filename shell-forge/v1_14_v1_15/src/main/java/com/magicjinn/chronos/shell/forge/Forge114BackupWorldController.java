package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.BackupWorldController;
import net.minecraft.server.MinecraftServer;

/**
 * Forge 1.14.x world flush/toggle logic safe across 1.14.2-1.14.4 Forge remaps.
 */
public final class Forge114BackupWorldController implements BackupWorldController {
    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return;
        }
        Forge114Reflect.saveAllWorldData((MinecraftServer) serverHandle);
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return false;
        }
        return Forge114Reflect.setWorldSavingDisabled((MinecraftServer) serverHandle, disabled);
    }
}

package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.BackupWorldController;
import com.magicjinn.chronos.shell.mojmap.common.MojmapBackupWorldController;
import net.minecraft.server.MinecraftServer;

public final class Forge114BackupWorldController implements BackupWorldController {
    private static final MojmapBackupWorldController DELEGATE = new MojmapBackupWorldController();

    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return;
        }
        DELEGATE.saveAllWorldData(serverHandle);
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return false;
        }
        return DELEGATE.setWorldSavingDisabled(serverHandle, disabled);
    }
}


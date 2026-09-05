package com.magicjinn.chronos.shell.mojmap.common;

import com.magicjinn.chronos.core.BackupWorldController;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Minecraft 1.14-1.15: {@link MinecraftServer#saveAllChunks} + per-dimension
 * {@link ServerLevel#noSave} (no {@code saveEverything}). Chronos only calls
 * world ops from the server tick drain.
 */
public final class MojmapBackupWorldController implements BackupWorldController {

    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        server.getPlayerList().saveAll();
        server.saveAllChunks(true, true, true);
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer))
            return false;

        MinecraftServer server = (MinecraftServer) serverHandle;
        boolean touched = false;
        for (ServerLevel level : server.getAllLevels()) {
            if (level != null && level.noSave != disabled) {
                level.noSave = disabled;
                touched = true;
            }
        }
        return touched;
    }
}

package com.magicjinn.chronos.shell.mojmap.common;

import com.magicjinn.chronos.core.BackupWorldController;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Saves all world data using {@link MinecraftServer#saveEverything}, then toggles
 * each dimension's {@link ServerLevel#noSave}. This matches {@code FabricBackupWorldController},
 * allowing one implementation to support 1.20 and newer (since 1.20.x lacks
 * {@code MinecraftServer#setAutoSave}).
 */
public final class MojmapBackupWorldController implements BackupWorldController {
    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        server.executeBlocking(() -> server.saveEverything(true, false, true));
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return false;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        AtomicBoolean touchedLevel = new AtomicBoolean(false);
        server.executeBlocking(
                () -> {
                    for (ServerLevel level : server.getAllLevels()) {
                        if (level == null) {
                            continue;
                        }
                        if (level.noSave != disabled) {
                            level.noSave = disabled;
                        }
                        touchedLevel.set(true);
                    }
                });
        return touchedLevel.get();
    }
}

package com.magicjinn.chronos.shell.mojmap;

import com.magicjinn.chronos.core.BackupWorldController;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Mojmap: flush with {@link MinecraftServer#saveEverything}, then toggle each dimension's
 * {@link ServerLevel#noSave}. Matches {@code FabricBackupWorldController} so one implementation
 * works from 1.20 through current (1.20.x did not expose {@code MinecraftServer#setAutoSave}).
 */
public final class MojmapBackupWorldController implements BackupWorldController {
    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof MinecraftServer server)) {
            return;
        }
        server.executeBlocking(() -> server.saveEverything(true, false, true));
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer server)) {
            return false;
        }
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

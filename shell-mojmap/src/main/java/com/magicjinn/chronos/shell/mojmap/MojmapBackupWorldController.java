package com.magicjinn.chronos.shell.mojmap;

import com.magicjinn.chronos.core.BackupWorldController;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

/**
 * Mojmap: flush with {@link MinecraftServer#saveEverything}, then pause/resume
 * persistence using the
 * vanilla public {@link MinecraftServer#setAutoSave} API (same {@code noSave}
 * toggle vanilla uses).
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
        AtomicBoolean changed = new AtomicBoolean(false);
        server.executeBlocking(() -> changed.set(server.setAutoSave(!disabled)));
        return changed.get();
    }
}

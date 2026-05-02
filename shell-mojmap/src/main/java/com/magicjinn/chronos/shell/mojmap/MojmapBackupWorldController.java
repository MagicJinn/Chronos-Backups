package com.magicjinn.chronos.shell.mojmap;

import com.magicjinn.chronos.core.BackupWorldController;
import net.minecraft.server.MinecraftServer;

/**
 * Mojmap: flush with {@link MinecraftServer#saveEverything}, then pause/resume
 * persistence using the vanilla {@link MinecraftServer#setAutoSave} API.
 *
 * <p>Autosave state before pause is stored on {@link ThreadLocal} because the backup worker
 * thread runs pause and restore sequentially; restore reapplies the exact prior flag so backups
 * never leave the server stuck with saving off when {@code setAutoSave} return values are
 * ambiguous.
 */
public final class MojmapBackupWorldController implements BackupWorldController {
    private static final ThreadLocal<Boolean> AUTOSAVE_BEFORE_PAUSE = new ThreadLocal<>();

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
        server.executeBlocking(
                () -> {
                    if (disabled) {
                        boolean was = server.isAutoSave();
                        AUTOSAVE_BEFORE_PAUSE.set(was);
                        server.setAutoSave(false);
                    } else {
                        Boolean was = AUTOSAVE_BEFORE_PAUSE.get();
                        AUTOSAVE_BEFORE_PAUSE.remove();
                        if (was != null && was.booleanValue()) {
                            server.setAutoSave(true);
                        }
                    }
                });
        return true;
    }
}

package com.magicjinn.chronos.shell.mojmap.common;

import com.magicjinn.chronos.core.BackupWorldController;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Minecraft 1.16-1.17 - chunk saves via {@link MinecraftServer#saveAllChunks};
 * separate from
 * {@code shell-mojmap/common} which targets {@code saveEverything} (1.18+).
 */
public final class MojmapBackupWorldController implements BackupWorldController {
    private static final String SERVER_THREAD_NAME = "Server thread";

    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        runOnServerThread(
                server,
                () -> {
                    server.getPlayerList().saveAll();
                    server.saveAllChunks(true, true, true);
                });
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return false;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        boolean[] touched = new boolean[1];
        runOnServerThread(
                server,
                () -> {
                    for (ServerLevel level : server.getAllLevels()) {
                        if (level != null && level.noSave != disabled) {
                            level.noSave = disabled;
                            touched[0] = true;
                        }
                    }
                });
        return touched[0];
    }

    private static void runOnServerThread(MinecraftServer server, Runnable task) {
        if (SERVER_THREAD_NAME.equals(Thread.currentThread().getName())) {
            task.run();
            return;
        }
        server.submit(task).join();
    }
}

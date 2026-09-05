package com.magicjinn.chronos.shell.mojmap.common;

import com.magicjinn.chronos.core.BackupWorldController;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Saves all world data using {@link MinecraftServer#saveEverything}, then
 * toggles
 * each dimension's {@link ServerLevel#noSave}. This matches
 * {@code FabricBackupWorldController},
 * allowing one implementation to support 1.20 and newer (since 1.20.x lacks
 * {@code MinecraftServer#setAutoSave}).
 *
 * <p>
 * The middle {@code saveEverything} argument is {@code flush}: {@code true} so
 * the call blocks
 * until chunk/level data has been written through to storage (not merely
 * scheduled), which is
 * required before filesystem backups on Windows.
 */
public final class MojmapBackupWorldController implements BackupWorldController {

    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        runServerBlocking(
                server,
                () ->
                // suppressLogs, flush, force
                // flush must be true so saves complete before copy
                server.saveEverything(true, true, true));
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return false;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        AtomicBoolean touchedLevel = new AtomicBoolean(false);
        runServerBlocking(
                        server,
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

    private static void runServerBlocking(MinecraftServer server, Runnable task) {
        // Chronos only calls world ops from the server tick drain.
        task.run();
    }
}

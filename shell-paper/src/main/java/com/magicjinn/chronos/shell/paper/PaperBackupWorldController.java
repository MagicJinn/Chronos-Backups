package com.magicjinn.chronos.shell.paper;

import com.magicjinn.chronos.core.BackupWorldController;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;

/** Flushes and pauses world saves through the stable Bukkit API. */
public final class PaperBackupWorldController implements BackupWorldController {
    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof Server)) {
            return;
        }
        Server server = (Server) serverHandle;
        runOnMainThread(
                server,
                () -> {
                    for (World world : server.getWorlds()) {
                        if (world != null) {
                            world.save();
                        }
                    }
                    server.savePlayers();
                });
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof Server)) {
            return false;
        }
        Server server = (Server) serverHandle;
        runOnMainThread(
                server,
                () -> {
                    for (World world : server.getWorlds()) {
                        if (world != null) {
                            world.setAutoSave(!disabled);
                        }
                    }
                });
        return true;
    }

    private static void runOnMainThread(Server server, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        Bukkit.getScheduler().runTask(PaperRuntime.plugin(server), task);
    }
}

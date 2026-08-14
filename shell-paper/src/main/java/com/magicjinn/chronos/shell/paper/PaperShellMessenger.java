package com.magicjinn.chronos.shell.paper;

import com.magicjinn.chronos.core.ShellMessenger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperShellMessenger implements ShellMessenger {
    private final JavaPlugin plugin;

    PaperShellMessenger(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void sendChat(String command) {
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        String line = command;
        if (line.charAt(0) == '/') {
            line = line.substring(1);
        }
        String dispatchLine = line;
        Runnable dispatch =
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), dispatchLine);
        if (PaperSchedulers.runsOnGlobalThread()) {
            dispatch.run();
        } else {
            PaperSchedulers.runGlobal(plugin, dispatch);
        }
    }
}

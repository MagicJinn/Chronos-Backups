package com.magicjinn.chronos.shell.paper;

import com.magicjinn.chronos.core.ShellMessenger;
import org.bukkit.Bukkit;

/**
 * Paper/Bukkit chat dispatch. Chronos only invokes this from the server tick
 * drain (global region on Folia), so no off-thread scheduling is needed.
 */
final class PaperShellMessenger implements ShellMessenger {
    @Override
    public void sendChat(String command) {
        if (command == null || command.trim().isEmpty())
            return;

        String line = command;
        if (line.charAt(0) == '/')
            line = line.substring(1);

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
    }
}

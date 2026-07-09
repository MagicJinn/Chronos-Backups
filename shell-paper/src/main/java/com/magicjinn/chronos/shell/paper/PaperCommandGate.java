package com.magicjinn.chronos.shell.paper;

import com.magicjinn.chronos.core.config.Config;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class PaperCommandGate {
    private PaperCommandGate() {}

    static boolean mayExecute(CommandSender sender) {
        int required = Config.getCommandRequiredPermissionLevel();
        if (required <= 0) {
            return true;
        }
        if (!(sender instanceof Player)) {
            return true;
        }
        return sender.isOp();
    }
}

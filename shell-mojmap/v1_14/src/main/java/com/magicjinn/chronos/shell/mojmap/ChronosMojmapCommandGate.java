package com.magicjinn.chronos.shell.mojmap;

import com.magicjinn.chronos.core.config.Config;

import net.minecraft.commands.CommandSourceStack;

/**
 * Mojmap {@link CommandSourceStack} permission gate for versions with numeric
 * {@link CommandSourceStack#hasPermission(int)} checks (1.14+ through 1.21.10).
 */
public final class ChronosMojmapCommandGate {
    private ChronosMojmapCommandGate() {
    }

    public static boolean mayExecute(CommandSourceStack source) {
        return source.hasPermission(Config.getCommandRequiredPermissionLevel());
    }
}

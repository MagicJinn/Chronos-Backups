package com.magicjinn.chronos.shell.mojmap;

import com.magicjinn.chronos.core.config.Config;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;

/**
 * Mojmap command permission for Minecraft 1.21.11+ where
 * {@link CommandSourceStack} carries a
 * {@link PermissionSet}, level-style checks use
 * {@link LevelBasedPermissionSet}.
 */
public final class ChronosMojmapCommandGate {
    private ChronosMojmapCommandGate() {
    }

    public static boolean mayExecute(CommandSourceStack source) {
        PermissionSet set = source.permissions();
        if (!(set instanceof LevelBasedPermissionSet lbs)) {
            return true;
        }
        int raw = Config.getCommandRequiredPermissionLevel();
        int req = Math.min(4, Math.max(0, raw));
        PermissionLevel required = PermissionLevel.byId(req);
        PermissionLevel have = lbs.level();
        return have.id() >= required.id();
    }
}

package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import net.minecraft.command.ServerCommandManager;

/**
 * Registers {@link ChronosBackupCommand}, shared so 1.7.10 does not pull
 * {@link ChronosForgeMod} from {@code forge-common-1_8-1_12}.
 */
final class ForgeCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        if (!(registrationContext instanceof ServerCommandManager)) {
            return;
        }
        ((ServerCommandManager) registrationContext).registerCommand(new ChronosBackupCommand());
    }
}

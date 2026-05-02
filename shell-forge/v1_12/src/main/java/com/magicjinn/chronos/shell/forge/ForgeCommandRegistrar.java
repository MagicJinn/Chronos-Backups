package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import net.minecraft.command.ServerCommandManager;

final class ForgeCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        if (!(registrationContext instanceof ServerCommandManager)) {
            return;
        }
        ((ServerCommandManager) registrationContext).registerCommand(new ChronosBackupCommand());
    }
}

package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandSource;

/* Forge 1.13 command registrar. */
final class ForgeCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        if (!(registrationContext instanceof CommandDispatcher)) {
            return;
        }
        @SuppressWarnings("unchecked")
        CommandDispatcher<CommandSource> dispatcher = (CommandDispatcher<CommandSource>) registrationContext;
        ChronosBackupCommand.register(dispatcher);
    }
}

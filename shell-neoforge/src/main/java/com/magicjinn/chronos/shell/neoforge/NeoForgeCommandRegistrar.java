package com.magicjinn.chronos.shell.neoforge;

import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;

final class NeoForgeCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        if (!(registrationContext instanceof CommandDispatcher)) {
            return;
        }
        @SuppressWarnings("unchecked")
        CommandDispatcher<CommandSourceStack> dispatcher =
                (CommandDispatcher<CommandSourceStack>) registrationContext;
        dispatcher.register(
                Commands.literal("chronos")
                        .then(
                                Commands.literal("backup")
                                        .executes(
                                                context ->
                                                        NeoForgeBackupCommands.runBackup(
                                                                context.getSource()))));
    }
}

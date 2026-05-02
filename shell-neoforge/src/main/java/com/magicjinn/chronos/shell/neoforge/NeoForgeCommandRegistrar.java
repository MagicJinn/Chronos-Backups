package com.magicjinn.chronos.shell.neoforge;

import com.magicjinn.chronos.shell.ChronosCommandActions;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class NeoForgeCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        if (!(registrationContext instanceof CommandDispatcher)) {
            return;
        }
        @SuppressWarnings("unchecked")
        CommandDispatcher<CommandSourceStack> dispatcher = (CommandDispatcher<CommandSourceStack>) registrationContext;
        dispatcher.register(
                Commands.literal("chronos")
                        .then(
                                Commands.literal("backup")
                                        .executes(
                                                context -> {
                                                    if (ChronosCommandActions.startManualBackup()) {
                                                        context.getSource()
                                                                .sendSuccess(
                                                                        () -> Component.literal(
                                                                                ChronosCommandActions
                                                                                        .messageManualBackupStarted()),
                                                                        false);
                                                        return Command.SINGLE_SUCCESS;
                                                    }
                                                    context.getSource()
                                                            .sendSuccess(
                                                                    () -> Component.literal(
                                                                            ChronosCommandActions
                                                                                    .messageRuntimeInactive()),
                                                                    false);
                                                    return 0;
                                                }))
                        .then(
                                Commands.literal("cancel")
                                        .executes(
                                                context -> {
                                                    if (ChronosCommandActions
                                                            .requestCancelInFlightBackup()) {
                                                        return Command.SINGLE_SUCCESS;
                                                    }
                                                    context.getSource()
                                                            .sendSuccess(
                                                                    () -> Component.literal(
                                                                            ChronosCommandActions
                                                                                    .messageCancelNothingRunning()),
                                                                    false);
                                                    return 0;
                                                })));
    }
}

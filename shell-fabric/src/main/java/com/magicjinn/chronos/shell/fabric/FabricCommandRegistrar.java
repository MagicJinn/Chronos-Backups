package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.shell.ChronosCommandActions;
import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

final class FabricCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> dispatcher.register(
                        Commands.literal("chronos")
                                .then(
                                        Commands.literal("backup")
                                                .executes(
                                                        context -> {
                                                            if (ChronosCommandActions.startManualBackup()) {
                                                                context
                                                                        .getSource()
                                                                        .sendSuccess(
                                                                                () -> Component.literal(
                                                                                        ChronosCommandActions
                                                                                                .messageManualBackupStarted()),
                                                                                false);
                                                                return 1;
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
                                                                return 1;
                                                            }
                                                            context.getSource()
                                                                    .sendSuccess(
                                                                            () -> Component.literal(
                                                                                    ChronosCommandActions
                                                                                            .messageCancelNothingRunning()),
                                                                            false);
                                                            return 0;
                                                        }))));
    }
}

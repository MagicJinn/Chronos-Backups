package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.shell.ShellCommandRegistrar;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;

final class FabricCommandRegistrar implements ShellCommandRegistrar {
    @Override
    public void register(Object registrationContext) {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> dispatcher.register(
                        Commands.literal("chronos")
                                .then(
                                        Commands.literal("backup")
                                                .executes(
                                                        context ->
                                                                FabricBackupCommands.runBackup(
                                                                        context.getSource())))));
    }
}

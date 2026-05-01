package com.magicjinn.chronos.shell.neoforge;

import com.magicjinn.chronos.core.BackupRuntimeContext;
import com.magicjinn.chronos.core.Scheduler;
import com.mojang.brigadier.Command;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class NeoForgeBackupCommands {
    private NeoForgeBackupCommands() {}

    static int runBackup(CommandSourceStack source) {
        if (Scheduler.runBackupNow()) {
            source.sendSuccess(
                    () -> Component.literal(BackupRuntimeContext.CHAT_PREFIX + "Manual backup started."),
                    false);
            return Command.SINGLE_SUCCESS;
        }
        source.sendSuccess(
                () -> Component.literal(BackupRuntimeContext.CHAT_PREFIX + "Backup runtime is not active yet."),
                false);
        return 0;
    }
}

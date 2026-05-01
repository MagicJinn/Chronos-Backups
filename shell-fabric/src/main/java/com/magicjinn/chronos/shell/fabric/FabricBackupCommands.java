package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.core.BackupRuntimeContext;
import com.magicjinn.chronos.core.Scheduler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class FabricBackupCommands {
    private FabricBackupCommands() {}

    static int runBackup(CommandSourceStack source) {
        if (Scheduler.runBackupNow()) {
            source.sendSuccess(
                    () -> Component.literal(BackupRuntimeContext.CHAT_PREFIX + "Manual backup started."),
                    false);
            return 1;
        }
        source.sendSuccess(
                () -> Component.literal(BackupRuntimeContext.CHAT_PREFIX + "Backup runtime is not active yet."),
                false);
        return 0;
    }
}

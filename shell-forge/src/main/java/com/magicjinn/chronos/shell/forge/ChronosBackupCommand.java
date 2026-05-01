package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.BackupRuntimeContext;
import com.magicjinn.chronos.core.Scheduler;
import java.util.Collections;
import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

final class ChronosBackupCommand extends CommandBase {
    @Override
    public String getName() {
        return "chronos";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/chronos backup";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length > 0 && "backup".equalsIgnoreCase(args[0])) {
            if (Scheduler.runBackupNow()) {
                sender.sendMessage(
                        new TextComponentString(BackupRuntimeContext.CHAT_PREFIX + "Manual backup started."));
                return;
            }
            sender.sendMessage(
                    new TextComponentString(BackupRuntimeContext.CHAT_PREFIX + "Backup runtime is not active yet."));
        }
    }
}

package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.shell.ChronosCommandActions;
import java.util.Collections;
import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

final class ChronosBackupCommand extends CommandBase {
    @Override
    public String getName() {
        return "chronos";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return ChronosCommandActions.USAGE_LINE;
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getTabCompletions(
            MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "backup", "cancel");
        }
        return Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            return;
        }
        if ("backup".equalsIgnoreCase(args[0])) {
            if (ChronosCommandActions.startManualBackup()) {
                sender.sendMessage(
                        new TextComponentString(ChronosCommandActions.messageManualBackupStarted()));
            } else {
                sender.sendMessage(
                        new TextComponentString(ChronosCommandActions.messageRuntimeInactive()));
            }
            return;
        }
        if ("cancel".equalsIgnoreCase(args[0])) {
            if (!ChronosCommandActions.requestCancelInFlightBackup()) {
                sender.sendMessage(
                        new TextComponentString(ChronosCommandActions.messageCancelNothingRunning()));
            }
        }
    }
}

package com.magicjinn.chronos.shell.forge;

import com.magicjinn.chronos.core.config.Config;
import com.magicjinn.chronos.shell.ChronosCommandActions;
import com.magicjinn.chronos.shell.ChronosCommandLiterals;
import com.magicjinn.chronos.shell.LegacyCommandSupport;
import java.util.Collections;
import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

final class ChronosBackupCommand extends CommandBase {
    @Override
    public int compareTo(Object other) {
        if (other instanceof ICommand) {
            return getCommandName().compareTo(((ICommand) other).getCommandName());
        }
        return 0;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return Config.getCommandRequiredPermissionLevel();
    }

    @Override
    public String getCommandName() {
        return ChronosCommandLiterals.ROOT;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return ChronosCommandActions.USAGE_LINE;
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.emptyList();
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(
                    args,
                    ChronosCommandLiterals.BACKUP,
                    ChronosCommandLiterals.CANCEL,
                    ChronosCommandLiterals.SPEEDTEST);
        }
        return Collections.emptyList();
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        try {
            LegacyCommandSupport.execute(args, message -> sender.addChatMessage(new ChatComponentText(message)));
        } catch (LegacyCommandSupport.UnknownSubcommandException e) {
            throw new CommandException(e.getMessage());
        }
    }
}

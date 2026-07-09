package com.magicjinn.chronos.shell.paper;

import com.magicjinn.chronos.shell.ChronosCommandLiterals;
import com.magicjinn.chronos.shell.LegacyCommandSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperCommands implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;

    PaperCommands(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        PluginCommand command = plugin.getCommand(ChronosCommandLiterals.ROOT);
        if (command == null) {
            plugin.getLogger()
                    .severe(
                            "Missing plugin.yml command entry \""
                                    + ChronosCommandLiterals.ROOT
                                    + "\".");
            return;
        }
        command.setExecutor(plugin);
        command.setTabCompleter(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!PaperCommandGate.mayExecute(sender)) {
            return true;
        }
        try {
            LegacyCommandSupport.execute(args, sender::sendMessage);
        } catch (LegacyCommandSupport.UnknownSubcommandException e) {
            sender.sendMessage(e.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (!PaperCommandGate.mayExecute(sender)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> choices = new ArrayList<>();
            for (String candidate : new String[] {
                    ChronosCommandLiterals.BACKUP,
                    ChronosCommandLiterals.CANCEL,
                    ChronosCommandLiterals.SPEEDTEST
            }) {
                if (candidate.startsWith(prefix)) {
                    choices.add(candidate);
                }
            }
            return choices;
        }
        return Collections.emptyList();
    }
}

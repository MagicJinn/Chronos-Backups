package com.magicjinn.chronos.shell.paper;

import com.magicjinn.chronos.core.ChatCommandStyle;
import com.magicjinn.chronos.core.Core;
import com.magicjinn.chronos.shell.ChronosConstants;
import com.magicjinn.chronos.shell.HookBridge;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChronosPaperPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final PaperBackupWorldController WORLD_CONTROLLER = new PaperBackupWorldController();
    private final PaperShellMessenger messenger = new PaperShellMessenger();
    private final PaperCommands commands = new PaperCommands(this);

    @Override
    public void onEnable() {
        PaperRuntime.bind(this);
        Core.OnLoaderStarted(Core.LoaderEnvironment.DEDICATED_SERVER);
        commands.register();
        PaperSchedulers.runGlobal(this, this::onServerReady);
        PaperSchedulers.runGlobalTimer(this, HookBridge::serverTick, 1L, 1L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return commands.onCommand(sender, command, label, args);
    }

    @Override
    public java.util.List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        return commands.onTabComplete(sender, command, alias, args);
    }

    private void onServerReady() {
        commands.register();
        Server server = getServer();
        HookBridge.worldStarted(
                new PaperServerEnvironment(server),
                server,
                messenger,
                WORLD_CONTROLLER,
                chatStyleFor(server));
    }

    @Override
    public void onDisable() {
        HookBridge.worldStopped();
        PaperRuntime.clear();
    }

    static ChatCommandStyle chatStyleFor(Server server) {
        String version = server.getBukkitVersion();
        if (version == null || version.isEmpty()) {
            return ChatCommandStyle.MODERN_TELLRAW;
        }
        String numeric = version.split("-", 2)[0];
        String[] parts = numeric.split("\\.");
        try {
            if (parts.length >= 2 && "1".equals(parts[0])) {
                int minor = Integer.parseInt(parts[1]);
                if (minor < 13) {
                    return ChatCommandStyle.LEGACY_SAY;
                }
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return ChatCommandStyle.MODERN_TELLRAW;
    }

    public static String pluginId() {
        return ChronosConstants.MODID;
    }
}

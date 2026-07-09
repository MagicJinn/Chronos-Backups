package com.magicjinn.chronos.shell.paper;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Holds the active Paper plugin instance. */
final class PaperRuntime {
    private static volatile JavaPlugin plugin;

    private PaperRuntime() {
    }

    static void bind(JavaPlugin instance) {
        plugin = instance;
    }

    static void clear() {
        plugin = null;
    }

    static Plugin plugin(Server server) {
        JavaPlugin bound = plugin;
        if (bound != null) {
            return bound;
        }
        for (Plugin candidate : server.getPluginManager().getPlugins()) {
            if (candidate instanceof ChronosPaperPlugin) {
                return candidate;
            }
        }
        throw new IllegalStateException("Chronos Paper plugin is not loaded.");
    }
}

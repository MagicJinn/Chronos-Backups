package com.magicjinn.chronos.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import com.magicjinn.chronos.core.config.Config;
import com.magicjinn.chronos.shell.ChronosConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Core {
    public static Path RunningDirectory;
    private static final Logger LOG = LogManager.getLogger(ChronosConstants.LOG_NAME);

    /**
     * Where the mod first loads - before title screen or worlds, excludes
     * integrated SP server inside a client.
     */
    public enum LoaderEnvironment {
        CLIENT,
        DEDICATED_SERVER
    }

    /**
     * Called when Chronos first starts up, before any worlds are loaded.
     */
    public static void OnLoaderStarted(LoaderEnvironment environment) {
        RunningDirectory = Paths.get(System.getProperty("user.dir"));
        Backupper.InitializeBackupper();
        Config.InitializeConfig();
    }

    public static void OnWorldStarted(BackupRuntimeContext context) {
        Scheduler.InitializeScheduler(context);
    }

    public static void OnWorldStopped() {
        Backupper.ShutdownBackupper();
    }

    /**
     * Called on every server tick.
     */
    public static void OnServerTick() {
        // This will prevent a backup etc from crashing the server
        try {
            Scheduler.tickScheduler();
        } catch (Exception e) {
            LOG.error("Error ticking scheduler: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

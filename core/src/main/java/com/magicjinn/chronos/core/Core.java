package com.magicjinn.chronos.core;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.magicjinn.chronos.core.config.Config;

public class Core {
    public static Path RunningDirectory;

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
}

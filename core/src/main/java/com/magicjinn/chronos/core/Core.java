package com.magicjinn.chronos.core;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.magicjinn.chronos.core.config.Config;
import com.magicjinn.chronos.shell.ChronosConstants;
import com.magicjinn.cloudintegration.CloudIntegration;
import com.magicjinn.cloudintegration.CloudSync;
import com.magicjinn.cloudintegration.Dropbox;
import com.magicjinn.cloudintegration.GoogleDrive;
import com.magicjinn.cloudintegration.OneDrive;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Core {
    public static Path RunningDirectory;
    private static final Logger LOG = LogManager.getLogger(ChronosConstants.LOG_NAME);
    private static final List<CloudIntegration> cloudIntegrations = new ArrayList<>(Arrays.asList(
            GoogleDrive.INSTANCE,
            OneDrive.INSTANCE,
            Dropbox.INSTANCE));

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
        Config.InitializeConfig();
        Backupper.InitializeBackupper();

        CloudSync.registerIntegrations(cloudIntegrations);
        CloudSync.resetForNewSession();

        // Initialize cloud integrations
        for (CloudIntegration cloudIntegration : cloudIntegrations) {
            if (cloudIntegration.isEnabled()) {
                cloudIntegration.initialize();
            }
        }

        // On loader start, request a sync resume any previous sync that was interrupted
        CloudSync.requestSync();
    }

    public static void OnWorldStarted(BackupRuntimeContext context) {
        CloudSync.resetForNewSession();
        Scheduler.InitializeScheduler(context);
        CloudSync.requestSync();
    }

    public static void OnWorldStopped() {
        CloudSync.shutdown();
        for (CloudIntegration cloudIntegration : cloudIntegrations) {
            cloudIntegration.shutdown();
        }
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

package com.magicjinn.chronos.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Schedules backup work and delegates to {@link Backupper}.
 */
public final class Scheduler {
    private static ScheduledExecutorService backupScheduler = Executors.newScheduledThreadPool(1);
    private static volatile BackupRuntimeContext runtimeContext;

    private static final long BACKUP_INTERVAL_SECONDS = 60 * 60 * 24; // 24 hours // TODO: Make this configurable

    // This triggers a backup immediately
    // TODO: Remove this once the backup system is implemented
    private static long secondsSinceLastBackup = getCurrentTimeSeconds() - BACKUP_INTERVAL_SECONDS;

    public static void onWorldStarted(BackupRuntimeContext context) {
        System.out.println("Scheduler checking in");
        runtimeContext = context;
        if (backupScheduler.isShutdown() || backupScheduler.isTerminated()) {
            backupScheduler = Executors.newScheduledThreadPool(1);
        }

        Runnable backupTask = () -> {
            try {
                long currentTimeSeconds = getCurrentTimeSeconds();
                if (currentTimeSeconds - secondsSinceLastBackup >= BACKUP_INTERVAL_SECONDS) {
                    secondsSinceLastBackup = currentTimeSeconds;
                    Backupper.runBackup(runtimeContext);
                }
            } catch (Exception e) {
                System.err.println("Error scheduling backup: " + e.getMessage());
                e.printStackTrace();
            }
        };

        // Check whether we should run a backup every second. This is handy so the user
        // could change backup interval on the fly
        backupScheduler.scheduleAtFixedRate(backupTask, 0, 1, TimeUnit.SECONDS);
    }

    public static void onWorldStopped() {
        backupScheduler.shutdown();
        runtimeContext = null;
    }

    private Scheduler() {}

    private static long getCurrentTimeSeconds() {
        return System.currentTimeMillis() / 1000;
    }
}
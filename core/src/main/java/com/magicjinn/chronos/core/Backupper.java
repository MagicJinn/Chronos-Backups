package com.magicjinn.chronos.core;

import java.nio.file.Path;

/**
 * Version-agnostic backup implementation (placeholder).
 */
public final class Backupper {
    private static final String CHRONOS_FOLDER_NAME = "chronos";
    private static final int DEDICATED_SERVER_SLASH_BACKWARDS_AMOUNT = 1;
    private static final int INTEGRATED_SERVER_SLASH_BACKWARDS_AMOUNT = 2;

    public static void runBackup(BackupRuntimeContext context) {
        if (context == null) {
            System.err.println("Backupper skipped: runtime context is unavailable.");
            return;
        }

        Path worldPath = resolveWorldPath(context);
        System.out.println("Backupper checking in"); // TODO: Remove later, and change smoketest as well
        System.out.println(
                "Backup target resolved: dedicated="
                        + context.isDedicatedServer()
                        + ", worldName="
                        + context.getWorldName()
                        + ", worldPath="
                        + worldPath);
        // Turn something like
        // worldPath=C:\Users\Admin\Documents\GitHub\Chronos-Backups\variants\minecraft_26_1\fabric-line-26_1\run\saves\New
        // World into
        // C:\Users\Admin\Documents\GitHub\Chronos-Backups\variants\minecraft_26_1\fabric-line-26_1\run
        int slashBackwardsAmount = context.isDedicatedServer() ? DEDICATED_SERVER_SLASH_BACKWARDS_AMOUNT
                : INTEGRATED_SERVER_SLASH_BACKWARDS_AMOUNT;
        // Go back the amount of slashes needed to get to the root directory
        Path rootPath = worldPath;
        for (int i = 0; i < slashBackwardsAmount; i++) {
            rootPath = rootPath.getParent();
        }
        System.out.println("Root path: " + rootPath);
        // rootPath will be used to create a new directory, and save the backup to it
        // create Chronos folder in the root path
        Path chronosFolder = rootPath.resolve("chronos");
        if (chronosFolder.toFile().mkdirs()) {
            System.out.println("chronos folder created successfully");
        } else {
            System.err.println("Failed to create Chronos folder");
            return;
        }
    }

    private Backupper() {}

    private static Path resolveWorldPath(BackupRuntimeContext context) {
        if (context.isDedicatedServer()) {
            return context.getRunDirectory().resolve(context.getWorldName());
        }
        return context.getRunDirectory().resolve("saves").resolve(context.getWorldName());
    }
}

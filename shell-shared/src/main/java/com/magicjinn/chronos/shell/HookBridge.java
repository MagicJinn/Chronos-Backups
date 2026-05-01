package com.magicjinn.chronos.shell;

import com.magicjinn.chronos.core.BackupRuntimeContext;
import com.magicjinn.chronos.core.Scheduler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small glue between loader-specific hooks and the version-agnostic core.
 */
public final class HookBridge {
    private static final AtomicBoolean WORLD_START_FIRED = new AtomicBoolean(false);
    private static final String DEFAULT_WORLD_NAME = "world";

    public static void worldStarted(Object server) {
        if (!WORLD_START_FIRED.compareAndSet(false, true)) {
            return;
        }
        BackupRuntimeContext context = buildRuntimeContext(server);
        System.out.println("Hook checking in");
        Scheduler.onWorldStarted(context);
    }

    public static void worldStopped() {
        WORLD_START_FIRED.set(false);
        Scheduler.onWorldStopped();
    }

    private HookBridge() {}

    private static BackupRuntimeContext buildRuntimeContext(Object server) {
        boolean dedicatedServer = readDedicatedServer(server);
        String worldName = readWorldName(server);
        Path runDirectory = readRunDirectory(server);
        return new BackupRuntimeContext(dedicatedServer, worldName, runDirectory);
    }

    private static boolean readDedicatedServer(Object server) {
        try {
            Object result = server.getClass().getMethod("isDedicatedServer").invoke(server);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static String readWorldName(Object server) {
        try {
            Object worldData = server.getClass().getMethod("getWorldData").invoke(server);
            Object levelName = worldData.getClass().getMethod("getLevelName").invoke(worldData);
            if (levelName instanceof String && !((String) levelName).trim().isEmpty()) {
                return (String) levelName;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to legacy name probing.
        }

        try {
            Object folderName = server.getClass().getMethod("getFolderName").invoke(server);
            if (folderName instanceof String && !((String) folderName).trim().isEmpty()) {
                return (String) folderName;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to default name.
        }

        return DEFAULT_WORLD_NAME;
    }

    private static Path readRunDirectory(Object server) {
        try {
            Object directory = server.getClass().getMethod("getServerDirectory").invoke(server);
            if (directory instanceof java.io.File) {
                return ((java.io.File) directory).toPath().toAbsolutePath().normalize();
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to process working directory.
        }

        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}

package com.magicjinn.chronos.shell.mojmap.common;

import com.magicjinn.chronos.core.BackupWorldController;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Minecraft 1.14-1.15: {@link MinecraftServer#saveAllChunks} + per-dimension
 * {@link ServerLevel#noSave}
 * (no {@code saveEverything}). Server-thread scheduling uses reflection so one
 * jar covers early 1.14.x ({@code postToMainThread}) and later patches
 * ({@code executeBlocking} / {@code submit} on {@code BlockableEventLoop}).
 */
public final class MojmapBackupWorldController implements BackupWorldController {
    private static final String SERVER_THREAD_NAME = "Server thread";

    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        runOnServerThread(
                server,
                () -> {
                    server.getPlayerList().saveAll();
                    server.saveAllChunks(true, true, true);
                });
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (!(serverHandle instanceof MinecraftServer)) {
            return false;
        }
        MinecraftServer server = (MinecraftServer) serverHandle;
        boolean[] touched = new boolean[1];
        runOnServerThread(
                server,
                () -> {
                    for (ServerLevel level : server.getAllLevels()) {
                        if (level != null && level.noSave != disabled) {
                            level.noSave = disabled;
                            touched[0] = true;
                        }
                    }
                });
        return touched[0];
    }

    private static void runOnServerThread(MinecraftServer server, Runnable task) {
        if (SERVER_THREAD_NAME.equals(Thread.currentThread().getName())) {
            task.run();
            return;
        }
        Method schedule = firstMethod(
                server.getClass(),
                new String[] { "execute", "postToMainThread", "tell" },
                Runnable.class);
        if (schedule == null) {
            throw new IllegalStateException(
                    "Cannot schedule backup work on the Minecraft server thread");
        }
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        Runnable wrapped = () -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                latch.countDown();
            }
        };
        invokeChecked(schedule, server, wrapped);
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        rethrow(failure[0]);
    }

    private static Method firstMethod(Class<?> type, String[] names, Class<?>... params) {
        for (String name : names) {
            Method method = findMethod(type, name, params);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // try superclass
            }
        }
        return null;
    }

    private static Object invokeChecked(Method method, Object target, Object arg) {
        try {
            method.setAccessible(true);
            return method.invoke(target, arg);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new RuntimeException(failure);
    }
}

package com.magicjinn.chronos.shell.forge;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import net.minecraft.server.MinecraftServer;

/**
 * Reflection helpers for Forge 1.14.x unified jars compiled against 1.14.4
 * Mojmap but run on 1.14.2-1.14.3 where several method descriptors still use
 * the pre-flatten class names.
 * TODO. This sucks. Remove all reflection in the future.
 */
final class Forge114Reflect {
    private static final String SERVER_THREAD_NAME = "Server thread";
    private static final String DEFAULT_WORLD_NAME = "world";

    private Forge114Reflect() {
    }

    static Path getRunDirectory(MinecraftServer server) {
        try {
            // 1.14.4+ Mojmap
            return toAbsolutePath(server.getServerDirectory());
        } catch (NoSuchMethodError ignored) {
            // 1.14.2-1.14.3: fall back to reflective probes
        }
        Path resolved = toAbsolutePath(
                invokeNoArg(
                        server,
                        new String[] {
                                "getServerDirectory",
                                "getDataDirectory",
                                "getStorageFolder",
                                "func_71270_I"
                        }));
        if (resolved != null) {
            return resolved;
        }
        resolved = toAbsolutePath(
                readField(server, new String[] { "serverDirectory", "storageFolder", "field_71370_b" }));
        if (resolved != null) {
            return resolved;
        }
        if (server.isDedicatedServer()) {
            return Paths.get(".").toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Cannot resolve Minecraft server run directory");
    }

    private static Path toAbsolutePath(Object root) {
        if (root instanceof Path) {
            return ((Path) root).toAbsolutePath().normalize();
        }
        if (root instanceof File) {
            return ((File) root).toPath().toAbsolutePath().normalize();
        }
        return null;
    }

    private static Object readField(Object target, String[] names) {
        for (String name : names) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (ReflectiveOperationException ignored) {
                    // try next field name / superclass
                }
            }
        }
        return null;
    }

    static String getWorldName(MinecraftServer server) {
        try {
            // 1.14.4+ Mojmap (dedicated + integrated).
            String id = server.getLevelIdName();
            if (id != null) {
                String name = id.trim();
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (NoSuchMethodError ignored) {
            // 1.14.2-1.14.3: fall back
        }
        if (server.isDedicatedServer()) {
            Object folder = invokeNoArg(server, new String[] { "getLevelName", "getFolderName", "getLevelIdName" });
            if (folder instanceof String) {
                String name = ((String) folder).trim();
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }
        Object id = invokeNoArg(server, new String[] { "getLevelIdName", "getLevelName" });
        if (id instanceof String) {
            String name = ((String) id).trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return DEFAULT_WORLD_NAME;
    }

    static Path getWorldSaveRoot(MinecraftServer server) {
        if (server.isDedicatedServer()) {
            return getRunDirectory(server).resolve(getWorldName(server)).normalize();
        }
        Object overworld = firstLoadedLevel(server);
        if (overworld != null) {
            Path fromLevel = worldSavePath(overworld);
            if (fromLevel != null) {
                return fromLevel;
            }
        }
        return getRunDirectory(server).resolve("saves").resolve(getWorldName(server)).normalize();
    }

    static void saveAllWorldData(MinecraftServer server) {
        runOnServerThread(
                server,
                () -> {
                    try {
                        server.getPlayerList().saveAll();
                    } catch (NoSuchMethodError ignored) {
                        Object playerList = invokeNoArg(server, "getPlayerList");
                        if (playerList != null) {
                            invokeVoid(playerList, new String[] { "saveAll", "saveAllPlayerData", "func_72389_g" });
                        }
                    }
                    flushWorldsToDisk(server);
                });
    }

    /**
     * Best-effort flush. 1.14.2-1.14.3 dedicated servers may not expose Mojmap save
     * names at runtime.
     */
    private static void flushWorldsToDisk(MinecraftServer server) {
        try {
            server.saveAllChunks(true, true, true);
            return;
        } catch (NoSuchMethodError ignored) {
            // fall back to reflective probes for early 1.14.x
        }
        if (invokeVoid(server, new String[] { "saveAllChunks", "saveAllWorlds" }, true, true, true)) {
            return;
        }
        if (invokeVoid(server, new String[] { "saveAllChunks", "saveAllWorlds" }, true)) {
            return;
        }
        if (invokeVoid(
                server,
                new String[] { "saveAllWorlds", "save", "saveAll", "func_71267_a", "func_71270_s" },
                true)) {
            return;
        }
        invokeFirstBooleanSaver(server);
        for (Object level : loadedLevels(server)) {
            if (level == null) {
                continue;
            }
            if (invokeVoid(level, new String[] { "save", "saveLevel", "saveAll" }, true)) {
                return;
            }
        }
    }

    /**
     * Last resort for 1.14.2-1.14.3 where Mojmap save method names are not remapped
     * at runtime.
     */
    private static void invokeFirstBooleanSaver(MinecraftServer server) {
        for (Class<?> type = server.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != boolean.class) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("save") && !name.startsWith("func_71267")) {
                    continue;
                }
                invokeChecked(method, server, true);
                return;
            }
        }
    }

    static boolean setWorldSavingDisabled(MinecraftServer server, boolean disabled) {
        boolean[] touched = new boolean[1];
        runOnServerThread(
                server,
                () -> {
                    try {
                        for (Object level : server.getAllLevels()) {
                            if (level == null) {
                                continue;
                            }
                            if (setBooleanField(level, new String[] { "noSave", "disableLevelSaving" }, disabled)) {
                                touched[0] = true;
                            }
                        }
                        return;
                    } catch (NoSuchMethodError ignored) {
                        // early 1.14.x: fall back
                    }
                    for (Object level : loadedLevels(server)) {
                        if (level == null) {
                            continue;
                        }
                        if (setBooleanField(level, new String[] { "noSave", "disableLevelSaving" }, disabled)) {
                            touched[0] = true;
                        }
                    }
                });
        return touched[0];
    }

    static void runOnServerThread(MinecraftServer server, Runnable task) {
        if (SERVER_THREAD_NAME.equals(Thread.currentThread().getName())) {
            task.run();
            return;
        }
        // Prefer the stable scheduling entrypoint. If it links, we can avoid all reflection.
        try {
            runBlockingOn(server::execute, task);
            return;
        } catch (NoSuchMethodError ignored) {
            // early 1.14.x: fall back
        }

        Method schedule = firstMethod(server.getClass(), new String[] { "execute", "postToMainThread", "tell" }, Runnable.class);
        if (schedule == null) {
            throw new IllegalStateException("Cannot schedule backup work on the Minecraft server thread");
        }
        runBlockingOn(r -> invokeChecked(schedule, server, r), task);
    }

    @FunctionalInterface
    private interface Scheduler {
        void schedule(Runnable runnable);
    }

    private static void runBlockingOn(Scheduler scheduler, Runnable task) {
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
        scheduler.schedule(wrapped);
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        rethrow(failure[0]);
    }

    private static Object firstLoadedLevel(MinecraftServer server) {
        for (Object level : loadedLevels(server)) {
            if (level != null) {
                return level;
            }
        }
        return null;
    }

    private static Iterable<Object> loadedLevels(MinecraftServer server) {
        Object forgeMap = invokeNoArg(server, "forgeGetWorldMap");
        if (forgeMap instanceof java.util.Map<?, ?>) {
            return castIterable(((java.util.Map<?, ?>) forgeMap).values());
        }
        if (forgeMap instanceof Iterable<?>) {
            return castIterable((Iterable<?>) forgeMap);
        }
        if (forgeMap != null && forgeMap.getClass().isArray()) {
            return arrayIterable(forgeMap);
        }
        Object levels = invokeNoArg(server, new String[] { "getAllLevels", "getWorlds" });
        if (levels instanceof Iterable<?>) {
            return castIterable((Iterable<?>) levels);
        }
        if (levels != null && levels.getClass().isArray()) {
            return arrayIterable(levels);
        }
        return java.util.Collections.emptyList();
    }

    private static Path worldSavePath(Object level) {
        Object storage = invokeNoArg(level, new String[] { "getLevelStorage", "getSaveHandler" });
        if (storage == null) {
            return null;
        }
        Object folder = invokeNoArg(storage, new String[] { "getFolder", "getWorldDirectory" });
        if (folder instanceof Path) {
            return ((Path) folder).toAbsolutePath().normalize();
        }
        if (folder instanceof File) {
            return ((File) folder).toPath().toAbsolutePath().normalize();
        }
        return null;
    }

    private static boolean setBooleanField(Object target, String[] names, boolean value) {
        for (String name : names) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                        field.setAccessible(true);
                        field.setBoolean(target, value);
                        return true;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // try next field name / superclass
                }
            }
        }
        return false;
    }

    private static Object invokeNoArg(Object target, String... names) {
        for (String name : names) {
            Method method = findMethod(target.getClass(), name);
            if (method != null) {
                return invokeChecked(method, target);
            }
            method = findDeclaredMethod(target.getClass(), name);
            if (method != null) {
                return invokeChecked(method, target);
            }
        }
        return null;
    }

    private static Method findDeclaredMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                // try superclass
            }
        }
        return null;
    }

    private static boolean invokeVoid(Object target, String[] names, Object... args) {
        for (String name : names) {
            Method method = findMethod(target.getClass(), name, args);
            if (method != null) {
                invokeChecked(method, target, args);
                return true;
            }
        }
        return false;
    }

    private static Method findMethod(Class<?> type, String name, Object... args) {
        Method[] methods = type.getMethods();
        for (Method method : methods) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                continue;
            }
            if (matchesArgs(method.getParameterTypes(), args)) {
                return method;
            }
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                    continue;
                }
                if (matchesArgs(method.getParameterTypes(), args)) {
                    return method;
                }
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

    private static Method firstMethod(Class<?> type, String[] names, Class<?>... params) {
        for (String name : names) {
            Method method = findMethod(type, name, params);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private static boolean matchesArgs(Class<?>[] paramTypes, Object[] args) {
        for (int i = 0; i < paramTypes.length; i++) {
            if (args[i] == null) {
                continue;
            }
            if (!wrap(paramTypes[i]).isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return type;
    }

    private static Object invokeChecked(Method method, Object target, Object... args) {
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static RuntimeException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new RuntimeException(cause != null ? cause : e);
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

    private static Iterable<Object> castIterable(Iterable<?> source) {
        java.util.List<Object> levels = new java.util.ArrayList<>();
        for (Object level : source) {
            levels.add(level);
        }
        return levels;
    }

    private static Iterable<Object> arrayIterable(Object array) {
        int length = Array.getLength(array);
        java.util.List<Object> levels = new java.util.ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            levels.add(Array.get(array, i));
        }
        return levels;
    }
}

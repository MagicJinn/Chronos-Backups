package com.magicjinn.chronos.shell.fabric;

import com.magicjinn.chronos.core.BackupWorldController;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Fabric-first world controller.
 */
public final class FabricBackupWorldController implements BackupWorldController {
    @Override
    public void saveAllWorldData(Object serverHandle) {
        if (serverHandle == null) {
            return;
        }
        try {
            Method getPlayerManagerMethod = serverHandle.getClass().getMethod("getPlayerManager");
            Object playerManager = getPlayerManagerMethod.invoke(serverHandle);
            if (playerManager != null) {
                Method saveAllPlayerDataMethod = playerManager.getClass().getMethod("saveAllPlayerData");
                saveAllPlayerDataMethod.invoke(playerManager);
            }
        } catch (ReflectiveOperationException ignored) {
            // Best effort.
        }
    }

    @Override
    public boolean setWorldSavingDisabled(Object serverHandle, boolean disabled) {
        if (serverHandle == null) {
            return false;
        }

        List<Object> worlds = resolveServerWorlds(serverHandle);
        if (worlds.isEmpty()) {
            return false;
        }

        boolean updatedAny = false;
        for (Object world : worlds) {
            if (world == null) {
                continue;
            }
            boolean updated =
                    setField(world, "savingDisabled", disabled)
                            || setField(world, "noSave", disabled)
                            || setField(world, "disableLevelSaving", disabled);
            if (updated) {
                updatedAny = true;
            }
        }
        if (updatedAny) {
            return true;
        }
        return false;
    }

    private static List<Object> resolveServerWorlds(Object serverHandle) {
        List<Object> worlds = new ArrayList<Object>();

        Object value = tryCallMethod(serverHandle, "getWorlds");
        addWorldCollection(worlds, value);
        if (!worlds.isEmpty()) {
            return worlds;
        }

        value = tryCallMethod(serverHandle, "getAllLevels");
        addWorldCollection(worlds, value);
        if (!worlds.isEmpty()) {
            return worlds;
        }

        value = tryReadField(serverHandle, "worlds");
        addWorldCollection(worlds, value);
        return worlds;
    }

    private static Object tryCallMethod(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object tryReadField(Object target, String fieldName) {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void addWorldCollection(List<Object> worlds, Object worldCollectionValue) {
        if (worldCollectionValue == null) {
            return;
        }
        if (worldCollectionValue instanceof Iterable) {
            for (Object world : (Iterable<?>) worldCollectionValue) {
                worlds.add(world);
            }
            return;
        }
        if (worldCollectionValue.getClass().isArray()) {
            int length = Array.getLength(worldCollectionValue);
            for (int i = 0; i < length; i++) {
                worlds.add(Array.get(worldCollectionValue, i));
            }
        }
    }

    private static boolean setField(Object target, String fieldName, boolean value) {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                    field.setBoolean(target, value);
                    return true;
                }
                return false;
            } catch (ReflectiveOperationException ignored) {
                current = current.getSuperclass();
            }
        }
        return false;
    }
}

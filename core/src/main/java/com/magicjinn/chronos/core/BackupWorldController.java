package com.magicjinn.chronos.core;

/**
 * Loader-specific world control operations needed by backup execution.
 */
public interface BackupWorldController {
    void saveAllWorldData(Object serverHandle);

    boolean setWorldSavingDisabled(Object serverHandle, boolean disabled);

    /**
     * Flushes world data before a backup. Returns {@code false} while work is still
     * in progress and must be retried on a later server tick.
     */
    default boolean prepareWorldFlush(Object serverHandle) {
        saveAllWorldData(serverHandle);
        return true;
    }

    /**
     * Enables or disables automatic world saves before/after a backup. Returns
     * {@code false} while work is still in progress and must be retried on a later
     * server tick.
     */
    default boolean preparePauseSaves(Object serverHandle, boolean disabled) {
        setWorldSavingDisabled(serverHandle, disabled);
        return true;
    }
}

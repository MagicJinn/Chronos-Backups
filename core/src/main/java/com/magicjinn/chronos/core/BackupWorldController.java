package com.magicjinn.chronos.core;

/**
 * Loader-specific world control operations needed by backup execution.
 */
public interface BackupWorldController {
    void saveAllWorldData(Object serverHandle);

    boolean setWorldSavingDisabled(Object serverHandle, boolean disabled);
}

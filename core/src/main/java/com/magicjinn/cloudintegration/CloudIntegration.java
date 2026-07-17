package com.magicjinn.cloudintegration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Remote backup destination (Google Drive, OneDrive, Dropbox, ...).
 */
public interface CloudIntegration {
    /** Stable id for logs, e.g. {@code "gdrive"}. */
    String getId();

    /** Whether this provider is turned on in config. */
    boolean isEnabled();

    /** Whether the provider is linked and can upload. */
    boolean isReady();

    /** Resume tokens or start auth when enabled. */
    void initialize();

    /** Upload one finished local backup artifact. */
    void uploadBackup(Path localBackup, String worldName) throws IOException;

    /**
     * Keep at most <code>maxStored</code> remote backups for this world.
     */
    void trimOldBackups(String worldName, int maxStored) throws IOException;

    /** Cancel in-flight work (e.g. on world stop). */
    void shutdown();
}

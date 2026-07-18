package com.magicjinn.cloudintegration;

import java.io.IOException;

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

    /**
     * Upload local Chronos backups missing remotely, trim remote to
     * {@code maxStoredBackups}, and optionally delete locals after upload.
     */
    void synchronize() throws IOException;

    /** Cancel in-flight work (e.g. on world stop). */
    void shutdown();
}

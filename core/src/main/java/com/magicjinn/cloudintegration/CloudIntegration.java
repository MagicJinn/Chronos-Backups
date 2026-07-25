package com.magicjinn.cloudintegration;

import java.io.IOException;

/**
 * Remote backup destination (Google Drive, OneDrive, Dropbox, ...).
 */
public interface CloudIntegration {
    /** Stable machine id, e.g. {@code "gdrive"}. */
    String getId();

    /** Human-readable name for logs, e.g. {@code "Google Drive"}. */
    String getDisplayName();

    /** Whether this provider is turned on in config. */
    boolean isEnabled();

    /** Whether the provider is linked and can upload. */
    boolean isReady();

    /** Resume tokens or start auth when enabled. */
    void initialize();

    /**
     * Called when a world session is available (alias / folder setup that
     * needs a world name). Default: no-op.
     */
    default void onWorldAvailable() {}

    /**
     * Upload local Chronos backups missing remotely, trim remote to
     * {@code maxStoredBackups}, and optionally delete locals after upload.
     */
    void synchronize() throws IOException;

    /** Cancel in-flight work (e.g. on world stop). */
    void shutdown();
}

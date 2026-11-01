package com.magicjinn.chronos.core.config;

/**
 * How each backup snapshot is stored on disk.
 */
public enum CompressionMethod {
    /** Uncompressed backup stored in a folder. */
    NONE,
    /** Compressed backup stored in a zip file. */
    ZIP,
    /**
     * Incremental Git repository under the world backup folder.
     * Unchanged files are content-addressed across snapshots (space) and skipped
     * before copy/prune when the pre-prune source hash matches (speed).
     */
    GIT;

    public static CompressionMethod fromTomlValue(Object raw, CompressionMethod fallback) {
        if (raw == null)
            return fallback;

        String s = raw.toString().trim();

        if (s.isEmpty())
            return fallback;

        if ("none".equalsIgnoreCase(s))
            return NONE;

        if ("zip".equalsIgnoreCase(s))
            return ZIP;

        if ("git".equalsIgnoreCase(s))
            return GIT;

        return fallback;
    }
}

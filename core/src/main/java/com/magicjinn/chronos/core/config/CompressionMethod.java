package com.magicjinn.chronos.core.config;

/**
 * How each backup snapshot is stored on disk.
 */
public enum CompressionMethod {
    /** Copy the world tree into a timestamped folder (no zip, no cache snapshot). */
    NONE,
    /** Copy to cache, then produce a {@code .zip} in the world's backup directory. */
    ZIP;

    public static CompressionMethod fromTomlValue(Object raw, CompressionMethod fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            return fallback;
        }
        if ("none".equalsIgnoreCase(s)) {
            return NONE;
        }
        if ("zip".equalsIgnoreCase(s)) {
            return ZIP;
        }
        return fallback;
    }
}

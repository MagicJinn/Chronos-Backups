package com.magicjinn.chronos.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.magicjinn.chronos.shell.ChronosConstants;

/**
 * Creates and recognizes Chronos backup artifact names
 *
 * <p>
 * Format: {@code {sanitizedWorld}-{yyyy-MM-dd_HH-mm-ss.SSS}} or the same with
 * {@code .zip}. Generation and detection share one timestamp layout so local
 * trim, cloud sync, and new backups stay aligned
 */
public final class ChronosBackupArtifacts {
    /**
     * Timestamp layout embedded in every backup id. Detection regex below must
     * match this formatter's output exactly.
     */
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss.SSS";
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN);

    /** Matches {@link #TIMESTAMP_FORMAT} output, optional {@code .zip}. */
    private static final Pattern TIMESTAMP_SUFFIX = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.\\d{3})(\\.zip)?$");

    /** Default backups folder name */
    public static final String DEFAULT_BACKUP_FOLDER_NAME = "backups";

    private ChronosBackupArtifacts() {}

    /**
     * Single folder name under the Minecraft run directory. Rejects blank,
     * {@code .}/{@code ..}, path separators, drive letters, and control/reserved
     * characters. Invalid values fall back to {@link #DEFAULT_BACKUP_FOLDER_NAME}.
     */
    public static String sanitizeBackupFolderName(String name) {
        return sanitizeFsSegment(name, DEFAULT_BACKUP_FOLDER_NAME, false);
    }

    /**
     * Resolves {@code folderName} under {@code runDirectory}, always staying
     * inside that root after normalize.
     */
    public static Path resolveBackupFolder(Path runDirectory, String folderName) {
        if (runDirectory == null)
            throw new IllegalArgumentException("runDirectory is null");

        String safe = sanitizeBackupFolderName(folderName);
        Path base = runDirectory.toAbsolutePath().normalize();
        Path resolved = base.resolve(safe).normalize();
        if (!resolved.startsWith(base) || resolved.equals(base))
            return base.resolve(DEFAULT_BACKUP_FOLDER_NAME).normalize();

        return resolved;
    }

    /** Returns a sanitized world directory name */
    public static String sanitizeWorldDirName(String worldName) {
        return sanitizeFsSegment(worldName, ChronosConstants.DEFAULT_WORLD_NAME, true);
    }

    /**
     * @param replaceUnsafe {@code true} replaces unsafe chars with {@code _};
     *                      {@code false} rejects the whole name
     */
    private static String sanitizeFsSegment(String name, String fallback, boolean replaceUnsafe) {
        if (name == null)
            return fallback;

        String trimmed = name.trim();
        if (trimmed.isEmpty())
            return fallback;

        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (isUnsafeFsChar(ch)) {
                if (!replaceUnsafe)
                    return fallback;

                sb.append('_');
            } else {
                sb.append(ch);
            }
        }

        String s = stripTrailingDotsAndSpaces(sb.toString().trim());
        if (s.isEmpty() || ".".equals(s) || "..".equals(s))
            return fallback;

        return s;
    }

    // Whether the character is unsafe for a filesystem path
    private static boolean isUnsafeFsChar(char ch) {
        // Reject ASCII control characters (below 32) and DEL (127)
        if (ch < 32 || ch == 127)
            return true;

        // Reject any other unsafe characters
        switch (ch) {
            case '\\':
            case '/':
            case ':':
            case '*':
            case '?':
            case '"':
            case '<':
            case '>':
            case '|':
                return true;
            default:
                return false;
        }
    }

    private static String stripTrailingDotsAndSpaces(String s) {
        while (s.endsWith(".") || s.endsWith(" "))
            s = s.substring(0, s.length() - 1).trim();

        return s;
    }

    /** New backup id for {@code worldName} using the current time. */
    public static String newBackupId(String worldName) {
        return newBackupId(worldName, LocalDateTime.now());
    }

    /**
     * {@code {sanitizedWorld}-{timestamp}} with no {@code .zip} suffix.
     * Use {@link #zipFileName(String)} when storing a zip.
     */
    public static String newBackupId(String worldName, LocalDateTime when) {
        return sanitizeWorldDirName(worldName) + "-" + TIMESTAMP_FORMAT.format(when);
    }

    /** Zip artifact filename for a backup id from {@link #newBackupId}. */
    public static String zipFileName(String backupId) {
        return backupId + ".zip";
    }

    /**
     * Whether {@code name} is a Chronos zip or folder backup for {@code worldName}.
     * Requires {@code {sanitizedWorld}-{timestamp}(.zip)?}, matching {@link #newBackupId}.
     */
    public static boolean isChronosBackupName(String name, String worldName) {
        if (name == null || name.isEmpty() || name.startsWith(".") || worldName == null) {
            return false;
        }
        String prefix = sanitizeWorldDirName(worldName) + "-";
        if (!name.startsWith(prefix)) {
            return false;
        }
        return TIMESTAMP_SUFFIX.matcher(name).find();
    }

    /**
     * Canonical stored name for an artifact. Local zips keep their filename.
     * Folder backups are stored as {@code {folderName}.zip} (e.g. on Drive).
     */
    public static String remoteFileName(Path localArtifact) {
        String name = localArtifact.getFileName().toString();
        if (Files.isDirectory(localArtifact) && !name.endsWith(".zip")) {
            return zipFileName(name);
        }
        return name;
    }

    /**
     * Sort key from the embedded timestamp (higher = newer). Returns
     * {@link Long#MIN_VALUE} when the name is not a Chronos backup.
     */
    public static long timestampSortKey(String name) {
        if (name == null)
            return Long.MIN_VALUE;
        
        Matcher m = TIMESTAMP_SUFFIX.matcher(name);
        if (!m.find())
            return Long.MIN_VALUE;

        String ts = m.group(1);
        StringBuilder digits = new StringBuilder(ts.length());
        for (int i = 0; i < ts.length(); i++) {
            char c = ts.charAt(i);
            if (c >= '0' && c <= '9')
                digits.append(c);
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }
}

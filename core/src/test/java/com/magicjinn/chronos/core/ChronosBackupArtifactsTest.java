package com.magicjinn.chronos.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.magicjinn.chronos.shell.ChronosConstants;

class ChronosBackupArtifactsTest {
    @Test
    void newBackupIdIsRecognizedAsChronosName() {
        String id = ChronosBackupArtifacts.newBackupId(
                "New World", LocalDateTime.of(2026, 7, 18, 13, 23, 3, 525_000_000));
        assertEquals("New World-2026-07-18_13-23-03.525", id);
        assertTrue(ChronosBackupArtifacts.isChronosBackupName(id, "New World"));
        assertTrue(ChronosBackupArtifacts.isChronosBackupName(
                ChronosBackupArtifacts.zipFileName(id), "New World"));
    }

    @Test
    void sanitizeReplacesPathSeparators() {
        assertEquals("a_b_c", ChronosBackupArtifacts.sanitizeWorldDirName("a/b\\c"));
        assertEquals(ChronosConstants.DEFAULT_WORLD_NAME, ChronosBackupArtifacts.sanitizeWorldDirName(""));
        assertEquals(ChronosConstants.DEFAULT_WORLD_NAME, ChronosBackupArtifacts.sanitizeWorldDirName(".."));
        assertEquals(ChronosConstants.DEFAULT_WORLD_NAME, ChronosBackupArtifacts.sanitizeWorldDirName("."));
    }

    @Test
    void sanitizeBackupFolderNameRejectsTraversal() {
        assertEquals(
                ChronosBackupArtifacts.DEFAULT_BACKUP_FOLDER_NAME,
                ChronosBackupArtifacts.sanitizeBackupFolderName(null));
        assertEquals(
                ChronosBackupArtifacts.DEFAULT_BACKUP_FOLDER_NAME,
                ChronosBackupArtifacts.sanitizeBackupFolderName(""));
        assertEquals(
                ChronosBackupArtifacts.DEFAULT_BACKUP_FOLDER_NAME,
                ChronosBackupArtifacts.sanitizeBackupFolderName(".."));
        assertEquals(
                ChronosBackupArtifacts.DEFAULT_BACKUP_FOLDER_NAME,
                ChronosBackupArtifacts.sanitizeBackupFolderName("../other"));
        assertEquals(
                ChronosBackupArtifacts.DEFAULT_BACKUP_FOLDER_NAME,
                ChronosBackupArtifacts.sanitizeBackupFolderName("a/b"));
        assertEquals(
                ChronosBackupArtifacts.DEFAULT_BACKUP_FOLDER_NAME,
                ChronosBackupArtifacts.sanitizeBackupFolderName("a\\b"));
        assertEquals(
                ChronosBackupArtifacts.DEFAULT_BACKUP_FOLDER_NAME,
                ChronosBackupArtifacts.sanitizeBackupFolderName("C:backups"));
        assertEquals("chronos", ChronosBackupArtifacts.sanitizeBackupFolderName("chronos"));
        assertEquals("backups", ChronosBackupArtifacts.sanitizeBackupFolderName("  backups  "));
    }

    @Test
    void resolveBackupFolderStaysUnderRunDirectory() throws Exception {
        Path runDir = Files.createTempDirectory("chronos-run");
        try {
            Path ok = ChronosBackupArtifacts.resolveBackupFolder(runDir, "chronos");
            assertEquals(runDir.toAbsolutePath().normalize().resolve("chronos"), ok);
            assertTrue(ok.startsWith(runDir.toAbsolutePath().normalize()));

            Path escaped = ChronosBackupArtifacts.resolveBackupFolder(runDir, "../outside");
            assertEquals(
                    runDir.toAbsolutePath().normalize().resolve(
                            ChronosBackupArtifacts.DEFAULT_BACKUP_FOLDER_NAME),
                    escaped);
        } finally {
            Files.deleteIfExists(runDir);
        }
    }

    @Test
    void acceptsChronosZipAndFolderNamesForMatchingWorld() {
        assertTrue(ChronosBackupArtifacts.isChronosBackupName(
                "world-2026-07-18_12-00-00.123.zip", "world"));
        assertTrue(ChronosBackupArtifacts.isChronosBackupName(
                "world-2026-07-18_12-00-00.123", "world"));
        assertTrue(ChronosBackupArtifacts.isChronosBackupName(
                "My World-2026-07-18_12-00-00.123.zip", "My World"));
    }

    @Test
    void rejectsUnrelatedNames() {
        assertFalse(ChronosBackupArtifacts.isChronosBackupName("notes.zip", "world"));
        assertFalse(ChronosBackupArtifacts.isChronosBackupName("manual-backup", "world"));
        assertFalse(ChronosBackupArtifacts.isChronosBackupName(".cache", "world"));
        assertFalse(ChronosBackupArtifacts.isChronosBackupName(null, "world"));
        assertFalse(ChronosBackupArtifacts.isChronosBackupName(
                "world-2026-07-18_12-00-00.123.zip", null));
    }

    @Test
    void rejectsTimestampedNameForWrongWorld() {
        assertFalse(ChronosBackupArtifacts.isChronosBackupName(
                "notes-2026-07-18_12-00-00.123.zip", "world"));
        assertFalse(ChronosBackupArtifacts.isChronosBackupName(
                "other-2026-07-18_12-00-00.123.zip", "world"));
    }

    @Test
    void timestampSortKeyOrdersNewestHigher() {
        long older = ChronosBackupArtifacts.timestampSortKey("world-2026-07-18_12-00-00.000.zip");
        long newer = ChronosBackupArtifacts.timestampSortKey("world-2026-07-18_12-00-01.000.zip");
        assertTrue(newer > older);
    }

    @Test
    void sortKeyIgnoresNonChronos() {
        assertEquals(Long.MIN_VALUE, ChronosBackupArtifacts.timestampSortKey("random.zip"));
    }
}

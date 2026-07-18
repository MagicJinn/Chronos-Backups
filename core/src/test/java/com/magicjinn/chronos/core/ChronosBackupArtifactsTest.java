package com.magicjinn.chronos.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.magicjinn.chronos.shell.ChronosConstants;

class ChronosBackupArtifactsTest {
    @Test
    void newBackupIdIsRecognizedAsChronosName() {
        String id = ChronosBackupArtifacts.newBackupId(
                "New World", LocalDateTime.of(2026, 7, 18, 13, 23, 3, 525_000_000));
        assertEquals("New World-2026-07-18_13-23-03.525", id);
        assertTrue(ChronosBackupArtifacts.isChronosBackupName(id));
        assertTrue(ChronosBackupArtifacts.isChronosBackupName(ChronosBackupArtifacts.zipFileName(id)));
    }

    @Test
    void sanitizeReplacesPathSeparators() {
        assertEquals("a_b_c", ChronosBackupArtifacts.sanitizeWorldDirName("a/b\\c"));
        assertEquals(ChronosConstants.DEFAULT_WORLD_NAME, ChronosBackupArtifacts.sanitizeWorldDirName(""));
    }

    @Test
    void acceptsChronosZipAndFolderNames() {
        assertTrue(ChronosBackupArtifacts.isChronosBackupName("world-2026-07-18_12-00-00.123.zip"));
        assertTrue(ChronosBackupArtifacts.isChronosBackupName("world-2026-07-18_12-00-00.123"));
        assertTrue(ChronosBackupArtifacts.isChronosBackupName("My World-2026-07-18_12-00-00.123.zip"));
    }

    @Test
    void rejectsUnrelatedNames() {
        assertFalse(ChronosBackupArtifacts.isChronosBackupName("notes.zip"));
        assertFalse(ChronosBackupArtifacts.isChronosBackupName("manual-backup"));
        assertFalse(ChronosBackupArtifacts.isChronosBackupName(".cache"));
        assertFalse(ChronosBackupArtifacts.isChronosBackupName(null));
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

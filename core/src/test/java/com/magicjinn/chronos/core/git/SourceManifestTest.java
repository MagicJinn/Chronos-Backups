package com.magicjinn.chronos.core.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceManifestTest {
    @TempDir
    Path temp;

    @Test
    void prunePolicyHashStableForSameInputs() {
        String a = SourceManifest.computePrunePolicyHash(true, 120, Arrays.asList("voxy", "dynmap"));
        String b = SourceManifest.computePrunePolicyHash(true, 120, Arrays.asList("dynmap", "voxy"));
        assertEquals(a, b);
    }

    @Test
    void prunePolicyHashChangesWhenPruneSecondsChange() {
        String a = SourceManifest.computePrunePolicyHash(true, 120, Collections.<String>emptyList());
        String b = SourceManifest.computePrunePolicyHash(true, 60, Collections.<String>emptyList());
        assertFalse(a.equals(b));
    }

    @Test
    void roundTripSaveLoad() throws Exception {
        Path path = temp.resolve(".chronos").resolve("source-manifest.json");
        SourceManifest m = SourceManifest.empty("abc123");
        m.files.put("region/r.0.0.mca", new SourceManifest.FileEntry("deadbeefcafebabe", 4096));
        m.save(path);

        SourceManifest loaded = SourceManifest.load(path);
        assertEquals(SourceManifest.SCHEMA_VERSION, loaded.version);
        assertEquals("abc123", loaded.prunePolicyHash);
        assertTrue(loaded.policyMatches("abc123"));
        assertEquals("deadbeefcafebabe", loaded.files.get("region/r.0.0.mca").hash);
        assertEquals(4096L, loaded.files.get("region/r.0.0.mca").size);
    }
}

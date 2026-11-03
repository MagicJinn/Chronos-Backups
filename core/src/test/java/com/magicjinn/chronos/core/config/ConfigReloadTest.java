package com.magicjinn.chronos.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import com.magicjinn.chronos.core.Core;

class ConfigReloadTest {
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private Path previousRunningDirectory;
    private ModConfig previousModConfig;

    @AfterEach
    void tearDown() throws InterruptedException {
        Core.RunningDirectory = previousRunningDirectory;
        Config.restoreForTests(previousModConfig);
        Config.clearPendingChangeForTests();
        if (tempDir == null || !Files.exists(tempDir))
            return;

        for (int attempt = 0; attempt < 8; attempt++) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            } catch (IOException ignored) {
            }
            if (!Files.exists(tempDir))
                return;

            // Wait for the next attempt
            Thread.sleep(30L * (attempt + 1));
        }
    }

    private Path initConfig() throws IOException {
        previousRunningDirectory = Core.RunningDirectory;
        previousModConfig = Config.peekForTests();
        Core.RunningDirectory = tempDir;
        Config.clearPendingChangeForTests();
        Config.InitializeConfig();
        return tempDir.resolve("config").resolve("chronos.toml");
    }

    private static void writeConfig(Path configFile, String body) throws Exception {
        Thread.sleep(20L);
        Files.write(configFile, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path configFile) throws IOException {
        return new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
    }

    @Test
    void debounceWaitsForSettledFingerprintBeforeReload() throws Exception {
        Path configFile = initConfig();
        assertTrue(Config.live().scheduleBackups);

        String body = read(configFile)
                .replace("scheduleBackups = true", "scheduleBackups = false")
                .replace("backupIntervalSeconds = 1800", "backupIntervalSeconds = 120");
        writeConfig(configFile, body);

        Config.resetPollThrottleForTests();
        assertFalse(Config.pollForFileChanges().isPresent(), "first sighting should only arm debounce");
        assertTrue(Config.live().scheduleBackups, "must not reload on the first changed poll");

        Config.resetPollThrottleForTests();
        Optional<Config.ReloadOutcome> outcome = Config.pollForFileChanges();
        assertTrue(outcome.isPresent(), "settled fingerprint should reload on the next poll");
        assertFalse(Config.live().scheduleBackups);
        assertEquals(120, Config.live().backupIntervalSeconds);
    }

    @Test
    void debounceResetsWhenFileKeepsChanging() throws Exception {
        Path configFile = initConfig();

        String body1 = read(configFile).replace("backupIntervalSeconds = 1800", "backupIntervalSeconds = 100");
        writeConfig(configFile, body1);
        Config.resetPollThrottleForTests();
        assertFalse(Config.pollForFileChanges().isPresent());

        String body2 = read(configFile).replace("backupIntervalSeconds = 100", "backupIntervalSeconds = 200");
        writeConfig(configFile, body2);
        Config.resetPollThrottleForTests();
        assertFalse(Config.pollForFileChanges().isPresent(), "new fingerprint should re-arm debounce");
        assertEquals(1800, Config.live().backupIntervalSeconds);

        Config.resetPollThrottleForTests();
        assertTrue(Config.pollForFileChanges().isPresent());
        assertEquals(200, Config.live().backupIntervalSeconds);
    }

    @Test
    void parseFailureKeepsPreviousConfigAndDoesNotOverwrite() throws Exception {
        Path configFile = initConfig();
        String original = read(configFile);
        int originalInterval = Config.live().backupIntervalSeconds;
        ModConfig before = Config.peekForTests();

        writeConfig(configFile, "this is not valid toml {{{");
        Config.resetPollThrottleForTests();
        assertFalse(Config.pollForFileChanges().isPresent()); // arm debounce
        Config.resetPollThrottleForTests();
        assertFalse(Config.pollForFileChanges().isPresent()); // settled corrupt file

        assertSame(before, Config.peekForTests());
        assertEquals(originalInterval, Config.live().backupIntervalSeconds);
        String onDisk = read(configFile);
        assertTrue(onDisk.contains("this is not valid toml"));
        assertFalse(onDisk.contains("backupFolderName"));
        assertFalse(onDisk.equals(original));
    }

    @Test
    void atomicSwapReplacesLiveReferenceWhileSnapshotStaysStable() throws Exception {
        Path configFile = initConfig();
        ModConfig before = Config.peekForTests();
        ModConfig heldSnapshot = Config.snapshot();
        assertNotSame(before, heldSnapshot);
        assertEquals(before.backupIntervalSeconds, heldSnapshot.backupIntervalSeconds);

        String body = read(configFile).replace("backupIntervalSeconds = 1800", "backupIntervalSeconds = 90");
        writeConfig(configFile, body);

        Optional<Config.ReloadOutcome> forced = Config.forceReloadFromDisk();
        assertTrue(forced.isPresent());
        ModConfig after = Config.peekForTests();
        assertNotSame(before, after);
        assertSame(before, forced.get().previous);
        assertSame(after, forced.get().current);
        assertEquals(90, Config.live().backupIntervalSeconds);
        assertEquals(1800, heldSnapshot.backupIntervalSeconds, "in-flight snapshot must not mutate");
    }

    @Test
    void autoReloadFalseSkipsPollButForceReloadStillWorks() throws Exception {
        Path configFile = initConfig();
        String body = read(configFile).replace("autoReload = true", "autoReload = false");
        writeConfig(configFile, body);
        assertTrue(Config.forceReloadFromDisk().isPresent());
        assertFalse(Config.live().autoReload);

        String body2 = read(configFile).replace("backupIntervalSeconds = 1800", "backupIntervalSeconds = 45");
        writeConfig(configFile, body2);
        Config.clearPendingChangeForTests();
        Config.resetPollThrottleForTests();
        assertFalse(Config.pollForFileChanges().isPresent());
        assertEquals(1800, Config.live().backupIntervalSeconds);

        assertTrue(Config.forceReloadFromDisk().isPresent());
        assertEquals(45, Config.live().backupIntervalSeconds);
    }
}

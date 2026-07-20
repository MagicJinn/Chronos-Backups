package com.magicjinn.chronos.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

class SaveRootDiscoveryTest {
    /**
     * Windows often fails JUnit's strict temp delete while AV still holds .mca
     * handles.
     */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    @AfterEach
    void cleanupTempDir() throws InterruptedException {
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

            Thread.sleep(30L * (attempt + 1));
        }
    }

    @Test
    void classicSingleWorldRollsUpDimensions() throws IOException {
        Path runDir = tempDir.resolve("server");
        Path world = runDir.resolve("world");
        touchMca(world.resolve("region/r.0.0.mca"));
        touchMca(world.resolve("DIM-1/region/r.0.0.mca"));
        touchMca(world.resolve("DIM1/region/r.0.0.mca"));
        Files.write(world.resolve("level.dat"), new byte[] { 1 });

        SaveRootDiscovery.BackupScope scope = SaveRootDiscovery.resolve(context(runDir, world, true));

        assertEquals(world.toAbsolutePath().normalize(), scope.snapshotLayoutRoot());
        assertEquals(1, scope.saveContainers().size());
        assertEquals(world.toAbsolutePath().normalize(), scope.saveContainers().get(0));
    }

    @Test
    void bukkitSplitWorldsUseRunDirectoryLayoutRoot() throws IOException {
        Path runDir = tempDir.resolve("server");
        Path world = runDir.resolve("world");
        Path nether = runDir.resolve("world_nether");
        Path end = runDir.resolve("world_the_end");
        touchMca(world.resolve("region/r.0.0.mca"));
        touchMca(nether.resolve("region/r.0.0.mca"));
        touchMca(end.resolve("region/r.0.0.mca"));

        SaveRootDiscovery.BackupScope scope = SaveRootDiscovery.resolve(context(runDir, world, true));

        assertEquals(runDir.toAbsolutePath().normalize(), scope.snapshotLayoutRoot());
        assertEquals(3, scope.saveContainers().size());
        assertTrue(scope.discoveredByScan());
    }

    @Test
    void bukkitIncludesDimensionSiblingWithEmptyRegion() throws IOException {
        Path runDir = tempDir.resolve("server");
        Path world = runDir.resolve("world");
        Path nether = runDir.resolve("world_nether");
        touchMca(world.resolve("region/r.0.0.mca"));
        Files.createDirectories(nether.resolve("region"));

        SaveRootDiscovery.BackupScope scope = SaveRootDiscovery.resolve(context(runDir, world, true));

        assertEquals(2, scope.saveContainers().size());
        assertTrue(scope.saveContainers().contains(nether.toAbsolutePath().normalize()));
    }

    @Test
    void modernDimensionsLayoutRollsUpToWorld() throws IOException {
        Path runDir = tempDir.resolve("server");
        Path world = runDir.resolve("world");
        touchMca(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"));
        touchMca(world.resolve("dimensions/minecraft/the_nether/region/r.0.0.mca"));

        SaveRootDiscovery.BackupScope scope = SaveRootDiscovery.resolve(context(runDir, world, true));

        assertEquals(world.toAbsolutePath().normalize(), scope.snapshotLayoutRoot());
        assertEquals(1, scope.saveContainers().size());
    }

    @Test
    void levelDatOnlyContainerIsDiscovered() throws IOException {
        Path runDir = tempDir.resolve("server");
        Path world = runDir.resolve("custom_world");
        Files.createDirectories(world);
        Files.write(world.resolve("level.dat"), new byte[] { 1 });
        Files.createDirectories(world.resolve("data"));

        SaveRootDiscovery.BackupScope scope = SaveRootDiscovery.resolve(context(runDir, world, true, "custom_world"));

        assertEquals(1, scope.saveContainers().size());
        assertEquals(world.toAbsolutePath().normalize(), scope.saveContainers().get(0));
    }

    @Test
    void integratedClientDoesNotPickSiblingSaves() throws IOException {
        Path runDir = tempDir.resolve("client");
        Path saves = runDir.resolve("saves");
        Path active = saves.resolve("Creative");
        Path other = saves.resolve("Survival");
        touchMca(active.resolve("region/r.0.0.mca"));
        touchMca(other.resolve("region/r.0.0.mca"));

        SaveRootDiscovery.BackupScope scope = SaveRootDiscovery.resolve(context(runDir, active, false, "Creative"));

        assertEquals(active.toAbsolutePath().normalize(), scope.snapshotLayoutRoot());
        assertEquals(1, scope.saveContainers().size());
    }

    @Test
    void emptyRegionFolderWithoutMcaIsNotSaveContainer() throws IOException {
        Path runDir = tempDir.resolve("server");
        Path world = runDir.resolve("world");
        Files.createDirectories(world.resolve("region"));

        assertFalse(SaveRootDiscovery.looksLikeSaveContainer(world));
        assertThrows(IOException.class, () -> SaveRootDiscovery.resolve(context(runDir, world, true)));
    }

    @Test
    void rollupToContainerKeepsSiblingWorldSeparate() {
        Path runDir = Paths.get("/data").toAbsolutePath().normalize();
        Path primary = runDir.resolve("world");
        Path netherDim = runDir.resolve("world_nether");

        assertEquals(primary.normalize(), SaveRootDiscovery.rollupToContainer(primary, primary, runDir));
        assertEquals(
                netherDim.normalize(),
                SaveRootDiscovery.rollupToContainer(netherDim, primary, runDir));
    }

    private static BackupRuntimeContext context(Path runDir, Path primary, boolean dedicated) {
        return context(runDir, primary, dedicated, primary.getFileName().toString());
    }

    private static BackupRuntimeContext context(
            Path runDir, Path primary, boolean dedicated, String worldName) {
        ServerEnvironment environment = new ServerEnvironment() {
            @Override
            public boolean isDedicatedServer() {
                return dedicated;
            }

            @Override
            public String getWorldName() {
                return worldName;
            }

            @Override
            public Path getRunDirectory() {
                return runDir.toAbsolutePath().normalize();
            }

            @Override
            public Path getWorldSaveRoot() {
                return primary.toAbsolutePath().normalize();
            }
        };
        return new BackupRuntimeContext(environment, null, null, null, null, null);
    }

    private static void touchMca(Path mcaFile) throws IOException {
        Files.createDirectories(mcaFile.getParent());
        Files.write(mcaFile, new byte[] { 0 });
    }
}
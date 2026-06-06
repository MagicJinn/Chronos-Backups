package com.magicjinn.chronos.tooling.TestServers.docker;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.magicjinn.chronos.tooling.TestServers.TestServers;
import com.magicjinn.chronos.tooling.TestServers.rcon.RconClient;

public class DockerMinecraftServer {
    private static final long CONTAINER_EXIT_GRACE_MS = 180_000;

    private final String loaderKey;
    private final String version;
    private final int gamePort;
    private final int rconPort;
    private final Path modJarPath;
    /** Optional. Passed as {@code FABRIC_LOADER_VERSION} for Fabric servers. */
    private final String fabricLoaderVersion;
    /**
     * Optional. Passed as {@code MODRINTH_PROJECTS} (e.g. {@code fabric-api}
     */
    private final String modrinthProjects;
    /**
     * Optional. Passed as {@code FORGE_VERSION} for Forge servers. Overrides the
     * Forge version that would otherwise be auto-resolved from the promotions JSON
     * (e.g. 1.10.0 has an incorrectly structured version URL).
     */
    private final String forgeVersion;

    public DockerMinecraftServer(
            String loaderKey,
            String version,
            int gamePort,
            int rconPort,
            Path modJarPath) {
        this(loaderKey, version, gamePort, rconPort, modJarPath, null, null, null);
    }

    public DockerMinecraftServer(
            String loaderKey,
            String version,
            int gamePort,
            int rconPort,
            Path modJarPath,
            String fabricLoaderVersion,
            String modrinthProjects) {
        this(loaderKey, version, gamePort, rconPort, modJarPath, fabricLoaderVersion, modrinthProjects, null);
    }

    public DockerMinecraftServer(
            String loaderKey,
            String version,
            int gamePort,
            int rconPort,
            Path modJarPath,
            String fabricLoaderVersion,
            String modrinthProjects,
            String forgeVersion) {
        this.loaderKey = loaderKey;
        this.version = version;
        this.gamePort = gamePort;
        this.rconPort = rconPort;
        this.modJarPath = modJarPath;
        this.fabricLoaderVersion = fabricLoaderVersion;
        this.modrinthProjects = modrinthProjects;
        this.forgeVersion = forgeVersion;
    }

    public String getLoaderKey() {
        return loaderKey;
    }

    public String getVersion() {
        return version;
    }

    public int getGamePort() {
        return gamePort;
    }

    public int getRconPort() {
        return rconPort;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof DockerMinecraftServer other))
            return false;
        return loaderKey.equals(other.loaderKey) && version.equals(other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(loaderKey, version);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(loaderKey).append('-').append(version)
                .append(" (game port: ").append(gamePort).append(", RCON port: ").append(rconPort).append(')')
                .append(" (mod jar: ").append(modJarPath.getFileName()).append(')');
        if ("FABRIC".equalsIgnoreCase(loaderKey) && modrinthProjects != null && !modrinthProjects.isBlank()) {
            sb.append(" (MODRINTH_PROJECTS: ").append(modrinthProjects).append(')');
        } else if ("FORGE".equalsIgnoreCase(loaderKey) && forgeVersion != null && !forgeVersion.isBlank()) {
            sb.append(" (FORGE_VERSION: ").append(forgeVersion).append(')');
        }
        return sb.toString();
    }

    public Path dataDir() {
        return TestServers.ROOT.resolve("build/test-docker-servers").resolve(dockerInstanceId());
    }

    public String containerName() {
        return dockerContainerName();
    }

    /**
     * Starts a detached container matching the itzg/minecraft-server compose
     * defaults.
     */
    public void createDockerContainer() throws IOException {
        if (gamePort == rconPort) {
            throw new IllegalArgumentException("game and RCON ports must differ");
        }
        Path dataDir = dataDir();
        Files.createDirectories(dataDir);
        String containerName = containerName();
        removeDockerContainer();
        String image = DockerServerImage.imageFor(getVersion());
        System.out.println("Starting " + containerName + " with image " + image);

        String modJarMount = modJarPath + ":/data/mods/chronosbackups.jar";
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("-d");
        command.add("-it");
        command.add("-p");
        command.add(gamePort + ":25565");
        command.add("-p");
        command.add(rconPort + ":25575");
        command.add("-v");
        command.add(dataDir.toAbsolutePath().normalize() + ":/data");
        command.add("-v");
        command.add(modJarMount);
        command.add("-e");
        command.add("EULA=TRUE");
        command.add("-e");
        command.add("TYPE=" + getLoaderKey());
        command.add("-e");
        command.add("VERSION=" + dockerMinecraftVersion(getVersion(), getLoaderKey()));
        if ("FABRIC".equalsIgnoreCase(loaderKey)) {
            command.add("-e");
            command.add("MODRINTH_LOADER=fabric");
            if (fabricLoaderVersion != null && !fabricLoaderVersion.isBlank()) {
                command.add("-e");
                command.add("FABRIC_LOADER_VERSION=" + fabricLoaderVersion);
            }
            if (modrinthProjects != null && !modrinthProjects.isBlank()) {
                command.add("-e");
                command.add("MODRINTH_PROJECTS=" + modrinthProjects);
            }
        } else if ("FORGE".equalsIgnoreCase(loaderKey) && forgeVersion != null && !forgeVersion.isBlank()) {
            command.add("-e");
            command.add("FORGE_VERSION=" + forgeVersion);
        }
        command.add("-e");
        command.add("ENABLE_RCON=TRUE");
        command.add("-e");
        command.add("RCON_PASSWORD=" + RconClient.RCON_PASSWORD);
        command.add("-e");
        command.add("RCON_PORT=25575");
        command.add("--name");
        command.add(containerName);
        command.add(image);
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(TestServers.ROOT.toFile())
                .inheritIO();
        try {
            int exitCode = pb.start().waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to create docker container " + containerName + " (exit " + exitCode
                        + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Docker run interrupted for " + containerName, e);
        }
    }

    /**
     * Streams container and bind-mounted {@code latest.log} until the container
     * stops.
     */
    public void followLogsUntilExit(String longShutdownMarker, Consumer<String> logCallback) throws IOException {
        String containerName = containerName();
        AtomicLong exitGraceMs = new AtomicLong(CONTAINER_EXIT_GRACE_MS);
        try (Closeable _ = DockerContainerLogs.followContainer(containerName, line -> {
            // If the long shutdown marker is present, some process (like installing a
            // loader) is executing, thus we should wait longer.
            if (!longShutdownMarker.isBlank() && line.contains(longShutdownMarker)) {
                exitGraceMs.compareAndSet(CONTAINER_EXIT_GRACE_MS, CONTAINER_EXIT_GRACE_MS * 10);
            }
            logCallback.accept(line);
        })) {
            waitForContainerExit(containerName, exitGraceMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while following logs for " + containerName, e);
        }
    }

    private static void waitForContainerExit(String containerName, AtomicLong exitGraceMs)
            throws IOException, InterruptedException {
        long graceStart = System.currentTimeMillis();
        long lastGraceMs = exitGraceMs.get();
        long graceEnd = graceStart + lastGraceMs;
        while (System.currentTimeMillis() < graceEnd && isContainerRunning(containerName)) {
            long currentGraceMs = exitGraceMs.get();
            if (currentGraceMs != lastGraceMs) {
                graceEnd = graceStart + currentGraceMs;
                lastGraceMs = currentGraceMs;
            }
            Thread.sleep(500);
        }
        if (isContainerRunning(containerName)) {
            System.err.println("Container " + containerName
                    + " still running after " + (exitGraceMs.get() / 1000) + "s; forcing docker stop");
            forceStopContainer(containerName);
        }
        ProcessBuilder pb = new ProcessBuilder("docker", "wait", containerName)
                .redirectErrorStream(true);
        Process p = pb.start();
        int exitCode = p.waitFor();
        System.out.println("Container " + containerName + " exited with code " + exitCode);
    }

    private static boolean isContainerRunning(String containerName) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", containerName)
                .redirectErrorStream(true);
        Process p = pb.start();
        if (p.waitFor() != 0) {
            return false;
        }
        String state = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return "true".equals(state);
    }

    static void forceStopContainer(String containerName) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("docker", "stop", "-t", "10", containerName)
                .redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor();
    }

    /**
     * {@code itzg/minecraft-server} / mc-image-helper expect some MC versions
     * without a {@code .0} patch (e.g. {@code 1.8} not {@code 1.8.0}, {@code 1.12}
     * not {@code 1.12.0}). Newer Forge releases (1.13+) keep the full semver.
     */
    static String dockerMinecraftVersion(String version, String loaderKey) {
        if (!endsWithZeroPatch(version)) {
            return version;
        }
        int minor = minecraftMinorVersion(version);
        if ("FABRIC".equalsIgnoreCase(loaderKey) && minor <= 15) {
            return stripZeroPatch(version);
        } else if ("FORGE".equalsIgnoreCase(loaderKey) && minor <= 12) {
            return stripZeroPatch(version);
        }
        return version;
    }

    private static boolean endsWithZeroPatch(String version) {
        int lastDot = version.lastIndexOf('.');
        return lastDot >= 0 && version.indexOf('.') != lastDot && "0".equals(version.substring(lastDot + 1));
    }

    private static String stripZeroPatch(String version) {
        return version.substring(0, version.lastIndexOf('.'));
    }

    private static int minecraftMinorVersion(String version) {
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String dockerInstanceId() {
        return getLoaderKey().toLowerCase() + "-" + getVersion().replace('.', '_');
    }

    private String dockerContainerName() {
        return "chronos-" + dockerInstanceId();
    }

    public void removeDockerContainer() throws IOException {
        String name = containerName();
        ProcessBuilder pb = new ProcessBuilder("docker", "rm", "-f", name)
                .redirectErrorStream(true);
        try {
            pb.start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while removing container " + name, e);
        }
    }
}

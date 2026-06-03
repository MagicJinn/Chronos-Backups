package com.magicjinn.chronos.tooling.TestServers.docker;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

import com.magicjinn.chronos.tooling.TestServers.TestServers;

public class DockerMinecraftServer {
    private final String loaderKey;
    private final String version;
    private final int gamePort;
    private final int rconPort;

    public DockerMinecraftServer(String loaderKey, String version, int gamePort, int rconPort) {
        this.loaderKey = loaderKey;
        this.version = version;
        this.gamePort = gamePort;
        this.rconPort = rconPort;
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
        return loaderKey + "-" + version + " (game port: " + gamePort + ", RCON port: " + rconPort + ")";
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
        removeDockerContainer(containerName);
        String image = DockerServerImage.imageFor(getVersion());
        System.out.println("Starting " + containerName + " with image " + image);

        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "-d", "-it",
                "-p", gamePort + ":25565",
                "-p", rconPort + ":25575",
                "-v", dataDir.toAbsolutePath().normalize() + ":/data",
                "-e", "EULA=TRUE",
                "-e", "TYPE=" + getLoaderKey(),
                "-e", "VERSION=" + getVersion(),
                "-e", "ENABLE_RCON=TRUE",
                "-e", "RCON_PASSWORD=" + TestServers.RCON_PASSWORD,
                "-e", "RCON_PORT=25575",
                "--name", containerName,
                image)
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
    public void followLogsUntilExit(Consumer<String> logCallback) throws IOException {
        String containerName = containerName();
        try (Closeable _ = DockerContainerLogs.followContainer(containerName, line -> {
            logCallback.accept(line);
        })) {
            waitForContainerExit(containerName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while following logs for " + containerName, e);
        }
    }

    private static void waitForContainerExit(String containerName) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("docker", "wait", containerName)
                .redirectErrorStream(true);
        Process p = pb.start();
        int exitCode = p.waitFor();
        System.out.println("Container " + containerName + " exited with code " + exitCode);
    }

    private String dockerInstanceId() {
        return getLoaderKey().toLowerCase() + "-" + getVersion().replace('.', '_');
    }

    private String dockerContainerName() {
        return "chronos-" + dockerInstanceId();
    }

    private static void removeDockerContainer(String name) throws IOException {
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

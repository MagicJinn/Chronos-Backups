package com.magicjinn.chronos.tooling.TestServers.docker;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.magicjinn.chronos.tooling.TestServers.BukkitPluginLoaderAvailability;
import com.magicjinn.chronos.tooling.TestServers.TestServers;
import com.magicjinn.chronos.tooling.TestServers.rcon.RconClient;

public class DockerMinecraftServer {
    /** Max idle time after the last log line before forcing container stop. */
    private static final long IDLE_TIMEOUT_MS = 60_000;
    /** Idle timeout while a long-running install/shutdown step is in progress. */
    private static final long LONG_IDLE_TIMEOUT_MS = IDLE_TIMEOUT_MS * 5;

    private final String loaderKey;
    private final String version;
    /** MC version passed to the Docker {@code VERSION} env var (may differ from {@link #version}). */
    private final String dockerVersion;
    private int gamePort;
    private int rconPort;
    private final Path modJarPath;
    /** Optional. Passed as {@code FABRIC_LOADER_VERSION} for Fabric servers. */
    private final String fabricLoaderVersion;
    /**
     * Optional. Passed as {@code MODRINTH_PROJECTS} (e.g. {@code fabric-api}
     */
    private final String modrinthProjects;
    /**
     * Optional. Passed as {@code FORGE_VERSION} for Forge servers. Overrides the
     * Forge version that would otherwise be auto-resolved from the promotions JSON.
     */
    private final String forgeVersion;
    /**
     * Optional. Passed as {@code NEOFORGE_VERSION} for NeoForge servers when
     * mc-image-helper cannot resolve {@code latest} for the requested Minecraft version.
     */
    private final String neoForgeVersion;
    /**
     * Optional. Passed as {@code FORGE_INSTALLER_URL} for Forge servers whose
     * installer is not reachable via the standard Maven path (e.g. 1.10.0).
     */
    private final String forgeInstallerUrl;
    /** Optional. Passed as {@code PAPER_CHANNEL} for Paper servers (e.g. {@code experimental}). */
    private final String paperChannel;

    public DockerMinecraftServer(
            String loaderKey,
            String version,
            int gamePort,
            int rconPort,
            Path modJarPath) {
        this(loaderKey, version, version, modJarPath, null, null, null, null, null, null);
        this.gamePort = gamePort;
        this.rconPort = rconPort;
    }

    public DockerMinecraftServer(
            String loaderKey,
            String version,
            int gamePort,
            int rconPort,
            Path modJarPath,
            String fabricLoaderVersion,
            String modrinthProjects) {
        this(loaderKey, version, version, modJarPath, fabricLoaderVersion, modrinthProjects, null, null, null, null);
        this.gamePort = gamePort;
        this.rconPort = rconPort;
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
        this(loaderKey, version, version, modJarPath, fabricLoaderVersion, modrinthProjects, forgeVersion, null, null,
                null);
        this.gamePort = gamePort;
        this.rconPort = rconPort;
    }

    public DockerMinecraftServer(
            String loaderKey,
            String version,
            String dockerVersion,
            Path modJarPath,
            String fabricLoaderVersion,
            String modrinthProjects,
            String forgeVersion,
            String forgeInstallerUrl) {
        this(loaderKey, version, dockerVersion, modJarPath, fabricLoaderVersion, modrinthProjects, forgeVersion,
                forgeInstallerUrl, null, null);
    }

    public DockerMinecraftServer(
            String loaderKey,
            String version,
            String dockerVersion,
            Path modJarPath,
            String fabricLoaderVersion,
            String modrinthProjects,
            String forgeVersion,
            String forgeInstallerUrl,
            String neoForgeVersion) {
        this(loaderKey, version, dockerVersion, modJarPath, fabricLoaderVersion, modrinthProjects, forgeVersion,
                forgeInstallerUrl, neoForgeVersion, null);
    }

    public DockerMinecraftServer(
            String loaderKey,
            String version,
            String dockerVersion,
            Path modJarPath,
            String fabricLoaderVersion,
            String modrinthProjects,
            String forgeVersion,
            String forgeInstallerUrl,
            String neoForgeVersion,
            String paperChannel) {
        this.loaderKey = loaderKey;
        this.version = version;
        this.dockerVersion = dockerVersion;
        this.modJarPath = modJarPath;
        this.fabricLoaderVersion = fabricLoaderVersion;
        this.modrinthProjects = modrinthProjects;
        this.forgeVersion = forgeVersion;
        this.forgeInstallerUrl = forgeInstallerUrl;
        this.neoForgeVersion = neoForgeVersion;
        this.paperChannel = paperChannel;
    }

    /** Binds host ports immediately before {@link #createDockerContainer()}. */
    public void assignPorts(Set<Integer> usedPorts) throws IOException {
        gamePort = TestServers.allocatePort(usedPorts);
        rconPort = TestServers.allocatePort(usedPorts);
    }

    public void releasePorts(Set<Integer> usedPorts) {
        if (gamePort > 0) {
            usedPorts.remove(gamePort);
            gamePort = 0;
        }
        if (rconPort > 0) {
            usedPorts.remove(rconPort);
            rconPort = 0;
        }
    }

    public String getLoaderKey() {
        return loaderKey;
    }

    public String getVersion() {
        return version;
    }

    /** Key for {@code --only} filters and Docker instance ids (e.g. {@code fabric-1_14_4}). */
    public String testTargetKey() {
        return getLoaderKey().toLowerCase(Locale.ROOT) + "-" + getVersion().replace('.', '_');
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
        String ports = gamePort > 0 && rconPort > 0
                ? " (game port: " + gamePort + ", RCON port: " + rconPort + ')'
                : "";
        String forgeDetail = optionalDetail("FORGE_INSTALLER_URL", forgeInstallerUrl);
        String env = optionalDetail("FABRIC_LOADER_VERSION", fabricLoaderVersion)
                + optionalDetail("MODRINTH_PROJECTS", modrinthProjects)
                + (forgeDetail.isEmpty() ? optionalDetail("FORGE_VERSION", forgeVersion) : forgeDetail)
                + optionalDetail("NEOFORGE_VERSION", neoForgeVersion)
                + optionalDetail("PAPER_CHANNEL", paperChannel);
        return loaderKey + '-' + version + ports + " (mod jar: " + modJarPath.getFileName() + ')' + env;
    }

    private static String optionalDetail(String label, String value) {
        return value != null && !value.isBlank() ? " (" + label + ": " + value + ')' : "";
    }

    public Path dataDir() {
        return TestServers.ROOT.resolve("build/test-docker-servers").resolve(dockerInstanceId());
    }

    public String containerName() {
        return dockerContainerName();
    }

    /**
     * Sends a command to the server console via itzg's {@code mc-send-to-console}.
     * Requires {@code CREATE_CONSOLE_IN_PIPE=TRUE} on the container.
     */
    public void sendConsoleCommand(String command) throws IOException, InterruptedException {
        String container = containerName();
        List<String> parts = splitCommandParts(command);
        IOException lastFailure = null;
        for (String[] execPrefix :
                new String[][] {{"docker", "exec", "-u0", container}, {"docker", "exec", "--user", "1000", container}}) {
            try {
                runMcSendToConsole(execPrefix, parts);
                return;
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IOException("mc-send-to-console failed for " + container);
    }

    private static List<String> splitCommandParts(String command) {
        List<String> parts = new ArrayList<>();
        for (String part : command.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static void runMcSendToConsole(String[] execPrefix, List<String> commandParts)
            throws IOException, InterruptedException {
        List<String> exec = new ArrayList<>(List.of(execPrefix));
        exec.add("mc-send-to-console");
        exec.addAll(commandParts);
        ProcessBuilder pb = new ProcessBuilder(exec).redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(
                    "mc-send-to-console failed (exit " + exit + "): " + output.trim());
        }
    }

    /** Folia RCON dispatch is broken upstream. Only console delivery is reliable there. */
    public boolean supportsRconCommandFallback() {
        // TODO: Softcode this
        return !"FOLIA".equalsIgnoreCase(loaderKey);
    }

    /**
     * Starts a detached container matching the itzg/minecraft-server compose
     * defaults.
     */
    public void createDockerContainer() throws IOException {
        if (gamePort <= 0 || rconPort <= 0) {
            throw new IllegalStateException("Call assignPorts() before createDockerContainer()");
        }
        if (gamePort == rconPort) {
            throw new IllegalArgumentException("game and RCON ports must differ");
        }
        Path dataDir = dataDir();
        Files.createDirectories(dataDir);
        resetSavedWorlds(dataDir);
        stageModJar(dataDir);
        String containerName = containerName();
        removeDockerContainer();
        String image = DockerServerImage.imageFor(getVersion());
        System.out.println("Starting " + containerName + " with image " + image);

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
        command.add("-e");
        command.add("EULA=TRUE");
        command.add("-e");
        command.add("TYPE=" + getLoaderKey());
        command.add("-e");
        command.add("VERSION=" + dockerVersion);
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
        } else if ("FORGE".equalsIgnoreCase(loaderKey)) {
            command.add("-e");
            command.add("JVM_DD_OPTS=fml.queryResult=confirm");
            if (forgeInstallerUrl != null && !forgeInstallerUrl.isBlank()) {
                command.add("-e");
                command.add("FORGE_INSTALLER_URL=" + forgeInstallerUrl);
            } else if (forgeVersion != null && !forgeVersion.isBlank()) {
                command.add("-e");
                command.add("FORGE_VERSION=" + forgeVersion);
            }
        } else if ("NEOFORGE".equalsIgnoreCase(loaderKey) && neoForgeVersion != null && !neoForgeVersion.isBlank()) {
            command.add("-e");
            command.add("NEOFORGE_VERSION=" + neoForgeVersion);
        } else if ("PAPER".equalsIgnoreCase(loaderKey) || "FOLIA".equalsIgnoreCase(loaderKey)) {
            if (paperChannel != null && !paperChannel.isBlank()) {
                command.add("-e");
                command.add("PAPER_CHANNEL=" + paperChannel);
            }
        } else if ("PURPUR".equalsIgnoreCase(loaderKey)) {
            command.add("-e");
            command.add("PURPUR_BUILD=latest");
        }
        command.add("-e");
        command.add("CREATE_CONSOLE_IN_PIPE=TRUE");
        command.add("-e");
        command.add("ENABLE_RCON=TRUE");
        command.add("-e");
        command.add("RCON_PASSWORD=" + RconClient.RCON_PASSWORD);
        command.add("-e");
        command.add("RCON_PORT=25575");
        addTestServerEnvironment(command);
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
    public void followLogsUntilExit(List<String> longShutdownMarkers, Consumer<String> logCallback) throws IOException {
        String containerName = containerName();
        AtomicLong lastLogMs = new AtomicLong(System.currentTimeMillis());
        AtomicLong idleTimeoutMs = new AtomicLong(IDLE_TIMEOUT_MS);
        try (Closeable _ = DockerContainerLogs.followContainer(containerName, line -> {
            lastLogMs.set(System.currentTimeMillis());
            // Long install/shutdown/world-prep steps may go quiet for a while.
            for (String marker : longShutdownMarkers) {
                if (marker != null && !marker.isBlank() && line.contains(marker)) {
                    idleTimeoutMs.set(LONG_IDLE_TIMEOUT_MS);
                    break;
                }
            }
            logCallback.accept(line);
        })) {
            waitForContainerExit(containerName, lastLogMs, idleTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while following logs for " + containerName, e);
        }
    }

    private static void waitForContainerExit(String containerName, AtomicLong lastLogMs, AtomicLong idleTimeoutMs)
            throws IOException, InterruptedException {
        while (isContainerRunning(containerName)) {
            long idleMs = System.currentTimeMillis() - lastLogMs.get();
            if (idleMs >= idleTimeoutMs.get()) {
                System.err.println("Container " + containerName + " idle for " + (idleMs / 1000)
                        + "s (limit " + (idleTimeoutMs.get() / 1000) + "s); forcing docker stop");
                forceStopContainer(containerName);
                break;
            }
            Thread.sleep(500);
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
    public static String dockerMinecraftVersion(String version, String loaderKey) {
        if (!endsWithZeroPatch(version)) {
            return version;
        }
        int minor = minecraftMinorVersion(version);
        if ("FABRIC".equalsIgnoreCase(loaderKey)) {
            return stripZeroPatch(version);
        }
        if (BukkitPluginLoaderAvailability.isBukkitFamilyLoader(loaderKey)) {
            return stripZeroPatch(version);
        }
        if ("FORGE".equalsIgnoreCase(loaderKey) && minor <= 12) {
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
        return testTargetKey();
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

    /** Drops saved dimensions so a prior run cannot leave a world from another MC/Forge line. */
    static void resetSavedWorlds(Path dataDir) throws IOException {
        for (String name : List.of("world", "world_nether", "world_the_end", "DIM-1", "DIM1", "backups")) {
            deleteRecursively(dataDir.resolve(name));
        }
        Files.deleteIfExists(dataDir.resolve("server.properties"));
    }

    /**
     * Docker env vars for {@code server.properties}. Applied during container init on
     * this run (before the JVM starts). {@code OVERRIDE_SERVER_PROPERTIES=true} ensures
     * they win over a leftover file in the bind-mounted {@code /data} directory.
     */
    private static void addTestServerEnvironment(List<String> command) {
        command.add("-e");
        command.add("OVERRIDE_SERVER_PROPERTIES=TRUE");
        command.add("-e");
        command.add("MAX_TICK_TIME=-1");
        command.add("-e");
        command.add("VIEW_DISTANCE=4");
        command.add("-e");
        command.add("SIMULATION_DISTANCE=4");
        command.add("-e");
        command.add("SPAWN_MONSTERS=false");
        command.add("-e");
        command.add("SPAWN_ANIMALS=false");
    }

    /**
     * Copies the built mod/plugin jar into the server data directory. Avoids
     * bind-mounting a single host file on Windows, which can serve a stale or
     * truncated jar when Gradle replaces the build output.
     */
    private void stageModJar(Path dataDir) throws IOException {
        Path source = modJarPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Mod jar does not exist: " + source);
        }
        String folder = BukkitPluginLoaderAvailability.isBukkitFamilyLoader(loaderKey) ? "plugins" : "mods";
        Path targetDir = dataDir.resolve(folder);
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve("chronosbackups.jar");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}

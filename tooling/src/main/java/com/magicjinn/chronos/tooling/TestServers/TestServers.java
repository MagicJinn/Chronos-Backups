package com.magicjinn.chronos.tooling.TestServers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.magicjinn.chronos.tooling.TestServers.docker.DockerMinecraftServer;
import com.magicjinn.chronos.tooling.TestServers.rcon.RconClient;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class TestServers {
    private static final Gson GSON = new Gson();
    public static final Path ROOT = locateRepoRoot();
    /** The path to the chronos-compile-groups file from the repository root. */
    private static final Path GROUPS_FILE = ROOT.resolve("gradle/chronos-compile-groups.json");
    /** The path to the test-servers-config file from the repository root. */
    private static final Path CONFIG_FILE = ROOT
            .resolve("tooling/src/main/java/com/magicjinn/chronos/tooling/TestServers/test-servers-config.json");

    /** The RCON password for the test servers. */
    public static final String RCON_PASSWORD = "password";

    private TestServers() {
    }

    public static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public static int allocatePort() throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 256; attempt++) {
            int port = 30000 + rnd.nextInt(35000);
            if (!isPortOccupied(port)) {
                return port;
            }
        }
        throw new IOException("Failed to allocate port after 256 attempts");
    }

    public static boolean isPortOccupied(int port) {
        try (ServerSocket _ = new ServerSocket(port)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    public static String sendRconCommand(int port, String message) throws IOException {
        return RconClient.send("127.0.0.1", port, RCON_PASSWORD, message);
    }

    private static Map<String, Object> readObject(Path file) throws IOException {
        Type type = new TypeToken<Map<String, Object>>() {
        }.getType();
        return GSON.fromJson(Files.readString(file), type);
    }

    private static void runBuildAll() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(gradleWrapperCommand(ROOT), "buildAll")
                .directory(ROOT.toFile())
                .inheritIO();
        try {
            int exitCode = pb.start().waitFor();
            if (exitCode != 0) {
                throw new IOException("buildAll failed with exit code " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("buildAll interrupted", e);
        }
    }

    private static String gradleWrapperCommand(Path cwd) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return cwd.resolve("gradlew.bat").toAbsolutePath().normalize().toString();
        }
        return cwd.resolve("gradlew").toAbsolutePath().normalize().toString();
    }

    private static Path locateRepoRoot() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                    .redirectErrorStream(true)
                    .start();
            if (process.waitFor() == 0) {
                return Path.of(new String(process.getInputStream().readAllBytes()).trim());
            }
        } catch (IOException | InterruptedException e) {
            // fall through
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    // Use a comparator to sort the servers by version and loader key
    private static final Comparator<DockerMinecraftServer> SERVER_ORDER = Comparator
            .comparing(DockerMinecraftServer::getVersion, TestServers::compareVersions)
            .thenComparing(DockerMinecraftServer::getLoaderKey);

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int cmp = Integer.compare(Integer.parseInt(pa[i]), Integer.parseInt(pb[i]));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static TreeSet<DockerMinecraftServer> getServers(List<Map<String, Object>> groups) {
        TreeSet<DockerMinecraftServer> servers = new TreeSet<>(SERVER_ORDER);
        for (Map<String, Object> group : groups) {
            if (!shouldBuildGroup(group))
                continue;

            // If the group contains a supportedVersions key, then all loaders support all
            // versions. If the key is not present, that means some loaders do not support
            // all versions (eg forge 1.20.0-1.20.1, Fabric 1.20.0-1.20.6)
            boolean allLoadersSupportAllVersions = group.containsKey("supportedVersions");
            for (Map.Entry<String, Object> entry : group.entrySet()) {
                Object value = entry.getValue();

                if (!(value instanceof Map<?, ?>))
                    continue;

                Map<String, Object> unified = castMap(value);

                if (!unified.containsKey("loaderKey"))
                    continue;

                String loaderKey = str(unified.get("loaderKey"));

                List<String> versions = allLoadersSupportAllVersions
                        ? strList(group.get("supportedVersions"))
                        : strList(unified.get("supportedVersions"));

                for (String version : versions) {
                    try {
                        int gamePort = allocatePort();
                        int rconPort = allocatePort();
                        servers.add(new DockerMinecraftServer(loaderKey, version, gamePort, rconPort));
                    } catch (IOException e) {
                        System.err.println(
                                "Failed to allocate port for " + loaderKey + "-" + version + ": " + e.getMessage());
                    }
                }
            }
        }

        for (DockerMinecraftServer server : servers) {
            System.out.println(server);
        }

        return servers;
    }

    public static void main(String[] args) throws IOException {
        if (!isDockerAvailable()) {
            System.err
                    .println("Docker is not available. Please ensure Docker is installed and running, then try again.");
            System.exit(1);
        }
        // TODO: remove?
        System.out.println("Docker is available.");
        System.out.println("Repo root: " + ROOT);

        Map<String, Object> groupsJson = readObject(GROUPS_FILE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) groupsJson.get("groups");
        System.out.println("Loaded " + groups.size() + " compile groups from " + GROUPS_FILE.getFileName());

        Map<String, Object> config = readObject(CONFIG_FILE);
        List<String> readyMarkers = strList(config.get("readyMarkers"));
        String testCommand = str(config.get("testCommand"));
        List<String> failureMarkers = strList(config.get("failureMarkers"));
        List<String> successMarkers = strList(config.get("successMarkers"));

        // Run buildAll first
        // runBuildAll(); TODO: Enable

        TreeSet<DockerMinecraftServer> servers = getServers(groups);

        // start a test docker container
        for (DockerMinecraftServer server : servers) {
            server.createDockerContainer();
            server.followLogsUntilExit(logLine -> {
                System.out.println("[callback] " + logLine); // TODO: remove
                for (String marker : readyMarkers) {
                    if (logLine.contains(marker)) {
                        System.out.println("Ready marker hit: " + marker);
                    }
                }
                for (String marker : failureMarkers) {
                    if (logLine.contains(marker)) {
                        System.out.println("Failure marker hit: " + marker);
                    }
                }
                for (String marker : successMarkers) {
                    if (logLine.contains(marker)) {
                        System.out.println("Success marker hit: " + marker);
                    }
                }
            });
            break; // TODO: remove
        }
    }

    private static boolean shouldBuildGroup(Map<String, Object> group) {
        Object value = group.get("shouldBuild");
        return !(value instanceof Boolean b) || b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object value) {
        if (value == null)
            return List.of();
        return (List<String>) value;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
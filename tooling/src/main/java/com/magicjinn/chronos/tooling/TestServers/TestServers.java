package com.magicjinn.chronos.tooling.TestServers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.magicjinn.chronos.tooling.TestServers.docker.DockerMinecraftServer;
import com.magicjinn.chronos.tooling.TestServers.rcon.RconClient;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.ServerSocket;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TestServers {
    private static final Gson GSON = new Gson();
    public static final Path ROOT = locateRepoRoot();
    /** The path to the chronos-compile-groups file from the repository root. */
    private static final Path GROUPS_FILE = ROOT.resolve("gradle/chronos-compile-groups.json");
    /** The path to the test-servers-config file from the repository root. */
    private static final Path CONFIG_FILE = ROOT
            .resolve("tooling/src/main/java/com/magicjinn/chronos/tooling/TestServers/test-servers-config.json");

    private TestServers() {
    }

    public static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public static int allocatePort() throws IOException {
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

    private static Map<String, Object> readObject(Path file) throws IOException {
        Type type = new TypeToken<Map<String, Object>>() {
        }.getType();
        return GSON.fromJson(Files.readString(file), type);
    }

    /** Use git to locate the repository root. */
    private static Path locateRepoRoot() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                    .redirectErrorStream(true)
                    .start();
            if (process.waitFor() == 0) {
                return Path.of(new String(process.getInputStream().readAllBytes()).trim());
            }
        } catch (IOException | InterruptedException e) {
            // ignore
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

    private static Map<String, Object> findGroupForVersion(List<Map<String, Object>> groups, String version) {
        for (Map<String, Object> group : groups) {
            if (!shouldBuildGroup(group))
                continue;
            // If supportedVersions is at the group root, use it for all loaders
            if (strList(group.get("supportedVersions")).contains(version)) {
                return group;
            }
            // Otherwise, check each loader's supportedVersions
            for (Map<String, Object> loaderObj : unifiedLoaderConfigs(group)) {
                if (strList(loaderObj.get("supportedVersions")).contains(version)) {
                    return group;
                }
            }
        }
        return null;
    }

    private static String resolveArchiveSuffix(Map<String, Object> group, String loaderKey) {
        Map<String, Object> subObj = findUnifiedConfig(group, loaderKey);

        if (subObj != null) {
            String override = str(subObj.get("archiveVersionTag"));
            if (!override.isBlank()) {
                return override;
            }
            String refMc = str(subObj.get("referenceMinecraft"));
            if (!refMc.isBlank()) {
                return minecraftLineTag(refMc);
            }
        }

        String label = str(group.get("jarTargetLabel"));
        if (!label.isBlank())
            return label;

        List<String> prefixes = strList(group.get("minecraftVersionPrefixes"));
        if (!prefixes.isEmpty())
            return minecraftLineTag(prefixes.get(0));

        throw new IllegalStateException("Cannot resolve archive suffix for group " + group.get("id"));
    }

    private static String minecraftLineTag(String version) {
        String[] p = version.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] + ".x" : version + ".x";
    }

    /**
     * Resolves {@code MODRINTH_PROJECTS} for Fabric test servers.
     */
    static String resolveFabricModrinthProjects(String minecraftVersion, Map<String, Object> group) {
        Map<String, Object> fabricUnified = castMap(group.get("fabricUnified"));
        if (fabricUnified == null) {
            return "fabric-api";
        }
        String fabricApi = str(fabricUnified.get("fabricApi"));
        if (fabricApi.isBlank()) {
            return "fabric-api";
        }
        String referenceMc = str(fabricUnified.get("referenceMinecraft"));
        if (!referenceMc.isBlank() && !referenceMc.equals(minecraftVersion)) {
            return "fabric-api";
        }
        return "fabric:fabric-api:" + fabricApi;
    }

    static String resolveFabricLoaderVersion(String minecraftVersion, Map<String, Object> group) {
        Map<String, Object> fabricUnified = castMap(group.get("fabricUnified"));
        if (fabricUnified == null) {
            return "";
        }
        String referenceMc = str(fabricUnified.get("referenceMinecraft"));
        if (!referenceMc.isBlank() && !referenceMc.equals(minecraftVersion)) {
            return "";
        }
        return str(fabricUnified.get("fabricLoader"));
    }

    /**
     * Resolves {@code FORGE_VERSION} for Forge test servers.
     * Specifically, 1.10.0 has an incorrectly structured version URL.
     */
    @SuppressWarnings("unchecked")
    static String resolveForgeVersionOverride(String minecraftVersion, Map<String, Object> unified) {
        Object raw = unified.get("forgeVersionOverrides");
        if (!(raw instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, String> overrides = (Map<String, String>) raw;
        return overrides.get(minecraftVersion);
    }

    private static Path findJarForServer(String loaderKey, String version, List<Map<String, Object>> groups)
            throws IOException {
        Map<String, Object> group = findGroupForVersion(groups, version);
        if (group == null) {
            throw new IllegalArgumentException(
                    "No compile group found for " + loaderKey + " version " + version);
        }

        String suffix = resolveArchiveSuffix(group, loaderKey);
        String loaderLower = loaderKey.toLowerCase();
        Path buildLibs = ROOT.resolve("build/libs");
        String pattern = "chronosbackups-" + suffix + "-*-" + loaderLower + ".jar";

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(buildLibs, pattern)) {
            Iterator<Path> it = stream.iterator();
            if (!it.hasNext()) {
                throw new IllegalArgumentException(
                        "No jar found matching " + pattern + " in " + buildLibs);
            }
            Path jar = it.next();
            if (it.hasNext()) {
                System.err.println("Warning: multiple jars match " + pattern + ", using " + jar.getFileName());
            }
            return jar.toAbsolutePath().normalize();
        }
    }

    private static TreeSet<DockerMinecraftServer> getServers(List<Map<String, Object>> groups) throws IOException {
        TreeSet<DockerMinecraftServer> servers = new TreeSet<>(SERVER_ORDER);

        for (Map<String, Object> group : groups) {
            if (!shouldBuildGroup(group))
                continue;
            boolean allLoadersSupportAllVersions = group.containsKey("supportedVersions");
            for (Map<String, Object> unified : unifiedLoaderConfigs(group)) {
                String loaderKey = str(unified.get("loaderKey"));
                List<String> versions = allLoadersSupportAllVersions
                        ? strList(group.get("supportedVersions"))
                        : strList(unified.get("supportedVersions"));

                for (String version : versions) {
                    int gamePort = allocatePort();
                    int rconPort = allocatePort();
                    Path modJar = findJarForServer(loaderKey, version, groups);
                    String fabricLoader = null;
                    String modrinthProjects = null;
                    String forgeVersion = null;

                    // Hardcode this for now
                    if ("FABRIC".equalsIgnoreCase(loaderKey)) {
                        fabricLoader = resolveFabricLoaderVersion(version, group);
                        modrinthProjects = resolveFabricModrinthProjects(version, group);
                    } else if ("FORGE".equalsIgnoreCase(loaderKey)) {
                        forgeVersion = resolveForgeVersionOverride(version, unified);
                    }
                    servers.add(new DockerMinecraftServer(
                            loaderKey, version, gamePort, rconPort, modJar, fabricLoader, modrinthProjects,
                            forgeVersion));
                }
            }
        }

        if (servers.isEmpty()) {
            throw new IOException("No test servers resolved from compile groups.");
        }

        for (DockerMinecraftServer server : servers) {
            System.out.println(server);
        }
        return servers;
    }

    public static void main(String[] args) throws IOException {
        if (!isDockerAvailable()) {
            System.err.println(
                    "Docker is not available. Install Docker Desktop (or Docker Engine), start it, then re-run.");
            System.exit(1);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) readObject(GROUPS_FILE).get("groups");
        Map<String, Object> config = readObject(CONFIG_FILE);
        System.out.println("Testing all supported loader/version pairs.");
        TreeSet<DockerMinecraftServer> servers = getServers(groups);
        System.out.println("Found " + servers.size() + " servers to test.");

        List<String> readyMarkers = strList(config.get("readyMarkers"));
        String testCommand = str(config.get("testCommand"));
        List<String> failureMarkers = strList(config.get("failureMarkers"));
        List<String> serverSetupFailureMarkers = strList(config.get("serverSetupFailureMarkers"));
        List<String> successMarkers = strList(config.get("successMarkers"));
        String longShutdownMarker = str(config.get("longShutdownMarker"));

        List<String> failures = new ArrayList<>();

        for (DockerMinecraftServer server : servers) {
            server.createDockerContainer();
            boolean[] readySeen = new boolean[readyMarkers.size()];
            AtomicBoolean rconSent = new AtomicBoolean(false);
            AtomicBoolean stopSent = new AtomicBoolean(false);
            AtomicBoolean successSeen = new AtomicBoolean(false);
            AtomicBoolean failureSeen = new AtomicBoolean(false);
            AtomicBoolean serverSetupFailureSeen = new AtomicBoolean(false);
            server.followLogsUntilExit(longShutdownMarker, logLine -> {
                for (int i = 0; i < readyMarkers.size(); i++) {
                    if (!readySeen[i] && containsIgnoreCase(logLine, readyMarkers.get(i))) {
                        readySeen[i] = true;
                        System.out.println("Ready marker hit: " + readyMarkers.get(i));
                    }
                }
                if (allReadyMarkersSeen(readySeen) && rconSent.compareAndSet(false, true)) {
                    System.out.println("All ready markers seen. Sending RCON: " + testCommand);
                    Thread rconThread = new Thread(() -> {
                        try {
                            String response = RconClient.send(server.getRconPort(), testCommand);
                            if (!response.isBlank()) {
                                System.out.println("RCON response: " + response.trim());
                            }
                        } catch (IOException e) {
                            if (!RconClient.isBenignShutdownIoMessage(e.getMessage())) {
                                System.err.println("Failed to send RCON command to " + server.getRconPort() + ": "
                                        + e.getMessage());
                            }
                        }
                    }, "rcon-cmd-" + server.containerName());
                    rconThread.setDaemon(true);
                    rconThread.start();
                }
                for (String marker : failureMarkers) {
                    if (containsIgnoreCase(logLine, marker)) {
                        failureSeen.set(true);
                        System.err.println("Failure marker hit: " + marker);
                        RconClient.stopServer(server);
                    }
                }
                for (String marker : serverSetupFailureMarkers) {
                    if (containsIgnoreCase(logLine, marker)) {
                        serverSetupFailureSeen.set(true);
                        System.err.println("Server setup failure marker hit: " + marker);
                        RconClient.stopServer(server);
                    }
                }
                for (String marker : successMarkers) {
                    if (containsIgnoreCase(logLine, marker) && stopSent.compareAndSet(false, true)) {
                        successSeen.set(true);
                        System.out.println("Success marker hit: " + marker);
                        RconClient.stopServer(server);
                    }
                }
            });
            if (serverSetupFailureSeen.get()) {
                failures.add(server + " (server setup failed)");
            } else if (failureSeen.get()) {
                failures.add(server + " (failure marker in logs)");
            } else if (!successSeen.get()) {
                failures.add(server + " (no success marker before container exit)");
            }
        }

        if (!failures.isEmpty()) {
            System.err.println("Docker test failures (" + failures.size() + "):");
            for (String failure : failures) {
                System.err.println("  - " + failure);
            }
            System.exit(1);
        }
        System.out.println("All " + servers.size() + " docker server tests passed.");
    }

    private static List<Map<String, Object>> unifiedLoaderConfigs(Map<String, Object> group) {
        List<Map<String, Object>> configs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : group.entrySet()) {
            if (!entry.getKey().toLowerCase(Locale.ROOT).contains("unified")) {
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> unified = castMap(entry.getValue());
            if (unified.containsKey("loaderKey")) {
                configs.add(unified);
            }
        }
        return configs;
    }

    private static Map<String, Object> findUnifiedConfig(Map<String, Object> group, String loaderKey) {
        for (Map<String, Object> unified : unifiedLoaderConfigs(group)) {
            if (loaderKey.equalsIgnoreCase(str(unified.get("loaderKey"))))
                return unified;
        }
        return null;
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
        return value == null ? List.of() : (List<String>) value;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean allReadyMarkersSeen(boolean[] readySeen) {
        for (boolean seen : readySeen) {
            if (!seen)
                return false;
        }
        return true;
    }
}
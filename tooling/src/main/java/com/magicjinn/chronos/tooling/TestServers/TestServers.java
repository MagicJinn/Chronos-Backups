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
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

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
        return allocatePort(Set.of());
    }

    public static int allocatePort(Set<Integer> reserved) throws IOException {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 256; attempt++) {
            int port = 30000 + rnd.nextInt(35000);
            if (!reserved.contains(port) && !isPortOccupied(port)) {
                reserved.add(port);
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

    private static String minecraftLineTag(String version) {
        String[] p = version.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] + ".x" : version + ".x";
    }

    /**
     * Resolves {@code MODRINTH_PROJECTS} for Fabric test servers.
     * Pinned {@code fabric-api} versions are tied to {@code referenceMinecraft}.
     * Other patches in the group use generic {@code fabric-api} so Modrinth
     * resolves a compatible release for that game version.
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
        if (!referenceMc.isBlank() && !minecraftVersion.equals(referenceMc)) {
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
     * Resolves {@code NEOFORGE_VERSION} for NeoForge test servers.
     */
    @SuppressWarnings("unchecked")
    static String resolveNeoForgeVersionOverride(String minecraftVersion, Map<String, Object> unified) {
        Object raw = unified.get("neoForgeVersionOverrides");
        if (!(raw instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, String> overrides = (Map<String, String>) raw;
        return overrides.get(minecraftVersion);
    }

    /**
     * Resolves {@code NEOFORGE_VERSION} when mc-image-helper cannot auto-select
     * {@code latest} (explicit overrides, or the reference NeoForge build for the
     * reference Minecraft patch).
     */
    static String resolveNeoForgeVersion(String minecraftVersion, String dockerVersion, Map<String, Object> unified) {
        String override = resolveNeoForgeVersionOverride(minecraftVersion, unified);
        if (override != null && !override.isBlank()) {
            return override;
        }
        String referenceMc = str(unified.get("referenceMinecraft"));
        String referenceNeo = str(unified.get("neoForge"));
        if (referenceNeo.isBlank() || referenceMc.isBlank()) {
            return null;
        }
        if (minecraftVersion.equals(referenceMc) || dockerVersion.equals(referenceMc)) {
            return referenceNeo;
        }
        return null;
    }

    /**
     * Resolves {@code FORGE_VERSION} for Forge test servers.
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

    /**
     * mc-image-helper {@code --forge-version} expects the bare Forge build id (e.g.
     * {@code 10.13.4.1614}), not Gradle loader coordinates
     * ({@code 10.13.4.1614-1.7.10}).
     */
    static String normalizeForgeVersionForDocker(String forgeVersion) {
        if (forgeVersion.isBlank()) {
            return forgeVersion;
        }
        int dash = forgeVersion.indexOf('-');
        if (dash <= 0) {
            return forgeVersion;
        }
        String suffix = forgeVersion.substring(dash + 1);
        if (suffix.startsWith("mc") || suffix.matches("\\d+(\\.\\d+)+.*")) {
            return forgeVersion.substring(0, dash);
        }
        return forgeVersion;
    }

    /**
     * Resolves {@code FORGE_VERSION} when mc-image-helper cannot auto-select a
     * valid installer (reference MC, mapped {@code .0} patches, or explicit
     * overrides). Legacy lines like 1.12.x use a different Forge build per patch.
     * Leave unset so the image resolves the correct installer for
     * {@code dockerVersion}.
     */
    static String resolveForgeVersion(String minecraftVersion, String dockerVersion, Map<String, Object> unified) {
        String override = resolveForgeVersionOverride(minecraftVersion, unified);
        if (override != null && !override.isBlank()) {
            return normalizeForgeVersionForDocker(override);
        }
        String referenceMc = str(unified.get("referenceMinecraft"));
        String referenceForge = str(unified.get("forge"));
        if (referenceForge.isBlank() || referenceMc.isBlank()) {
            return null;
        }
        if (minecraftVersion.equals(referenceMc) || dockerVersion.equals(referenceMc)) {
            return normalizeForgeVersionForDocker(referenceForge);
        }
        return null;
    }

    /**
     * Maps compile-group MC versions to values accepted by itzg/minecraft-server /
     * mc-image-helper (e.g. Fabric loader meta uses {@code 1.16}, not
     * {@code 1.16.0}. Many Forge lines never published {@code x.y.0} installers).
     */
    static String resolveDockerMinecraftVersion(String version, String loaderKey, Map<String, Object> unified) {
        String normalized = DockerMinecraftServer.dockerMinecraftVersion(version, loaderKey);
        if ("FORGE".equalsIgnoreCase(loaderKey) && endsWithZeroPatch(version)) {
            int minor = minecraftMinorVersion(version);
            if (minor >= 13) {
                String referenceMc = str(unified.get("referenceMinecraft"));
                if (!referenceMc.isBlank()) {
                    return referenceMc;
                }
            }
        }
        return normalized;
    }

    private static boolean endsWithZeroPatch(String version) {
        int lastDot = version.lastIndexOf('.');
        return lastDot >= 0 && version.indexOf('.') != lastDot && "0".equals(version.substring(lastDot + 1));
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

    /**
     * Resolves {@code FORGE_INSTALLER_URL} for Forge test servers whose installer
     * is not reachable via the standard Maven path (e.g. 1.10.0).
     */
    @SuppressWarnings("unchecked")
    static String resolveForgeInstallerUrlOverride(String minecraftVersion, Map<String, Object> unified) {
        Object raw = unified.get("forgeInstallerUrlOverrides");
        if (!(raw instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, String> overrides = (Map<String, String>) raw;
        return overrides.get(minecraftVersion);
    }

    private static final String LINUX_RUST_PRUNER_NATIVE = "natives/linux-x86_64/librust_pruner.so";

    private static void requireLinuxRustPrunerNative(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry(LINUX_RUST_PRUNER_NATIVE) == null) {
                throw new IllegalStateException(
                        jar.getFileName()
                                + " is missing "
                                + LINUX_RUST_PRUNER_NATIVE
                                + ". Rebuild with ./gradlew clean prepareTestServers (Docker must be running to build the Linux rust-pruner).");
            }
        }
    }

    private static Path findJarForUnified(Map<String, Object> unified, String loaderKey, Map<String, Object> group)
            throws IOException {
        String suffix = str(unified.get("archiveVersionTag"));
        if (suffix.isBlank()) {
            suffix = str(group.get("jarTargetLabel"));
        }
        if (suffix.isBlank()) {
            List<String> prefixes = strList(group.get("minecraftVersionPrefixes"));
            if (!prefixes.isEmpty()) {
                suffix = minecraftLineTag(prefixes.get(0));
            }
        }
        if (suffix.isBlank()) {
            throw new IllegalStateException(
                    "archiveVersionTag (or group jarTargetLabel) is required for unified " + loaderKey
                            + " in group " + group.get("id"));
        }
        String loaderLower = loaderKey.toLowerCase(Locale.ROOT);
        Path buildLibs = ROOT.resolve("build/libs");
        String pattern = "chronosbackups-" + suffix + "-*-" + loaderLower + ".jar";

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(buildLibs, pattern)) {
            Path jar = null;
            for (Path candidate : stream) {
                if (jar == null || Files.getLastModifiedTime(candidate).compareTo(Files.getLastModifiedTime(jar)) > 0) {
                    jar = candidate;
                }
            }
            if (jar == null) {
                throw new IllegalArgumentException(
                        "No jar found matching " + pattern + " in " + buildLibs);
            }
            try (DirectoryStream<Path> again = Files.newDirectoryStream(buildLibs, pattern)) {
                long matches = 0;
                for (Path ignored : again) {
                    matches++;
                }
                if (matches > 1) {
                    TestServersConsole.warn(
                            "Warning: multiple jars match "
                                    + pattern
                                    + ", using newest "
                                    + jar.getFileName());
                }
            }
            requireLinuxRustPrunerNative(jar);
            return jar.toAbsolutePath().normalize();
        }
    }

    private static TreeSet<DockerMinecraftServer> getServers(
            List<Map<String, Object>> groups, Set<String> only) throws IOException {
        Set<String> normalizedOnly = only.isEmpty()
                ? Set.of()
                : only.stream().map(TestServers::normalizeTargetKey).collect(java.util.stream.Collectors.toSet());
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
                    if (!normalizedOnly.isEmpty()
                            && !normalizedOnly.contains(normalizeTargetKey(loaderKey + "-" + version))) {
                        continue;
                    }
                    Path modJar = findJarForUnified(unified, loaderKey, group);
                    String dockerVersion = resolveDockerMinecraftVersion(version, loaderKey, unified);
                    String fabricLoader = null;
                    String modrinthProjects = null;
                    String forgeVersion = null;
                    String forgeInstallerUrl = null;
                    String neoForgeVersion = null;

                    if ("FABRIC".equalsIgnoreCase(loaderKey)) {
                        fabricLoader = resolveFabricLoaderVersion(version, group);
                        modrinthProjects = resolveFabricModrinthProjects(version, group);
                    } else if ("FORGE".equalsIgnoreCase(loaderKey)) {
                        forgeInstallerUrl = resolveForgeInstallerUrlOverride(version, unified);
                        if (forgeInstallerUrl == null) {
                            forgeVersion = resolveForgeVersion(version, dockerVersion, unified);
                        }
                    } else if ("NEOFORGE".equalsIgnoreCase(loaderKey)) {
                        neoForgeVersion = resolveNeoForgeVersion(version, dockerVersion, unified);
                    }
                    servers.add(new DockerMinecraftServer(
                            loaderKey, version, dockerVersion, modJar, fabricLoader,
                            modrinthProjects, forgeVersion, forgeInstallerUrl, neoForgeVersion));
                }
            }
        }

        if (servers.isEmpty()) {
            if (!normalizedOnly.isEmpty()) {
                throw new IllegalStateException(
                        "No test servers matched --only filters: " + only);
            }
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

        CliArgs cli = CliArgs.parse(args);
        if (cli.help) {
            printUsage();
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) readObject(GROUPS_FILE).get("groups");
        Map<String, Object> config = readObject(CONFIG_FILE);
        if (!cli.only.isEmpty()) {
            System.out.println("Filtering to target(s): " + cli.only);
        } else {
            System.out.println("Testing all supported loader/version pairs.");
        }
        TreeSet<DockerMinecraftServer> servers = getServers(groups, cli.only);
        System.out.println("Found " + servers.size() + " servers to test.");

        List<String> readyMarkers = strList(config.get("readyMarkers"));
        String testCommand = str(config.get("testCommand"));
        List<String> failureMarkers = strList(config.get("failureMarkers"));
        List<String> serverSetupFailureMarkers = strList(config.get("serverSetupFailureMarkers"));
        List<String> successMarkers = strList(config.get("successMarkers"));
        String longShutdownMarker = str(config.get("longShutdownMarker"));

        List<String> failures = new ArrayList<>();
        Set<Integer> usedPorts = new HashSet<>();

        int index = 0;
        for (DockerMinecraftServer server : servers) {
            // Log the index of the server being tested
            System.out.println("Testing server " + (++index) + " of " + servers.size());
            String failure = runDockerServerTest(
                    server,
                    usedPorts,
                    readyMarkers,
                    testCommand,
                    failureMarkers,
                    serverSetupFailureMarkers,
                    successMarkers,
                    longShutdownMarker,
                    false);
            if (failure != null) {
                TestServersConsole.retry(
                        "First attempt failed for " + server + ": " + failure + " . Retrying once...");
                try {
                    server.removeDockerContainer();
                } catch (IOException e) {
                    TestServersConsole.warn(
                            "Failed to remove container before retry for " + server.containerName() + ": "
                                    + e.getMessage());
                }
                failure = runDockerServerTest(
                        server,
                        usedPorts,
                        readyMarkers,
                        testCommand,
                        failureMarkers,
                        serverSetupFailureMarkers,
                        successMarkers,
                        longShutdownMarker,
                        true);
            }
            if (failure != null) {
                failures.add(server + " (" + failure + ")");
            } else {
                TestServersConsole.success("PASSED " + server);
            }
        }

        if (!failures.isEmpty()) {
            TestServersConsole.failure("Docker test failures (" + failures.size() + "):");
            for (String failure : failures) {
                TestServersConsole.failure("  - " + failure);
            }
            System.exit(1);
        }
        TestServersConsole.success("All " + servers.size() + " docker server tests PASSED.");
    }

    /**
     * Runs one Docker test attempt. Returns a failure reason, or {@code null} on
     * success.
     */
    private static String runDockerServerTest(
            DockerMinecraftServer server,
            Set<Integer> usedPorts,
            List<String> readyMarkers,
            String testCommand,
            List<String> failureMarkers,
            List<String> serverSetupFailureMarkers,
            List<String> successMarkers,
            String longShutdownMarker,
            boolean finalAttempt)
            throws IOException {
        TestServersConsole.info("Testing " + server);
        startDockerContainer(server, usedPorts);
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
                    TestServersConsole.info("Ready marker hit: " + readyMarkers.get(i));
                }
            }
            if (allReadyMarkersSeen(readySeen) && rconSent.compareAndSet(false, true)) {
                TestServersConsole.info("All ready markers seen. Sending RCON: " + testCommand);
                Thread rconThread = new Thread(() -> {
                    try {
                        String response = RconClient.send(server.getRconPort(), testCommand);
                        if (!response.isBlank()) {
                            TestServersConsole.info("RCON response: " + response.trim());
                        }
                    } catch (IOException e) {
                        if (!RconClient.isBenignShutdownIoMessage(e.getMessage())) {
                            TestServersConsole.warn("Failed to send RCON command to " + server.getRconPort() + ": "
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
                    logAttemptIssue(finalAttempt, "Failure marker hit: " + marker);
                    RconClient.stopServer(server);
                }
            }
            for (String marker : serverSetupFailureMarkers) {
                if (containsIgnoreCase(logLine, marker)) {
                    serverSetupFailureSeen.set(true);
                    logAttemptIssue(finalAttempt, "Server setup failure marker hit: " + marker);
                    RconClient.stopServer(server);
                }
            }
            for (String marker : successMarkers) {
                if (containsIgnoreCase(logLine, marker) && stopSent.compareAndSet(false, true)) {
                    successSeen.set(true);
                    TestServersConsole.success("SUCCESS marker hit: " + marker);
                    RconClient.stopServer(server);
                }
            }
        });
        if (serverSetupFailureSeen.get()) {
            return "server setup failed";
        }
        if (failureSeen.get()) {
            return "failure marker in logs";
        }
        if (!successSeen.get()) {
            return "no success marker before container exit";
        }
        return null;
    }

    private static void logAttemptIssue(boolean finalAttempt, String message) {
        if (finalAttempt) {
            TestServersConsole.failure("FAILURE: " + message);
        } else {
            TestServersConsole.retry(message);
        }
    }

    private static void startDockerContainer(DockerMinecraftServer server, Set<Integer> usedPorts) throws IOException {
        server.assignPorts(usedPorts);
        try {
            server.createDockerContainer();
        } catch (IOException firstFailure) {
            if (!isDockerPortBindFailure(firstFailure)) {
                throw firstFailure;
            }
            TestServersConsole.retry(
                    "Docker port bind failed for " + server.containerName() + "; retrying with new ports...");
            server.releasePorts(usedPorts);
            server.assignPorts(usedPorts);
            try {
                server.createDockerContainer();
            } catch (IOException secondFailure) {
                if (!isDockerPortBindFailure(secondFailure)) {
                    throw secondFailure;
                }
                TestServersConsole.retry("Port bind failed again for " + server.containerName()
                        + "; removing stale chronos containers and retrying once more...");
                server.releasePorts(usedPorts);
                removeStaleChronosContainers();
                server.assignPorts(usedPorts);
                server.createDockerContainer();
            }
        }
    }

    private static boolean isDockerPortBindFailure(IOException e) {
        String message = e.getMessage();
        return message != null && message.contains("(exit 125)");
    }

    private static void removeStaleChronosContainers() throws IOException {
        try {
            Process list = new ProcessBuilder("docker", "ps", "-aq", "--filter", "name=chronos-")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(list.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .trim();
            if (list.waitFor() != 0 || output.isBlank()) {
                return;
            }
            for (String id : output.split("\\R")) {
                if (id.isBlank()) {
                    continue;
                }
                Process rm = new ProcessBuilder("docker", "rm", "-f", id)
                        .redirectErrorStream(true)
                        .start();
                rm.waitFor();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while removing stale chronos containers", e);
        }
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

    static String normalizeTargetKey(String target) {
        String trimmed = target.trim().toLowerCase(Locale.ROOT);
        int dash = trimmed.indexOf('-');
        if (dash <= 0 || dash >= trimmed.length() - 1) {
            return trimmed.replace('.', '_');
        }
        String loader = trimmed.substring(0, dash);
        String version = trimmed.substring(dash + 1).replace('.', '_');
        return loader + "-" + version;
    }

    private static void printUsage() {
        System.out.println("""
                Usage: TestServers [--only <target>]...

                Runs Docker integration tests for Chronos loader/version pairs.

                  --only <target>   Run only the given target (repeatable). Targets use the form
                                    loader-version with dots or underscores, e.g. fabric-1.14.4
                                    or forge-1_14_4.
                  --help            Show this help text.
                """);
    }

    private static final class CliArgs {
        final Set<String> only;
        final boolean help;

        private CliArgs(Set<String> only, boolean help) {
            this.only = only;
            this.help = help;
        }

        static CliArgs parse(String[] args) {
            Set<String> only = new HashSet<>();
            boolean help = false;
            for (int i = 0; i < args.length; i++) {
                if ("--only".equals(args[i]) && i + 1 < args.length) {
                    only.add(args[++i]);
                } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                    help = true;
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + args[i] + " (try --help)");
                }
            }
            return new CliArgs(only, help);
        }
    }
}
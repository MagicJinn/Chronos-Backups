package com.magicjinn.chronos.tooling.TestServers;

import com.magicjinn.chronos.tooling.CompileGroupLoaders;
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
    /**
     * Pause after ready markers so RCON accepts plugin commands and fresh Docker
     * worlds finish their first save before speedtest starts.
     */
    private static final long RCON_SEND_DELAY_MS = 2_000;
    private static final int RCON_SEND_MAX_ATTEMPTS = 5;
    private static final long RCON_SEND_RETRY_MS = 3_000;

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
            for (Map<String, Object> loaderObj : loaderConfigs(group)) {
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
        Map<String, Object> fabricConfig = castMap(group.get("fabricConfig"));
        if (fabricConfig == null) {
            return "fabric-api";
        }
        String fabricApi = str(fabricConfig.get("fabricApi"));
        if (fabricApi.isBlank()) {
            return "fabric-api";
        }
        String referenceMc = str(fabricConfig.get("referenceMinecraft"));
        if (!referenceMc.isBlank() && !minecraftVersion.equals(referenceMc)) {
            return "fabric-api";
        }
        return "fabric:fabric-api:" + fabricApi;
    }

    static String resolveFabricLoaderVersion(String minecraftVersion, Map<String, Object> group) {
        Map<String, Object> fabricConfig = castMap(group.get("fabricConfig"));
        if (fabricConfig == null) {
            return "";
        }
        String referenceMc = str(fabricConfig.get("referenceMinecraft"));
        if (!referenceMc.isBlank() && !referenceMc.equals(minecraftVersion)) {
            return "";
        }
        return str(fabricConfig.get("fabricLoader"));
    }

    /**
     * Resolves {@code NEOFORGE_VERSION} for NeoForge test servers.
     */
    @SuppressWarnings("unchecked")
    static String resolveNeoForgeVersionOverride(String minecraftVersion, Map<String, Object> loaderConfig) {
        Object raw = loaderConfig.get("neoForgeVersionOverrides");
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
    static String resolveNeoForgeVersion(String minecraftVersion, String dockerVersion, Map<String, Object> loaderConfig) {
        String override = resolveNeoForgeVersionOverride(minecraftVersion, loaderConfig);
        if (override != null && !override.isBlank()) {
            return override;
        }
        String referenceMc = str(loaderConfig.get("referenceMinecraft"));
        String referenceNeo = str(loaderConfig.get("neoForge"));
        if (referenceNeo.isBlank() || referenceMc.isBlank()) {
            return null;
        }
        if (minecraftVersion.equals(referenceMc) || dockerVersion.equals(referenceMc)) {
            return referenceNeo;
        }
        return null;
    }

    /**
     * Resolves {@code PAPER_CHANNEL} for Paper test servers when only experimental
     * builds exist for a patch.
     */
    @SuppressWarnings("unchecked")
    static String resolvePaperChannelOverride(String minecraftVersion, Map<String, Object> loaderConfig) {
        Object raw = loaderConfig.get("paperChannelOverrides");
        if (!(raw instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, String> overrides = (Map<String, String>) raw;
        return overrides.get(minecraftVersion);
    }

    static String resolvePaperChannel(String minecraftVersion, Map<String, Object> loaderConfig) {
        String override = resolvePaperChannelOverride(minecraftVersion, loaderConfig);
        if (override != null && !override.isBlank()) {
            return override;
        }
        return null;
    }

    /**
     * Resolves {@code FORGE_VERSION} for Forge test servers.
     */
    @SuppressWarnings("unchecked")
    static String resolveForgeVersionOverride(String minecraftVersion, Map<String, Object> loaderConfig) {
        Object raw = loaderConfig.get("forgeVersionOverrides");
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
    static String resolveForgeVersion(String minecraftVersion, String dockerVersion, Map<String, Object> loaderConfig) {
        String override = resolveForgeVersionOverride(minecraftVersion, loaderConfig);
        if (override != null && !override.isBlank()) {
            return normalizeForgeVersionForDocker(override);
        }
        String referenceMc = str(loaderConfig.get("referenceMinecraft"));
        String referenceForge = str(loaderConfig.get("forge"));
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
    static String resolveDockerMinecraftVersion(String version, String loaderKey, Map<String, Object> loaderConfig) {
        String normalized = DockerMinecraftServer.dockerMinecraftVersion(version, loaderKey);
        if ("FORGE".equalsIgnoreCase(loaderKey) && endsWithZeroPatch(version)) {
            int minor = minecraftMinorVersion(version);
            if (minor >= 13) {
                String sameLine = resolveForgeDockerVersionOnMinorLine(version, loaderConfig);
                if (sameLine != null) {
                    return sameLine;
                }
            }
        }
        return normalized;
    }

    /**
     * Forge often lacks {@code x.y.0} installers. Pick the newest supported patch
     * on
     * the same minor line (e.g. {@code 1.15.0} -> {@code 1.15.2}), not the compile
     * group's {@code referenceMinecraft} when that points at a different line.
     */
    private static String resolveForgeDockerVersionOnMinorLine(String version, Map<String, Object> loaderConfig) {
        String minorLine = minecraftMinorLinePrefix(version);
        String best = null;
        for (String candidate : strList(loaderConfig.get("supportedVersions"))) {
            if (!isSameMinorLine(candidate, minorLine)) {
                continue;
            }
            if (best == null || compareVersions(candidate, best) > 0) {
                best = candidate;
            }
        }
        if (best != null) {
            return best;
        }
        String referenceMc = str(loaderConfig.get("referenceMinecraft"));
        if (!referenceMc.isBlank() && isSameMinorLine(referenceMc, minorLine)) {
            return referenceMc;
        }
        return null;
    }

    private static String minecraftMinorLinePrefix(String version) {
        int lastDot = version.lastIndexOf('.');
        return lastDot <= 0 ? version : version.substring(0, lastDot);
    }

    private static boolean isSameMinorLine(String version, String minorLine) {
        return version.equals(minorLine) || version.startsWith(minorLine + ".");
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
    static String resolveForgeInstallerUrlOverride(String minecraftVersion, Map<String, Object> loaderConfig) {
        Object raw = loaderConfig.get("forgeInstallerUrlOverrides");
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

    private static Path findJarForConfig(Map<String, Object> loaderConfig, String loaderKey, Map<String, Object> group)
            throws IOException {
        String suffix = str(loaderConfig.get("archiveVersionTag"));
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
                    "archiveVersionTag (or group jarTargetLabel) is required for " + loaderKey + " config"
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
                for (Path _ : again) {
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

    /**
     * Returns whether {@code serverName} passes all {@code --filter} patterns.
     * Include patterns (default) match as case ignored substrings, at least one
     * include must match when any includes are present. Exclude patterns ({@code !prefix})
     * reject names containing the remainder.
     */
    static boolean matchesNameFilters(String serverName, List<String> filters) {
        if (filters.isEmpty()) {
            return true;
        }
        String name = serverName.toLowerCase(Locale.ROOT);
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        for (String filter : filters) {
            String trimmed = filter.trim();
            if (trimmed.startsWith("!")) {
                String pattern = trimmed.substring(1).trim().toLowerCase(Locale.ROOT);
                if (pattern.isEmpty()) {
                    throw new IllegalArgumentException("--filter ! requires a pattern after '!'");
                }
                excludes.add(pattern);
            } else {
                String pattern = trimmed.toLowerCase(Locale.ROOT);
                if (pattern.isEmpty()) {
                    throw new IllegalArgumentException("--filter requires a non-empty pattern");
                }
                includes.add(pattern);
            }
        }
        for (String exclude : excludes) {
            if (name.contains(exclude)) {
                return false;
            }
        }
        if (includes.isEmpty()) {
            return true;
        }
        for (String include : includes) {
            if (name.contains(include)) {
                return true;
            }
        }
        return false;
    }

    private static String serverNameForFilter(String loaderKey, String version) {
        return loaderKey.toLowerCase(Locale.ROOT) + "-" + version.replace('.', '_');
    }

    private static TreeSet<DockerMinecraftServer> getServers(
            List<Map<String, Object>> groups, Set<String> only, List<String> filters) throws IOException {
        Set<String> normalizedOnly = only.isEmpty()
                ? Set.of()
                : only.stream().map(TestServers::normalizeTargetKey).collect(java.util.stream.Collectors.toSet());
        TreeSet<DockerMinecraftServer> servers = new TreeSet<>(SERVER_ORDER);
        BukkitPluginLoaderAvailability pluginAvailability = BukkitPluginLoaderAvailability.load(ROOT);

        for (Map<String, Object> group : groups) {
            if (!shouldBuildGroup(group))
                continue;
            for (Map<String, Object> loaderConfig : loaderConfigs(group)) {
                List<String> loaderVersions = strList(loaderConfig.get("supportedVersions"));
                List<String> versions = !loaderVersions.isEmpty()
                        ? loaderVersions
                        : strList(group.get("supportedVersions"));

                for (String version : versions) {
                    Path modJar = null;
                    for (String loaderKey : CompileGroupLoaders.resolveLoaderKeys(loaderConfig)) {
                        if (!normalizedOnly.isEmpty()
                                && !normalizedOnly.contains(normalizeTargetKey(loaderKey + "-" + version))) {
                            continue;
                        }
                        if (!matchesNameFilters(serverNameForFilter(loaderKey, version), filters)) {
                            continue;
                        }
                        if (BukkitPluginLoaderAvailability.isBukkitFamilyLoader(loaderKey)
                                && !pluginAvailability.supports(loaderKey, version)) {
                            continue;
                        }
                        if (modJar == null) {
                            String jarLoaderKey = CompileGroupLoaders.resolveJarLoaderKey(loaderConfig, loaderKey);
                            modJar = findJarForConfig(loaderConfig, jarLoaderKey, group);
                        }
                        String dockerVersion = resolveDockerMinecraftVersion(version, loaderKey, loaderConfig);
                        String fabricLoader = null;
                        String modrinthProjects = null;
                        String forgeVersion = null;
                        String forgeInstallerUrl = null;
                        String neoForgeVersion = null;
                        String paperChannel = null;

                        if ("FABRIC".equalsIgnoreCase(loaderKey)) {
                            fabricLoader = resolveFabricLoaderVersion(version, group);
                            modrinthProjects = resolveFabricModrinthProjects(version, group);
                        } else if ("FORGE".equalsIgnoreCase(loaderKey)) {
                            forgeInstallerUrl = resolveForgeInstallerUrlOverride(version, loaderConfig);
                            if (forgeInstallerUrl == null) {
                                forgeVersion = resolveForgeVersion(version, dockerVersion, loaderConfig);
                            }
                        } else if ("NEOFORGE".equalsIgnoreCase(loaderKey)) {
                            neoForgeVersion = resolveNeoForgeVersion(version, dockerVersion, loaderConfig);
                        } else if ("PAPER".equalsIgnoreCase(loaderKey) || "FOLIA".equalsIgnoreCase(loaderKey)) {
                            paperChannel = resolvePaperChannel(version, loaderConfig);
                        }
                        servers.add(new DockerMinecraftServer(
                                loaderKey, version, dockerVersion, modJar, fabricLoader,
                                modrinthProjects, forgeVersion, forgeInstallerUrl, neoForgeVersion, paperChannel));
                    }
                }
            }
        }

        if (servers.isEmpty()) {
            if (!normalizedOnly.isEmpty() || !filters.isEmpty()) {
                StringBuilder message = new StringBuilder("No test servers matched");
                if (!normalizedOnly.isEmpty()) {
                    message.append(" --only ").append(only);
                }
                if (!filters.isEmpty()) {
                    message.append(" --filter ").append(filters);
                }
                throw new IllegalStateException(message.toString());
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
        } else if (cli.filters.isEmpty()) {
            System.out.println("Testing all supported loader/version pairs.");
        }
        if (!cli.filters.isEmpty()) {
            System.out.println("Applying name filter(s): " + cli.filters);
        }
        TreeSet<DockerMinecraftServer> servers = getServers(groups, cli.only, cli.filters);
        System.out.println("Found " + servers.size() + " servers to test.");

        List<String> readyMarkers = strList(config.get("readyMarkers"));
        String testCommand = str(config.get("testCommand"));
        List<String> failureMarkers = strList(config.get("failureMarkers"));
        List<String> serverSetupFailureMarkers = strList(config.get("serverSetupFailureMarkers"));
        List<String> successMarkers = strList(config.get("successMarkers"));
        List<String> worryMarkers = strList(config.get("worryMarkers"));
        String longShutdownMarker = str(config.get("longShutdownMarker"));

        List<String> failures = new ArrayList<>();
        List<String> worries = new ArrayList<>();
        Set<Integer> usedPorts = new HashSet<>();

        int index = 0;
        for (DockerMinecraftServer server : servers) {
            // Log the index of the server being tested
            System.out.println("Testing server " + (++index) + " of " + servers.size());
            List<String> serverWorries = new ArrayList<>();
            String failure = runDockerServerTest(
                    server,
                    usedPorts,
                    readyMarkers,
                    testCommand,
                    failureMarkers,
                    serverSetupFailureMarkers,
                    successMarkers,
                    worryMarkers,
                    serverWorries,
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
                serverWorries.clear();
                failure = runDockerServerTest(
                        server,
                        usedPorts,
                        readyMarkers,
                        testCommand,
                        failureMarkers,
                        serverSetupFailureMarkers,
                        successMarkers,
                        worryMarkers,
                        serverWorries,
                        longShutdownMarker,
                        true);
            }
            if (failure != null) {
                failures.add(server + " (" + failure + ")");
            } else {
                TestServersConsole.success("PASSED " + server);
            }
            for (String worryLine : serverWorries) {
                worries.add(server + ": " + worryLine);
            }
        }

        if (!failures.isEmpty()) {
            TestServersConsole.failure("Docker test failures (" + failures.size() + "):");
            for (String failure : failures) {
                TestServersConsole.failure("  - " + failure);
            }
        }
        if (!worries.isEmpty()) {
            TestServersConsole.warn("Worry markers hit (" + worries.size() + "):");
            for (String worry : worries) {
                TestServersConsole.warn("  - " + worry);
            }
        }
        if (!failures.isEmpty()) {
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
            List<String> worryMarkers,
            List<String> worryHitsOut,
            String longShutdownMarker,
            boolean finalAttempt)
            throws IOException {
        startDockerContainer(server, usedPorts);
        TestServersConsole.info("Testing " + server);
        boolean[] readySeen = new boolean[readyMarkers.size()];
        Set<String> worrySeen = new HashSet<>();
        worryHitsOut.clear();
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
                TestServersConsole.info(
                        "All ready markers seen. Sending via console (or RCON as fallback): " + testCommand);
                Thread rconThread = new Thread(() -> sendTestCommand(server, testCommand, finalAttempt),
                        "test-cmd-" + server.containerName());
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
            for (String marker : worryMarkers) {
                if (containsIgnoreCase(logLine, marker) && worrySeen.add(marker)) {
                    String line = logLine.trim();
                    TestServersConsole.warn(line);
                    worryHitsOut.add(line);
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

    private static void sendTestCommand(DockerMinecraftServer server, String testCommand, boolean finalAttempt) {
        try {
            Thread.sleep(RCON_SEND_DELAY_MS);
            for (int attempt = 1; attempt <= RCON_SEND_MAX_ATTEMPTS; attempt++) {
                try {
                    RconClient.TestCommandResult result = RconClient.sendTestCommand(server, testCommand);
                    if (attempt > 1) {
                        TestServersConsole.info(result.delivery() + " delivered on attempt " + attempt);
                    } else if (!"console".equals(result.delivery())) {
                        TestServersConsole.info("Delivered via " + result.delivery());
                    }
                    if (!result.response().isBlank()) {
                        TestServersConsole.info(result.delivery() + " response: " + result.response().trim());
                    }
                    return;
                } catch (IOException e) {
                    if (RconClient.isBenignShutdownIoMessage(e.getMessage())) {
                        return;
                    }
                    if (attempt < RCON_SEND_MAX_ATTEMPTS) {
                        logAttemptIssue(finalAttempt,
                                "Command attempt " + attempt + " failed for " + server.containerName() + ": "
                                        + e.getMessage());
                        Thread.sleep(RCON_SEND_RETRY_MS);
                    } else {
                        TestServersConsole.warn("Failed to send test command to " + server.containerName()
                                + " after " + RCON_SEND_MAX_ATTEMPTS + " attempts: " + e.getMessage());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void logAttemptIssue(boolean finalAttempt, String message) {
        if (finalAttempt) {
            TestServersConsole.failure("FAILURE: " + message);
        } else {
            TestServersConsole.retry(message);
        }
    }

    private static void startDockerContainer(DockerMinecraftServer server, Set<Integer> usedPorts) throws IOException {
        assignServerPorts(server, usedPorts);
        try {
            server.createDockerContainer();
        } catch (IOException firstFailure) {
            if (!isDockerPortBindFailure(firstFailure)) {
                throw firstFailure;
            }
            TestServersConsole.retry(
                    "Docker port bind failed for " + server.containerName() + "; retrying with new ports...");
            server.releasePorts(usedPorts);
            assignServerPorts(server, usedPorts);
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
                assignServerPorts(server, usedPorts);
                server.createDockerContainer();
            }
        }
    }

    private static void assignServerPorts(DockerMinecraftServer server, Set<Integer> usedPorts) throws IOException {
        server.assignPorts(usedPorts);
        TestServersConsole.info("Ports assigned for " + server.containerName()
                + " (game port: " + server.getGamePort() + ", RCON port: " + server.getRconPort() + ')');
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

    private static List<Map<String, Object>> loaderConfigs(Map<String, Object> group) {
        List<Map<String, Object>> configs = new ArrayList<>();
        for (Map.Entry<String, Object> entry : group.entrySet()) {
            if (!entry.getKey().toLowerCase(Locale.ROOT).contains("config")) {
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> loaderConfig = castMap(entry.getValue());
            if (CompileGroupLoaders.definesLoaderConfig(loaderConfig)) {
                configs.add(loaderConfig);
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
                Usage: TestServers [--only <target>]... [--filter <pattern>]...

                Runs Docker integration tests for Chronos loader/version pairs.

                  --only <target>     Run only the given target (repeatable). Targets use the form
                                      loader-version with dots or underscores, e.g. fabric-1.14.4
                                      or forge-1_14_4.
                  --filter <pattern>  Run only servers whose name contains the pattern (repeatable,
                                      case ignored). Prefix with ! to exclude matches, e.g.
                                      --filter folia or --filter !folia.
                  --help              Show this help text.
                """);
    }

    private static final class CliArgs {
        final Set<String> only;
        final List<String> filters;
        final boolean help;

        private CliArgs(Set<String> only, List<String> filters, boolean help) {
            this.only = only;
            this.filters = filters;
            this.help = help;
        }

        static CliArgs parse(String[] args) {
            Set<String> only = new HashSet<>();
            List<String> filters = new ArrayList<>();
            boolean help = false;
            for (int i = 0; i < args.length; i++) {
                if ("--only".equals(args[i]) && i + 1 < args.length) {
                    only.add(args[++i]);
                } else if ("--filter".equals(args[i]) && i + 1 < args.length) {
                    filters.add(args[++i]);
                } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                    help = true;
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + args[i] + " (try --help)");
                }
            }
            return new CliArgs(only, filters, help);
        }
    }
}
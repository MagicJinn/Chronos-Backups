package com.magicjinn.chronos.tooling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Dedicated-server smoke: each job runs {@code runServer} from the repo root,
 * waits for Chronos log markers, triggers backup via RCON.
 */
public final class SmokeTestServers {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path ROOT = locateRepoRoot();
    private static final Path GROUPS = ROOT.resolve("gradle/chronos-compile-groups.json");
    private static final Path SMOKE_CONFIG = ROOT.resolve("tooling/smoke-test-servers.config.json");

    private static volatile boolean reuseGradleDaemonForWorkers;

    private SmokeTestServers() {
    }

    public static void main(String[] args) throws Exception {
        Args cfg = Args.parse(args);
        reuseGradleDaemonForWorkers = cfg.reuseGradleDaemon;
        SmokeConfig smoke = readSmokeConfig();
        Map<String, Object> groupsJson = GSON.fromJson(Files.readString(GROUPS), new TypeToken<Map<String, Object>>() {
        }.getType());
        List<Map<String, Object>> groups = castList(groupsJson.get("groups"));
        List<Map<String, Object>> rows = collectRowsFromGroups(groups);

        Set<String> unifiedFabric = groups.stream()
                .filter(g -> bool(g.get("unifiedFabricJar")) && g.containsKey("fabricUnified"))
                .map(g -> str(g.get("id"))).collect(Collectors.toSet());
        Set<String> unifiedNeo = groups.stream()
                .filter(g -> bool(g.get("unifiedNeoForgeJar")) && g.containsKey("neoForgeUnified"))
                .map(g -> str(g.get("id"))).collect(Collectors.toSet());
        Set<String> unifiedForge = groups.stream()
                .filter(g -> bool(g.get("unifiedForgeJar")) && g.containsKey("forgeUnified"))
                .map(g -> str(g.get("id"))).collect(Collectors.toSet());

        List<Job> jobs = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            String gid = str(g.get("id"));
            String line = primaryLinePrefix(g).replace(".", "_");
            if (unifiedFabric.contains(gid)) {
                String name = "fabric-line-" + line;
                jobs.add(new Job(name, ROOT, List.of(":" + name + ":runServer"), smokeRunDirs(gid, name), Map.of()));
            }
            if (unifiedNeo.contains(gid)) {
                String name = "neoforge-line-" + line;
                jobs.add(new Job(name, ROOT, List.of(":" + name + ":runServer"), smokeRunDirs(gid, name), Map.of()));
            }
            if (unifiedForge.contains(gid)) {
                String name = "forge-line-" + line;
                jobs.add(new Job(name, ROOT, List.of(":" + name + ":runServer"), smokeRunDirs(gid, name), Map.of()));
            }
        }
        for (Map<String, Object> row : rows) {
            String mc = str(row.get("minecraft"));
            String cg = str(row.get("compileGroup"));
            List<String> loaders = strList(row.get("loaders"));
            if (loaders.isEmpty())
                loaders = List.of("fabric", "neoforge");
            String slug = mc.replace(".", "_");
            if (loaders.contains("fabric") && !unifiedFabric.contains(cg)) {
                String name = "fabric-" + slug;
                jobs.add(new Job(name, ROOT, List.of(":" + name + ":runServer"), smokeRunDirs(cg, name), Map.of()));
            }
            if (loaders.contains("neoforge") && !unifiedNeo.contains(cg)) {
                String name = "neoforge-" + slug;
                jobs.add(new Job(name, ROOT, List.of(":" + name + ":runServer"), smokeRunDirs(cg, name), Map.of()));
            }
            if (loaders.contains("forge") && !unifiedForge.contains(cg)) {
                String name = "forge-" + slug;
                jobs.add(new Job(name, ROOT, List.of(":" + name + ":runServer"), smokeRunDirs(cg, name), Map.of()));
            }
        }
        if (!cfg.only.isEmpty())
            jobs = jobs.stream().filter(j -> cfg.only.contains(j.label)).toList();
        if (jobs.isEmpty())
            throw new IllegalStateException("No jobs matched --only filters.");

        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path sessionDir = ROOT.resolve("build/smoke-server-logs").resolve(runId);
        Files.createDirectories(sessionDir);
        System.out.println("Session logs: " + sessionDir);
        System.out.println("Planned jobs: " + jobs.size() + ", workers: " + cfg.workers
                + (cfg.reuseGradleDaemon ? ", reuseGradleDaemon: on" : ""));
        for (Job job : jobs) {
            System.out.println(" - " + job.label + " (cwd=" + job.cwd + ")");
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.workers));
        List<Future<Result>> futures = new ArrayList<>();
        for (Job job : jobs)
            futures.add(pool.submit(new JobRunner(job, sessionDir, smoke)));

        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures)
            results.add(f.get());
        pool.shutdownNow();

        results.sort(Comparator.comparing(r -> r.label));
        List<Map<String, Object>> summary = new ArrayList<>();
        boolean failed = false;
        for (Result r : results) {
            Map<String, Object> row = new HashMap<>();
            row.put("label", r.label);
            row.put("ok", r.ok);
            row.put("log", r.logName);
            summary.add(row);
            System.out.println((r.ok ? "PASS  " : "FAIL  ") + r.label);
            failed |= !r.ok;
        }
        Files.writeString(sessionDir.resolve("summary.json"), GSON.toJson(summary) + "\n", StandardCharsets.UTF_8);
        if (failed)
            System.exit(1);
    }

    private record SmokeConfig(String[] serverReadyMarkers, String[] failureMarkers, String[] successMarkers,
            String rconCommand) {
    }

    private static final class JobRunner implements Callable<Result> {
        private final Job job;
        private final Path sessionDir;
        private final SmokeConfig smoke;

        private JobRunner(Job job, Path sessionDir, SmokeConfig smoke) {
            this.job = job;
            this.sessionDir = sessionDir;
            this.smoke = smoke;
        }

        private static boolean jobRunnerCfgReuseDaemon() {
            return SmokeTestServers.reuseGradleDaemonForWorkers;
        }

        @Override
        public Result call() throws Exception {
            return runSmoke();
        }

        private Result runSmoke() throws Exception {
            String worldName = "smoke_" + safe(job.label) + "_" + UUID.randomUUID().toString().substring(0, 8);
            int gamePort = allocatePort();
            int rconPort;
            int tries = 0;
            do {
                rconPort = allocatePort();
                if (++tries > 64)
                    throw new IllegalStateException("Could not allocate distinct game and RCON ports");
            } while (rconPort == gamePort);
            String rconPassword = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            for (Path runDir : job.runDirs) {
                Files.createDirectories(runDir);
                Files.writeString(runDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
                mergeServerProperties(runDir.resolve("server.properties"), gamePort, worldName, rconPort,
                        rconPassword);
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(gradleWrapperCommand(job.cwd));
            for (Map.Entry<String, String> e : job.gradleProjectProperties.entrySet()) {
                cmd.add("-P" + e.getKey() + "=" + e.getValue());
            }
            cmd.addAll(job.gradleArgs);
            if (!jobRunnerCfgReuseDaemon()) {
                cmd.add("--no-daemon");
            }
            cmd.add("--configure-on-demand");
            cmd.add("-Dorg.gradle.console=plain");

            for (Path runDir : job.runDirs) {
                terminateProcessesUsing(runDir, job.label);
            }

            System.out.println("[" + job.label + "] Starting smoke test (RCON " + rconPort + " / game " + gamePort
                    + ")");
            if (cfgVerbose()) {
                System.out.println("[" + job.label + "] cwd: " + job.cwd);
                System.out.println("[" + job.label + "] cmd: " + String.join(" ", cmd));
            }
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(job.cwd.toFile());
            // Root settings.gradle.kts otherwise spawns a nested generateVariants on
            // every Gradle entry. Parallel smoke runs then race Unimined/Mojang metadata
            // and unrelated logs show forge-line-1_12 "1.12.2" failures.
            pb.environment().put("CHRONOS_VARIANT_GENERATION", "skip");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(360);
            StringBuffer outBuilder = new StringBuffer();
            boolean[] readySeen = new boolean[smoke.serverReadyMarkers.length];
            Object stateLock = new Object();
            AtomicReference<Boolean> shutdownSuccess = new AtomicReference<>();
            AtomicBoolean backupSent = new AtomicBoolean(false);

            AtomicInteger lineCount = new AtomicInteger(0);
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            Thread outputReader = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lineCount.incrementAndGet();
                        outBuilder.append(line).append(System.lineSeparator());
                        if (cfgVerbose())
                            System.out.println("[" + job.label + "] " + line);
                        synchronized (stateLock) {
                            for (int i = 0; i < smoke.serverReadyMarkers.length; i++) {
                                if (!readySeen[i] && line.contains(smoke.serverReadyMarkers[i])) {
                                    readySeen[i] = true;
                                    System.out.println("[" + job.label + "] Ready marker hit: "
                                            + smoke.serverReadyMarkers[i]);
                                }
                            }
                            if (shutdownSuccess.get() == null) {
                                for (String marker : smoke.failureMarkers) {
                                    if (line.contains(marker)) {
                                        shutdownSuccess.compareAndSet(null, Boolean.FALSE);
                                        System.out.println("[" + job.label + "] Failure marker hit: " + marker);
                                        break;
                                    }
                                }
                            }
                            if (shutdownSuccess.get() == null) {
                                for (String marker : smoke.successMarkers) {
                                    if (line.contains(marker)) {
                                        shutdownSuccess.compareAndSet(null, Boolean.TRUE);
                                        System.out.println("[" + job.label + "] Success marker hit: " + marker);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    outBuilder.append("Failed to read process output: ").append(e.getMessage())
                            .append(System.lineSeparator());
                }
            }, "smoke-output-" + safe(job.label));
            outputReader.setDaemon(true);
            outputReader.start();

            boolean ok = false;
            while (System.currentTimeMillis() < deadline && p.isAlive()) {
                boolean allReady;
                Boolean shutdown;
                synchronized (stateLock) {
                    allReady = allSeen(readySeen);
                    shutdown = shutdownSuccess.get();
                }
                if (allReady && backupSent.compareAndSet(false, true)) {
                    try {
                        System.out.println("[" + job.label + "] Sending RCON: " + smoke.rconCommand);
                        String rconOut = Rcon.send("127.0.0.1", rconPort, rconPassword, smoke.rconCommand);
                        if (cfgVerbose() && rconOut != null && !rconOut.isEmpty())
                            System.out.println("[" + job.label + "] RCON response: " + rconOut.trim());
                    } catch (Exception e) {
                        System.out.println("[" + job.label + "] RCON failed: " + e.getMessage());
                        break;
                    }
                }
                if (shutdown != null) {
                    ok = backupSent.get() && Boolean.TRUE.equals(shutdown);
                    break;
                }
                Thread.sleep(50L);
            }

            synchronized (stateLock) {
                if (shutdownSuccess.get() != null)
                    ok = backupSent.get() && Boolean.TRUE.equals(shutdownSuccess.get());
            }

            if (!ok && System.currentTimeMillis() >= deadline) {
                System.out.println("[" + job.label + "] Timed out (ready markers or shutdown after RCON backup)");
            }

            if (p.isAlive()) {
                Rcon.stopBestEffort(job.label, rconPort, rconPassword);
                long gracefulEnd = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(45);
                while (p.isAlive() && System.currentTimeMillis() < gracefulEnd) {
                    Thread.sleep(150L);
                }
            }

            if (p.isAlive()) {
                System.out.println("[" + job.label + "] Stopping process");
                p.destroy();
                if (!p.waitFor(10, TimeUnit.SECONDS))
                    p.destroyForcibly();
            }
            p.waitFor(5, TimeUnit.SECONDS);
            outputReader.join(500);
            if (outputReader.isAlive()) {
                outputReader.interrupt();
            }

            String out = outBuilder.toString();

            if (!ok && !cfgVerbose()) {
                System.out.println("[" + job.label + "] Showing last 40 lines of process output:");
                printLastLines(job.label, out, 40);
            }
            if (!cfgVerbose()) {
                System.out.println("[" + job.label + "] Captured lines: " + lineCount.get());
            }
            System.out.println("[" + job.label + "] Completed with status: " + (ok ? "PASS" : "FAIL"));
            String logName = safe(job.label) + ".log";
            Files.writeString(sessionDir.resolve(logName), out, StandardCharsets.UTF_8);
            return new Result(job.label, ok, logName);
        }
    }

    private static boolean shutdownMarkerMeansSuccess(String marker) {
        String s = marker.toLowerCase();
        if (s.contains("fail"))
            return false;
        if (s.contains("complet"))
            return true;
        return false;
    }

    private record Job(String label, Path cwd, List<String> gradleArgs, List<Path> runDirs,
            Map<String, String> gradleProjectProperties) {
    }

    private record Result(String label, boolean ok, String logName) {
    }

    private static int allocatePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void mergeServerProperties(Path props, int gamePort, String worldName, int rconPort,
            String rconPassword) throws IOException {
        List<String> lines = Files.exists(props) ? Files.readAllLines(props, StandardCharsets.UTF_8)
                : new ArrayList<>();
        Map<String, String> keys = new HashMap<>();
        keys.put("server-port", String.valueOf(gamePort));
        keys.put("level-name", worldName);
        keys.put("enable-rcon", "true");
        keys.put("rcon.port", String.valueOf(rconPort));
        keys.put("rcon.password", rconPassword);
        for (Map.Entry<String, String> e : keys.entrySet()) {
            String prefix = e.getKey() + "=";
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith(prefix) || line.startsWith(e.getKey() + " =")) {
                    lines.set(i, e.getKey() + "=" + e.getValue());
                    replaced = true;
                    break;
                }
            }
            if (!replaced)
                lines.add(e.getKey() + "=" + e.getValue());
        }
        Files.write(props, lines, StandardCharsets.UTF_8);
    }

    // Typical layouts: Fabric/Loom use run/, Forge dev runs use run/server,
            // JavaExec cwd is often the variant root.
    private static List<Path> smokeRunDirs(String compileGroupId, String projectName) {
        Path variantRoot = ROOT.resolve("variants").resolve(compileGroupId).resolve(projectName);
        Path runDir = variantRoot.resolve("run");
        return List.of(runDir.resolve("server"), runDir, variantRoot);
    }

    private static String primaryLinePrefix(Map<String, Object> group) {
        List<String> pfx = strList(group.get("minecraftVersionPrefixes"));
        return pfx.stream().min(Comparator.comparingInt(String::length)).orElse("");
    }

    private static List<Map<String, Object>> collectRowsFromGroups(List<Map<String, Object>> groups) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            String groupId = str(group.get("id"));
            List<Map<String, Object>> variants = castList(group.get("variants"));
            for (Map<String, Object> variant : variants) {
                Map<String, Object> row = new HashMap<>(variant);
                row.put("compileGroup", groupId);
                rows.add(row);
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object value) {
        if (value == null)
            return new ArrayList<>();
        List<Object> raw = (List<Object>) value;
        return raw.stream().map(SmokeTestServers::str).toList();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b && b;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safe(String label) {
        return label.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String gradleWrapperCommand(Path cwd) {
        if (isWindows()) {
            return cwd.resolve("gradlew.bat").toAbsolutePath().normalize().toString();
        }
        return "./gradlew";
    }

    private static boolean allSeen(boolean[] seen) {
        for (boolean b : seen)
            if (!b)
                return false;
        return true;
    }

    private static void printLastLines(String label, String out, int maxLines) {
        if (out.isEmpty()) {
            System.out.println("[" + label + "] (no process output)");
            return;
        }
        String[] lines = out.split("\\R");
        int start = Math.max(0, lines.length - maxLines);
        for (int i = start; i < lines.length; i++) {
            System.out.println("[" + label + "] " + lines[i]);
        }
    }

    private static void terminateProcessesUsing(Path dir, String label) {
        String needle = dir.toAbsolutePath().normalize().toString().toLowerCase();
        List<ProcessHandle> matches = ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(ph -> ph.pid() != ProcessHandle.current().pid())
                .filter(ph -> ph.info().commandLine().map(cl -> cl.toLowerCase().contains(needle)).orElse(false))
                .toList();
        if (matches.isEmpty())
            return;

        System.out.println("[" + label + "] Found " + matches.size() + " stale process(es) using " + dir);
        for (ProcessHandle ph : matches) {
            boolean stopped = false;
            try {
                ph.destroy();
                stopped = ph.onExit().get(3, TimeUnit.SECONDS) != null;
            } catch (Exception ignored) {
                stopped = false;
            }
            if (!stopped) {
                try {
                    ph.destroyForcibly();
                    ph.onExit().get(3, TimeUnit.SECONDS);
                    stopped = true;
                } catch (Exception ignored) {
                    stopped = false;
                }
            }
            System.out.println("[" + label + "] stale pid " + ph.pid() + " stop result: " + (stopped ? "stopped" : "still-running"));
        }
    }

    private static SmokeConfig readSmokeConfig() throws IOException {
        if (!Files.exists(SMOKE_CONFIG)) {
            throw new IllegalStateException("Missing smoke test config: " + SMOKE_CONFIG);
        }
        Map<String, Object> config = GSON.fromJson(Files.readString(SMOKE_CONFIG),
                new TypeToken<Map<String, Object>>() {
                }.getType());
        List<String> ready = strList(config.get("serverReadyMarkers"));
        if (ready.isEmpty())
            ready = strList(config.get("expectedMarkers"));
        List<String> failureMarkers = new ArrayList<>(strList(config.get("failureMarkers")));
        List<String> successMarkers = new ArrayList<>(strList(config.get("successMarkers")));
        if (failureMarkers.isEmpty() && successMarkers.isEmpty()) {
            List<String> shutdown = strList(config.get("shutdownMarkers"));
            for (String marker : shutdown) {
                if (shutdownMarkerMeansSuccess(marker))
                    successMarkers.add(marker);
                else
                    failureMarkers.add(marker);
            }
        }
        if (ready.isEmpty())
            throw new IllegalStateException("smoke test config has no serverReadyMarkers (or legacy expectedMarkers): "
                    + SMOKE_CONFIG);
        if (failureMarkers.isEmpty() && successMarkers.isEmpty())
            throw new IllegalStateException(
                    "smoke test config has no failureMarkers/successMarkers (or legacy shutdownMarkers): "
                            + SMOKE_CONFIG);
        String cmd = str(config.get("rconCommand"));
        if (cmd.isEmpty())
            cmd = "/chronos backup";
        return new SmokeConfig(ready.toArray(String[]::new), failureMarkers.toArray(String[]::new),
                successMarkers.toArray(String[]::new), cmd);
    }

    private static final class Rcon {
        /** SERVERDATA_AUTH */
        private static final int TYPE_AUTH = 3;
        /** SERVERDATA_AUTH_RESPONSE and SERVERDATA_EXECCOMMAND */
        private static final int TYPE_COMMAND_STAGE = 2;
        /** SERVERDATA_RESPONSE_VALUE */
        private static final int TYPE_RESPONSE = 0;

        private Rcon() {
        }

        static String send(String host, int port, String password, String command) throws IOException {
            return send(host, port, password, command, 60_000);
        }

        static String send(String host, int port, String password, String command, int readTimeoutMs)
                throws IOException {
            IOException last = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    return sendOnce(host, port, password, command, readTimeoutMs);
                } catch (ConnectException e) {
                    last = e;
                    try {
                        Thread.sleep(150L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ie);
                    }
                }
                // Do not retry SocketTimeoutException: sendOnce may have already executed the command (e.g. /chronos
                // backup). Retrying would issue the command again after readTimeoutMs (default 60s).
            }
            if (last != null)
                throw last;
            throw new IOException("RCON failed after retries");
        }

        /**
         * One attempt, used so shutdown does not retry if the server is already closing
         * the socket.
         */
        static void stopBestEffort(String jobLabel, int rconPort, String rconPassword) {
            System.out.println("[" + jobLabel + "] RCON: stop (graceful server shutdown)");
            try {
                sendStopGraceful("127.0.0.1", rconPort, rconPassword);
            } catch (IOException e) {
                System.out.println("[" + jobLabel + "] RCON stop failed (will destroy process): " + e.getMessage());
            }
        }

        /**
         * Sends {@code stop} over RCON. The dedicated server often closes the socket as
         * soon as shutdown begins,
         * which makes a strict {@link #sendOnce} read loop throw EOF / reset, those
         * cases mean stop was accepted.
         */
        static void sendStopGraceful(String host, int port, String password) throws IOException {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 10_000);
                socket.setSoTimeout(8_000);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                int authId = 0x00112233;
                writePacket(out, authId, TYPE_AUTH, password);
                Packet authResp = readPacket(in);
                if (authResp.requestId == -1)
                    throw new IOException("RCON authentication failed");
                int cmdId = 0x00445566;
                writePacket(out, cmdId, TYPE_COMMAND_STAGE, "stop");
                boolean gotCommandResponse = false;
                while (true) {
                    final Packet resp;
                    try {
                        resp = readPacket(in);
                    } catch (SocketTimeoutException e) {
                        if (gotCommandResponse)
                            return;
                        throw e;
                    } catch (IOException e) {
                        if (benignRconShutdownIoMessage(e.getMessage()))
                            return;
                        throw e;
                    }
                    if (resp.requestId != cmdId || resp.type != TYPE_RESPONSE)
                        throw new IOException("Unexpected RCON packet (id=" + resp.requestId + " type=" + resp.type
                                + ")");
                    gotCommandResponse = true;
                    if (resp.payload.isEmpty())
                        break;
                }
            }
        }

        private static boolean benignRconShutdownIoMessage(String message) {
            if (message == null || message.isEmpty())
                return false;
            return message.contains("Unexpected end of stream")
                    || message.contains("Connection reset")
                    || message.contains("Broken pipe")
                    || message.contains("Connection reset by peer")
                    || message.contains("An established connection was aborted");
        }

        private static String sendOnce(String host, int port, String password, String command, int readTimeoutMs)
                throws IOException {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 10_000);
                socket.setSoTimeout(readTimeoutMs);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                int authId = 0x00112233;
                writePacket(out, authId, TYPE_AUTH, password);
                Packet authResp = readPacket(in);
                if (authResp.requestId == -1)
                    throw new IOException("RCON authentication failed");
                int cmdId = 0x00445566;
                writePacket(out, cmdId, TYPE_COMMAND_STAGE, command);
                StringBuilder acc = new StringBuilder();
                boolean gotCommandResponse = false;
                while (true) {
                    Packet resp;
                    try {
                        resp = readPacket(in);
                    } catch (SocketTimeoutException e) {
                        // Vanilla often omits the final empty TERMINATOR packet, waiting hits SoTimeout
                        // (60s). Returning
                        // here avoids Rcon.send retrying and issuing the same command twice.
                        if (gotCommandResponse)
                            return acc.toString();
                        throw e;
                    }
                    if (resp.requestId != cmdId || resp.type != TYPE_RESPONSE)
                        throw new IOException("Unexpected RCON packet (id=" + resp.requestId + " type=" + resp.type
                                + ")");
                    gotCommandResponse = true;
                    acc.append(resp.payload);
                    if (resp.payload.isEmpty())
                        break;
                }
                return acc.toString();
            }
        }

        private static void writePacket(OutputStream out, int requestId, int type, String payload) throws IOException {
            byte[] body = payload.getBytes(StandardCharsets.US_ASCII);
            int len = 4 + 4 + body.length + 2;
            ByteBuffer buf = ByteBuffer.allocate(4 + len).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(len);
            buf.putInt(requestId);
            buf.putInt(type);
            buf.put(body);
            buf.put((byte) 0);
            buf.put((byte) 0);
            out.write(buf.array());
            out.flush();
        }

        private static Packet readPacket(InputStream in) throws IOException {
            byte[] lenBuf = readFully(in, 4);
            int len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (len < 10 || len > 4096 * 1024)
                throw new IOException("Invalid RCON packet length: " + len);
            byte[] rest = readFully(in, len);
            ByteBuffer buf = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
            int requestId = buf.getInt();
            int type = buf.getInt();
            int remain = buf.remaining();
            byte[] strBytes = new byte[Math.max(0, remain - 2)];
            buf.get(strBytes);
            String payload = new String(strBytes, StandardCharsets.US_ASCII);
            return new Packet(requestId, type, payload);
        }

        private static byte[] readFully(InputStream in, int n) throws IOException {
            byte[] b = new byte[n];
            int o = 0;
            while (o < n) {
                int r = in.read(b, o, n - o);
                if (r < 0)
                    throw new IOException("Unexpected end of stream reading RCON packet");
                o += r;
            }
            return b;
        }

        private record Packet(int requestId, int type, String payload) {
        }
    }

    private static final class Args {
        final int workers;
        final Set<String> only;
        final boolean reuseGradleDaemon;

        private Args(int workers, Set<String> only, boolean reuseGradleDaemon) {
            this.workers = workers;
            this.only = only;
            this.reuseGradleDaemon = reuseGradleDaemon;
        }

        static Args parse(String[] args) {
            int workers = 4;
            Set<String> only = new java.util.HashSet<>();
            boolean verbose = false;
            boolean reuseGradleDaemon = false;
            for (int i = 0; i < args.length; i++) {
                if ("--workers".equals(args[i]) && i + 1 < args.length)
                    workers = Integer.parseInt(args[++i]);
                else if ("--only".equals(args[i]) && i + 1 < args.length)
                    only.add(args[++i]);
                else if ("--verbose".equals(args[i]))
                    verbose = true;
                else if ("--reuse-gradle-daemon".equals(args[i]))
                    reuseGradleDaemon = true;
            }
            VERBOSE = verbose;
            return new Args(workers, only, reuseGradleDaemon);
        }
    }

    private static volatile boolean VERBOSE = false;

    private static boolean cfgVerbose() {
        return VERBOSE;
    }

    private static Path locateRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path cursor = cwd;
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path groups = cursor.resolve("gradle/chronos-compile-groups.json");
            if (Files.exists(groups))
                return cursor;
            cursor = cursor.getParent();
        }
        return cwd;
    }
}

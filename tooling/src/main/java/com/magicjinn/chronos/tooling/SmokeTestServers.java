package com.magicjinn.chronos.tooling;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.lang.reflect.Type;
import java.net.ServerSocket;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class SmokeTestServers {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_OF_MAP = new TypeToken<List<Map<String, Object>>>() {
    }.getType();

    private static final Path ROOT = locateRepoRoot();
    private static final Path VERSIONS = ROOT.resolve("gradle/chronos-versions.json");
    private static final Path GROUPS = ROOT.resolve("gradle/chronos-compile-groups.json");
    private static final Path SMOKE_CONFIG = ROOT.resolve("tooling/smoke-test-servers.config.json");

    private SmokeTestServers() {
    }

    public static void main(String[] args) throws Exception {
        Args cfg = Args.parse(args);
        String[] markers = readMarkers();
        List<Map<String, Object>> rows = GSON.fromJson(Files.readString(VERSIONS), LIST_OF_MAP);
        Map<String, Object> groupsJson = GSON.fromJson(Files.readString(GROUPS), new TypeToken<Map<String, Object>>() {
        }.getType());
        List<Map<String, Object>> groups = castList(groupsJson.get("groups"));

        Set<String> unifiedFabric = groups.stream()
                .filter(g -> bool(g.get("unifiedFabricJar")) && g.containsKey("fabricUnified"))
                .map(g -> str(g.get("id"))).collect(Collectors.toSet());
        Set<String> unifiedNeo = groups.stream()
                .filter(g -> bool(g.get("unifiedNeoForgeJar")) && g.containsKey("neoForgeUnified"))
                .map(g -> str(g.get("id"))).collect(Collectors.toSet());

        List<Job> jobs = new ArrayList<>();
        jobs.add(new Job("forge-line-1_12", ROOT.resolve("forge"), List.of("runServer"),
                List.of(ROOT.resolve("forge/run"), ROOT.resolve("forge/run/server"))));
        for (Map<String, Object> g : groups) {
            String gid = str(g.get("id"));
            String line = primaryLinePrefix(g).replace(".", "_");
            if (unifiedFabric.contains(gid)) {
                String name = "fabric-line-" + line;
                jobs.add(new Job(name, ROOT, List.of(":" + name + ":runServer"),
                        List.of(ROOT.resolve("variants").resolve(gid).resolve(name).resolve("run"))));
            }
            if (unifiedNeo.contains(gid)) {
                String name = "neoforge-line-" + line;
                jobs.add(new Job(name, ROOT, List.of(":" + name + ":runServer"),
                        List.of(ROOT.resolve("variants").resolve(gid).resolve(name).resolve("run"))));
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
                jobs.add(new Job(name, ROOT, List.of(":" + name + ":runServer"),
                        List.of(ROOT.resolve("variants").resolve(cg).resolve(name).resolve("run"))));
            }
            if (loaders.contains("neoforge") && !unifiedNeo.contains(cg)) {
                String name = "neoforge-" + slug;
                jobs.add(new Job(name, ROOT, List.of(":" + name + ":runServer"),
                        List.of(ROOT.resolve("variants").resolve(cg).resolve(name).resolve("run"))));
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
        System.out.println("Planned jobs: " + jobs.size() + ", workers: " + cfg.workers);
        for (Job job : jobs) {
            System.out.println(" - " + job.label + " (cwd=" + job.cwd + ")");
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.workers));
        List<Future<Result>> futures = new ArrayList<>();
        for (Job job : jobs)
            futures.add(pool.submit(new JobRunner(job, sessionDir, markers)));

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

    private static final class JobRunner implements Callable<Result> {
        private final Job job;
        private final Path sessionDir;
        private final String[] markers;

        private JobRunner(Job job, Path sessionDir, String[] markers) {
            this.job = job;
            this.sessionDir = sessionDir;
            this.markers = markers;
        }

        @Override
        public Result call() throws Exception {
            String worldName = "smoke_" + safe(job.label) + "_" + UUID.randomUUID().toString().substring(0, 8);
            for (Path runDir : job.runDirs) {
                Files.createDirectories(runDir);
                Files.writeString(runDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
                mergeServerProperties(runDir.resolve("server.properties"), pickFreePort(), worldName);
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(gradleWrapperCommand(job.cwd));
            cmd.addAll(job.gradleArgs);
            String jdk21 = resolveJdk21Home();
            if (job.label.startsWith("forge-line-1_12") && jdk21 != null) {
                cmd.add("-Dorg.gradle.java.home=" + jdk21);
            }
            cmd.add("--no-daemon");
            cmd.add("-Dorg.gradle.console=plain");

            for (Path runDir : job.runDirs) {
                terminateProcessesUsing(runDir, job.label);
            }

            System.out.println("[" + job.label + "] Starting smoke test");
            if (cfgVerbose()) {
                System.out.println("[" + job.label + "] cwd: " + job.cwd);
                System.out.println("[" + job.label + "] cmd: " + String.join(" ", cmd));
            }
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(job.cwd.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(360);
            StringBuffer outBuilder = new StringBuffer();
            boolean[] seen = new boolean[markers.length];
            Object seenLock = new Object();
            boolean ok = false;

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
                        synchronized (seenLock) {
                            for (int i = 0; i < markers.length; i++) {
                                if (!seen[i] && line.contains(markers[i])) {
                                    seen[i] = true;
                                    System.out.println("[" + job.label + "] Marker hit: " + markers[i]);
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

            while (System.currentTimeMillis() < deadline) {
                synchronized (seenLock) {
                    if (allSeen(seen))
                        ok = true;
                }
                if (ok)
                    break;
                if (!p.isAlive())
                    break;
                Thread.sleep(50L);
            }

            if (!ok && System.currentTimeMillis() >= deadline) {
                System.out.println("[" + job.label + "] Timed out waiting for markers");
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
            synchronized (seenLock) {
                if (!ok && allSeen(seen))
                    ok = true;
            }

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

    private record Job(String label, Path cwd, List<String> gradleArgs, List<Path> runDirs) {
    }

    private record Result(String label, boolean ok, String logName) {
    }

    private static int pickFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void mergeServerProperties(Path props, int port, String worldName) throws IOException {
        List<String> lines = Files.exists(props) ? Files.readAllLines(props, StandardCharsets.UTF_8)
                : new ArrayList<>();
        boolean replacedPort = false;
        boolean replacedWorld = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("server-port=")) {
                lines.set(i, "server-port=" + port);
                replacedPort = true;
            } else if (lines.get(i).startsWith("level-name=")) {
                lines.set(i, "level-name=" + worldName);
                replacedWorld = true;
            }
        }
        if (!replacedPort)
            lines.add("server-port=" + port);
        if (!replacedWorld)
            lines.add("level-name=" + worldName);
        Files.write(props, lines, StandardCharsets.UTF_8);
    }

    private static String primaryLinePrefix(Map<String, Object> group) {
        List<String> pfx = strList(group.get("minecraftVersionPrefixes"));
        return pfx.stream().min(Comparator.comparingInt(String::length)).orElse("");
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

    private static String[] readMarkers() throws IOException {
        if (!Files.exists(SMOKE_CONFIG)) {
            throw new IllegalStateException("Missing smoke test config: " + SMOKE_CONFIG);
        }
        Map<String, Object> config = GSON.fromJson(Files.readString(SMOKE_CONFIG),
                new TypeToken<Map<String, Object>>() {
                }.getType());
        List<String> markers = strList(config.get("expectedMarkers"));
        if (markers.isEmpty()) {
            throw new IllegalStateException("smoke test config has no expectedMarkers: " + SMOKE_CONFIG);
        }
        return markers.toArray(String[]::new);
    }

    private static final class Args {
        final int workers;
        final Set<String> only;

        private Args(int workers, Set<String> only) {
            this.workers = workers;
            this.only = only;
        }

        static Args parse(String[] args) {
            int workers = 4;
            Set<String> only = new java.util.HashSet<>();
            boolean verbose = false;
            for (int i = 0; i < args.length; i++) {
                if ("--workers".equals(args[i]) && i + 1 < args.length)
                    workers = Integer.parseInt(args[++i]);
                if ("--only".equals(args[i]) && i + 1 < args.length)
                    only.add(args[++i]);
                if ("--verbose".equals(args[i]))
                    verbose = true;
            }
            VERBOSE = verbose;
            return new Args(workers, only);
        }
    }

    private static volatile boolean VERBOSE = false;

    private static boolean cfgVerbose() {
        return VERBOSE;
    }

    private static String resolveJdk21Home() {
        String configured = System.getenv("JDK21_HOME");
        if (configured != null && !configured.isBlank()) {
            Path configuredPath = Path.of(configured);
            if (Files.exists(configuredPath.resolve("bin").resolve(isWindows() ? "java.exe" : "java")))
                return configuredPath.toAbsolutePath().normalize().toString();
        }

        Path currentHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        Path parent = currentHome.getParent();
        if (parent == null)
            return null;

        Path sibling = parent.resolve("21");
        if (Files.exists(sibling.resolve("bin").resolve(isWindows() ? "java.exe" : "java")))
            return sibling.toAbsolutePath().normalize().toString();
        return null;
    }

    private static Path locateRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path cursor = cwd;
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path versions = cursor.resolve("gradle/chronos-versions.json");
            Path groups = cursor.resolve("gradle/chronos-compile-groups.json");
            if (Files.exists(versions) && Files.exists(groups))
                return cursor;
            cursor = cursor.getParent();
        }
        return cwd;
    }
}

package com.magicjinn.chronos.tooling;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GenerateVariants {
    private static final Gson GSON = new Gson();

    private static final Path ROOT = locateRepoRoot();
    private static final Path VERSIONS_FILE = ROOT.resolve("gradle/chronos-versions.json");
    private static final Path GROUPS_FILE = ROOT.resolve("gradle/chronos-compile-groups.json");
    private static final Path TEMPLATES_DIR = ROOT.resolve("tooling/templates/generate-variants");
    private static final Map<String, String> TEMPLATE_FILES = Map.ofEntries(
            Map.entry("fabricBuildGradle", "fabric-build.gradle.template"),
            Map.entry("fabricModJson", "fabric-mod.json.template"),
            Map.entry("neoBuildGradleKts", "neo-build.gradle.kts.template"),
            Map.entry("neoModsToml", "neo-neoforge.mods.toml.template"),
            Map.entry("forgeBuildGradle111", "forge-build-1.11.gradle.template"),
            Map.entry("forgeBuildGradle112", "forge-build-1.12.gradle.template"),
            Map.entry("forgeBuildGradle113", "forge-build-1.13.gradle.template"),
            Map.entry("forgeBuildGradle120", "forge-build-1.20.gradle.template"),
            Map.entry("forgeMcmodInfo", "forge-mcmod.info.template"),
            Map.entry("forgeModsToml113", "forge-mods-1.13.toml.template"),
            Map.entry("forgeModsToml120", "forge-mods-1.20.toml.template"));
    private static final Path VARIANTS_ROOT = ROOT.resolve("variants");
    private static final Map<String, String> TEMPLATES = readTemplates();

    private GenerateVariants() {
    }

    public static void main(String[] args) throws Exception {
        if (!Files.exists(VERSIONS_FILE) || !Files.exists(GROUPS_FILE)) {
            throw new IllegalStateException("Missing matrix files under gradle/.");
        }

        List<Map<String, Object>> rows = readRows(VERSIONS_FILE);
        Map<String, Object> groupsJson = readObject(GROUPS_FILE);
        List<Map<String, Object>> groups = castList(groupsJson.get("groups"));

        Map<String, String> rootProps = readRootProps();
        String loomVersion = rootProps.getOrDefault("loom.version", "1.10.1");
        String neoforgeModdev = rootProps.getOrDefault("neoforge.moddev.plugin.version", "2.0.141");

        Map<String, Map<String, Object>> groupsById = new HashMap<>();
        for (Map<String, Object> g : groups)
            groupsById.put(str(g.get("id")), g);

        Set<Path> validPaths = new HashSet<>();
        int generated = 0;

        for (Map<String, Object> row : rows) {
            String mc = str(row.get("minecraft"));
            String compileGroup = str(row.get("compileGroup"));
            List<String> loaders = strList(row.get("loaders"));
            if (loaders.isEmpty())
                loaders = List.of("fabric", "neoforge");

            Map<String, Object> gdef = groupsById.get(compileGroup);
            if (gdef == null)
                throw new IllegalStateException("Unknown compileGroup: " + compileGroup);

            boolean unifiedFabric = bool(gdef.get("unifiedFabricJar")) && gdef.containsKey("fabricUnified");
            boolean unifiedNeo = bool(gdef.get("unifiedNeoForgeJar")) && gdef.containsKey("neoForgeUnified");
            boolean unifiedForge = bool(gdef.get("unifiedForgeJar")) && gdef.containsKey("forgeUnified");

            String slug = mc.replace(".", "_");
            Path groupDir = VARIANTS_ROOT.resolve(compileGroup);
            Path fabricDir = groupDir.resolve("fabric-" + slug);
            Path neoDir = groupDir.resolve("neoforge-" + slug);
            Path forgeDir = groupDir.resolve("forge-" + slug);

            if (!loaders.contains("fabric"))
                deleteTree(fabricDir);
            if (!loaders.contains("neoforge") || unifiedNeo)
                deleteTree(neoDir);
            if (!loaders.contains("forge") || unifiedForge)
                deleteTree(forgeDir);

            if (loaders.contains("fabric") && !unifiedFabric) {
                writeFabricProject(fabricDir, compileGroup, mc, str(row.get("fabricLoader")), str(row.get("fabricApi")),
                        loomVersion, null, null);
                validPaths.add(fabricDir);
                generated++;
            }

            if (loaders.contains("neoforge") && !unifiedNeo) {
                writeNeoProject(neoDir, compileGroup, mc, str(row.get("neoForge")), neoforgeModdev, false, null, null);
                validPaths.add(neoDir);
                generated++;
            }
            if (loaders.contains("forge") && !unifiedForge) {
                writeForgeProject(forgeDir, compileGroup, mc, str(row.get("forge")), null,
                        forgeMcpFromGroup(gdef));
                validPaths.add(forgeDir);
                generated++;
            }
        }

        for (Map<String, Object> g : groups) {
            String gid = str(g.get("id"));
            if (bool(g.get("unifiedFabricJar")) && g.containsKey("fabricUnified")) {
                Map<String, Object> fu = castMap(g.get("fabricUnified"));
                String line = primaryLinePrefix(g);
                String projectName = "fabric-line-" + line.replace(".", "_");
                Path dir = VARIANTS_ROOT.resolve(gid).resolve(projectName);
                String fabricMcDepOverride = str(fu.get("minecraftDep"));
                writeFabricProject(dir, gid, str(fu.get("referenceMinecraft")), str(fu.get("fabricLoader")),
                        str(fu.get("fabricApi")), loomVersion,
                        fabricMcDepOverride.isBlank() ? null : fabricMcDepOverride, fu);
                validPaths.add(dir);
                generated++;
            }
            if (bool(g.get("unifiedNeoForgeJar")) && g.containsKey("neoForgeUnified")) {
                Map<String, Object> nu = castMap(g.get("neoForgeUnified"));
                String line = primaryLinePrefix(g);
                String projectName = "neoforge-line-" + line.replace(".", "_");
                Path dir = VARIANTS_ROOT.resolve(gid).resolve(projectName);
                String neoMcRange = nu.get("minecraftRange") != null ? str(nu.get("minecraftRange")) : null;
                if (neoMcRange != null && neoMcRange.isBlank())
                    neoMcRange = null;
                writeNeoProject(dir, gid, str(nu.get("referenceMinecraft")), str(nu.get("neoForge")), neoforgeModdev,
                        true, neoMcRange, nu);
                validPaths.add(dir);
                generated++;
            }
            if (bool(g.get("unifiedForgeJar")) && g.containsKey("forgeUnified")) {
                Map<String, Object> fu = castMap(g.get("forgeUnified"));
                String line = primaryLinePrefix(g);
                String projectName = "forge-line-" + line.replace(".", "_");
                Path dir = VARIANTS_ROOT.resolve(gid).resolve(projectName);
                writeForgeProject(dir, gid, str(fu.get("referenceMinecraft")), str(fu.get("forge")), fu,
                        forgeMcpFromGroup(g));
                validPaths.add(dir);
                generated++;
            }
        }

        pruneStaleVariantDirs(validPaths);
        System.out.println("Generated " + generated + " variant projects under variants/");
    }

    private static void writeFabricProject(Path dir, String compileGroup, String mc, String fabricLoader,
            String fabricApi, String loomVersion, String minecraftDepOverride,
            Map<String, Object> unifiedArchiveSource) throws IOException {
        Files.createDirectories(dir);
        write(dir.resolve("gradle.properties"), """
                # Generated by tooling Java generator — do not hand-edit.
                compileGroup=%s
                minecraftVersion=%s
                fabricLoaderVersion=%s
                fabricApiVersion=%s
                neoForgeVersion=
                """.formatted(compileGroup, mc, fabricLoader, fabricApi));

        String archivesName = "chronos-backup-fabric-" + archiveSuffix(unifiedArchiveSource, mc);
        String pluginId = mc.startsWith("26.") ? "net.fabricmc.fabric-loom" : "net.fabricmc.fabric-loom-remap";
        String depConf = mc.startsWith("26.") ? "implementation" : "modImplementation";
        String mappings = mc.startsWith("26.") ? "" : "mappings loom.officialMojangMappings()";
        String javaDep = mc.startsWith("26.") ? ">=25" : ">=21";
        String mcDep = minecraftDepOverride != null && !minecraftDepOverride.isBlank()
                ? minecraftDepOverride
                : fabricMcDep(mc);
        String remapJar = mc.startsWith("26.") ? "" : "\n\ntasks.named('remapJar') {\n    archiveClassifier = ''\n}\n";

        Map<String, String> values = new HashMap<>();
        values.put("pluginId", pluginId);
        values.put("loomVersion", loomVersion);
        values.put("archivesName", archivesName);
        values.put("minecraft", mc);
        values.put("mappings", mappings);
        values.put("depConf", depConf);
        values.put("fabricLoader", fabricLoader);
        values.put("fabricApi", fabricApi);
        values.put("minecraftDep", mcDep);
        values.put("javaDep", javaDep);
        values.put("javaMajor", mc.startsWith("26.") ? "25" : "21");
        values.put("remapJarBlock", remapJar);
        putMojmapSourceDirLines(values, mc);
        write(dir.resolve("build.gradle"), renderTemplate("fabricBuildGradle", values));

        Path resources = dir.resolve("src/main/resources");
        Files.createDirectories(resources);
        write(resources.resolve("fabric.mod.json"), renderTemplate("fabricModJson", Map.of()));
    }

    private static void writeNeoProject(Path dir, String compileGroup, String mc, String neoVersion,
            String moddevVersion, boolean lineRange, String minecraftRangeOverride,
            Map<String, Object> unifiedArchiveSource) throws IOException {
        Files.createDirectories(dir);
        write(dir.resolve("gradle.properties"), """
                # Generated by tooling Java generator — do not hand-edit.
                compileGroup=%s
                minecraftVersion=%s
                neoForgeVersion=%s
                """.formatted(compileGroup, mc, neoVersion));

        String archivesName = "chronos-backup-neoforge-" + archiveSuffix(unifiedArchiveSource, mc);
        String mcRange = minecraftRangeOverride != null && !minecraftRangeOverride.isBlank()
                ? minecraftRangeOverride
                : (lineRange ? lineRange(mc) : neoRange(mc));
        String javaMajor = mc.startsWith("26.") ? "25" : "21";
        Map<String, String> neoValues = new HashMap<>();
        neoValues.put("moddevVersion", moddevVersion);
        neoValues.put("archivesName", archivesName);
        neoValues.put("neoVersion", neoVersion);
        neoValues.put("minecraft", mc);
        neoValues.put("minecraftRange", mcRange);
        neoValues.put("javaMajor", javaMajor);
        neoValues.put("neoForgeLineDir", minecraftLineFolder(mc));
        putMojmapSourceDirLines(neoValues, mc);
        write(dir.resolve("build.gradle.kts"), renderTemplate("neoBuildGradleKts", neoValues));

        Path meta = dir.resolve("src/main/resources/META-INF");
        Files.createDirectories(meta);
        write(meta.resolve("neoforge.mods.toml"), renderTemplate("neoModsToml", Map.of()));
    }

    private static void writeForgeProject(Path dir, String compileGroup, String mc, String forgeVersion,
            Map<String, Object> unifiedArchiveSource, Map<String, String> groupForgeMcp) throws IOException {
        Files.createDirectories(dir);
        write(dir.resolve("gradle.properties"), """
                # Generated by tooling Java generator — do not hand-edit.
                compileGroup=%s
                minecraftVersion=%s
                forgeVersion=%s
                """.formatted(compileGroup, mc, forgeVersion));

        String archivesName = "chronos-backup-forge-" + archiveSuffix(unifiedArchiveSource, mc);
        boolean forge111 = mc.startsWith("1.11");
        boolean forge112 = mc.startsWith("1.12");
        boolean forge113 = mc.startsWith("1.13");
        boolean forge120 = mc.startsWith("1.20");
        final String gradleTemplate;
        if (forge111) {
            gradleTemplate = "forgeBuildGradle111";
        } else if (forge112) {
            gradleTemplate = "forgeBuildGradle112";
        } else if (forge113) {
            gradleTemplate = "forgeBuildGradle113";
        } else if (forge120) {
            gradleTemplate = "forgeBuildGradle120";
        } else {
            throw new IllegalStateException(
                    "No Forge build.gradle template for Minecraft " + mc + " (expected 1.11 / 1.12 / 1.13 / 1.20 line).");
        }
        Map<String, String> forgeGradleValues = new HashMap<>();
        forgeGradleValues.put("archivesName", archivesName);
        forgeGradleValues.put("minecraft", mc);
        forgeGradleValues.put("forgeVersion", forgeVersion);
        if (forge120) {
            putMojmapSourceDirLines(forgeGradleValues, mc);
        }
        if (!forge120) {
            putForgeMcpMappings(forgeGradleValues, unifiedArchiveSource, groupForgeMcp, compileGroup);
        }
        write(dir.resolve("build.gradle"), renderTemplate(gradleTemplate, forgeGradleValues));

        Path resources = dir.resolve("src/main/resources");
        Files.createDirectories(resources);
        if (forge113) {
            Path meta = resources.resolve("META-INF");
            Files.createDirectories(meta);
            write(meta.resolve("mods.toml"), renderTemplate("forgeModsToml113", Map.of()));
        } else if (forge120) {
            Path meta = resources.resolve("META-INF");
            Files.createDirectories(meta);
            write(meta.resolve("mods.toml"), renderTemplate("forgeModsToml120", Map.of()));
        } else {
            write(resources.resolve("mcmod.info"), renderTemplate("forgeMcmodInfo", Map.of(
                    "minecraft", mc)));
        }
    }

    private static String fabricMcDep(String mc) {
        String[] p = mc.split("\\.");
        if (p.length == 2)
            return ">=" + mc + " <" + p[0] + "." + (Integer.parseInt(p[1]) + 1);
        if (p.length >= 3)
            return "~" + mc;
        return ">=" + mc;
    }

    private static String neoRange(String mc) {
        String[] p = mc.split("\\.");
        if (p.length == 2)
            return "[" + mc + "," + p[0] + "." + (Integer.parseInt(p[1]) + 1) + ")";
        if (p.length >= 3)
            return "[" + mc + "," + p[0] + "." + p[1] + ".999]";
        return "[" + mc + "," + mc + "]";
    }

    private static String lineRange(String linePrefix) {
        String[] p = linePrefix.split("\\.");
        if (p.length == 2)
            return "[" + linePrefix + "," + p[0] + "." + (Integer.parseInt(p[1]) + 1) + ")";
        return "[" + linePrefix + ",)";
    }

    /**
     * MCP coordinates for Unimined {@code searge() + mcp} mappings on legacy Forge (1.12 / 1.13).
     * Optional {@code mcpChannel} / {@code mcpVersion} on {@code forgeUnified} override;
     * otherwise values come from the compile group's {@code forgeMcp} in {@code chronos-compile-groups.json}.
     */
    private static void putForgeMcpMappings(Map<String, String> out, Map<String, Object> unifiedArchiveSource,
            Map<String, String> groupForgeMcp, String compileGroup) {
        String channel = "";
        String artifact = "";
        if (unifiedArchiveSource != null) {
            channel = str(unifiedArchiveSource.get("mcpChannel"));
            artifact = str(unifiedArchiveSource.get("mcpVersion"));
        }
        if (artifact.isBlank()) {
            channel = str(groupForgeMcp.get("mcpChannel"));
            artifact = str(groupForgeMcp.get("mcpVersion"));
        }
        if (channel.isBlank()) {
            channel = str(groupForgeMcp.get("mcpChannel"));
        }
        if (artifact.isBlank()) {
            throw new IllegalStateException(
                    "compile group \"" + compileGroup + "\" needs forgeMcp (or forgeUnified mcpVersion) for legacy Forge");
        }
        out.put("mcpChannel", channel);
        out.put("mcpVersion", artifact);
    }

    private static Map<String, String> forgeMcpFromGroup(Map<String, Object> group) {
        Object raw = group.get("forgeMcp");
        if (raw == null)
            return Map.of();
        Map<String, Object> m = castMap(raw);
        Map<String, String> out = new HashMap<>();
        out.put("mcpChannel", str(m.get("mcpChannel")));
        out.put("mcpVersion", str(m.get("mcpVersion")));
        return out;
    }

    private static String minecraftLineTag(String version) {
        String[] p = version.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] + ".x" : version + ".x";
    }

    /**
     * Suffix for {@code base.archivesName} (after
     * {@code chronos-backup-<loader>-}). When {@code unifiedArchiveSource}
     * is non-null and defines {@code archiveVersionTag}, that string is used so
     * unified jars can reflect real supported
     * Minecraft ranges instead of the reference version's
     * {@link #minecraftLineTag}.
     */
    private static String archiveSuffix(Map<String, Object> unifiedArchiveSource, String referenceMc) {
        if (unifiedArchiveSource != null) {
            String override = str(unifiedArchiveSource.get("archiveVersionTag"));
            if (!override.isBlank())
                return override;
        }
        return minecraftLineTag(referenceMc);
    }

    /**
     * Directory name under {@code shell-neoforge/} for line-specific NeoForge sources (e.g. {@code 1.20},
     * {@code 1.21}, {@code 26.1}) — not a separate Gradle module.
     */
    private static String minecraftLineFolder(String minecraftVersion) {
        String[] p = minecraftVersion.split("\\.");
        if (p.length >= 2)
            return p[0] + "." + p[1];
        return minecraftVersion;
    }

    /**
     * Fills Gradle/Kotlin template placeholders for Mojmap command gate sources: adds
     * {@code shell-mojmap/v1_14} for numeric {@code CommandSourceStack#hasPermission(int)}, or
     * {@code shell-mojmap/v1_21_11} for the {@code PermissionSet}-based implementation (mutually
     * exclusive).
     */
    private static void putMojmapSourceDirLines(Map<String, String> values, String mc) {
        if ("v1_21_11".equals(shellMojmapGateDir(mc))) {
            values.put("mojmapNumericGateGroovy", "");
            values.put("mojmapMojmapSliceGroovy", "    rootProject.file('shell-mojmap/v1_21_11/src/main/java'),");
            values.put("mojmapNumericGateKts", "");
            values.put("mojmapMojmapSliceKts", "        rootProject.file(\"shell-mojmap/v1_21_11/src/main/java\"),");
        } else {
            values.put("mojmapNumericGateGroovy", "    rootProject.file('shell-mojmap/v1_14/src/main/java'),");
            values.put("mojmapMojmapSliceGroovy", "");
            values.put("mojmapNumericGateKts", "        rootProject.file(\"shell-mojmap/v1_14/src/main/java\"),");
            values.put("mojmapMojmapSliceKts", "");
        }
    }

    /**
     * Which {@code shell-mojmap} variant applies for permission APIs: {@code v1_21_11} uses the
     * {@code PermissionSet} / {@code LevelBasedPermissionSet} gate under {@code shell-mojmap/v1_21_11};
     * all other returned values use the numeric gate in {@code shell-mojmap/v1_14} (see
     * {@link #putMojmapSourceDirLines}).
     */
    private static String shellMojmapGateDir(String mc) {
        String[] p = mc.split("\\.");
        try {
            if (p.length >= 1 && !"1".equals(p[0])) {
                return "v1_21_11";
            }
            if (p.length >= 2 && "1".equals(p[0])) {
                int minor = Integer.parseInt(p[1]);
                if (minor <= 20) {
                    return "v1_14";
                }
                if (minor == 21) {
                    int patch = p.length >= 3 ? Integer.parseInt(p[2]) : 0;
                    return patch >= 11 ? "v1_21_11" : "v1_14";
                }
                return "v1_21_11";
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return "v1_21_11";
    }

    private static String primaryLinePrefix(Map<String, Object> group) {
        List<String> pfx = strList(group.get("minecraftVersionPrefixes"));
        if (pfx.isEmpty())
            return "";
        return pfx.stream().min(Comparator.comparingInt(String::length)).orElse("");
    }

    private static void pruneStaleVariantDirs(Set<Path> validPaths) throws IOException {
        if (!Files.isDirectory(VARIANTS_ROOT))
            return;
        try (var groups = Files.list(VARIANTS_ROOT)) {
            for (Path groupDir : (Iterable<Path>) groups::iterator) {
                if (!Files.isDirectory(groupDir))
                    continue;
                try (var dirs = Files.list(groupDir)) {
                    for (Path child : (Iterable<Path>) dirs::iterator) {
                        String name = child.getFileName().toString();
                        if ((name.startsWith("fabric-") || name.startsWith("neoforge-") || name.startsWith("forge-"))
                                && !validPaths.contains(child)) {
                            deleteTree(child);
                        }
                    }
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root))
            return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content.stripLeading(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Map<String, String> readRootProps() throws IOException {
        Map<String, String> out = new HashMap<>();
        for (String line : Files.readAllLines(ROOT.resolve("gradle.properties"))) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("="))
                continue;
            String[] pair = trimmed.split("=", 2);
            out.put(pair[0].trim(), pair[1].trim());
        }
        return out;
    }

    private static List<Map<String, Object>> readRows(Path file) throws IOException {
        Type type = new TypeToken<List<Map<String, Object>>>() {
        }.getType();
        return GSON.fromJson(Files.readString(file), type);
    }

    private static Map<String, Object> readObject(Path file) throws IOException {
        Type type = new TypeToken<Map<String, Object>>() {
        }.getType();
        return GSON.fromJson(Files.readString(file), type);
    }

    private static Map<String, String> readTemplates() {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : TEMPLATE_FILES.entrySet()) {
            Path path = TEMPLATES_DIR.resolve(e.getValue());
            try {
                if (!Files.isRegularFile(path))
                    throw new IllegalStateException("Missing template file: " + path);
                out.put(e.getKey(), Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read template: " + path, ex);
            }
        }
        return out;
    }

    private static String renderTemplate(String key, Map<String, String> values) {
        String template = TEMPLATES.get(key);
        if (template == null || template.isEmpty()) {
            throw new IllegalStateException("Template not found: " + key);
        }
        String out = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
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
        return raw.stream().map(GenerateVariants::str).toList();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b && b;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
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

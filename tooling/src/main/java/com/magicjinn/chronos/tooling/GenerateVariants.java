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
    private static final Path VARIANTS_ROOT = ROOT.resolve("variants");

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

            String slug = mc.replace(".", "_");
            Path groupDir = VARIANTS_ROOT.resolve(compileGroup);
            Path fabricDir = groupDir.resolve("fabric-" + slug);
            Path neoDir = groupDir.resolve("neoforge-" + slug);

            if (!loaders.contains("fabric"))
                deleteTree(fabricDir);
            if (!loaders.contains("neoforge") || unifiedNeo)
                deleteTree(neoDir);

            if (loaders.contains("fabric") && !unifiedFabric) {
                writeFabricProject(fabricDir, compileGroup, mc, str(row.get("fabricLoader")), str(row.get("fabricApi")),
                        loomVersion);
                validPaths.add(fabricDir);
                generated++;
            }

            if (loaders.contains("neoforge") && !unifiedNeo) {
                writeNeoProject(neoDir, compileGroup, mc, str(row.get("neoForge")), neoforgeModdev, false);
                validPaths.add(neoDir);
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
                writeFabricProject(dir, gid, str(fu.get("referenceMinecraft")), str(fu.get("fabricLoader")),
                        str(fu.get("fabricApi")), loomVersion);
                validPaths.add(dir);
                generated++;
            }
            if (bool(g.get("unifiedNeoForgeJar")) && g.containsKey("neoForgeUnified")) {
                Map<String, Object> nu = castMap(g.get("neoForgeUnified"));
                String line = primaryLinePrefix(g);
                String projectName = "neoforge-line-" + line.replace(".", "_");
                Path dir = VARIANTS_ROOT.resolve(gid).resolve(projectName);
                writeNeoProject(dir, gid, str(nu.get("referenceMinecraft")), str(nu.get("neoForge")), neoforgeModdev,
                        true);
                validPaths.add(dir);
                generated++;
            }
        }

        pruneStaleVariantDirs(validPaths);
        System.out.println("Generated " + generated + " variant projects under variants/");
    }

    private static void writeFabricProject(Path dir, String compileGroup, String mc, String fabricLoader,
            String fabricApi, String loomVersion) throws IOException {
        Files.createDirectories(dir);
        write(dir.resolve("gradle.properties"), """
                # Generated by tooling Java generator — do not hand-edit.
                compileGroup=%s
                minecraftVersion=%s
                fabricLoaderVersion=%s
                fabricApiVersion=%s
                neoForgeVersion=
                """.formatted(compileGroup, mc, fabricLoader, fabricApi));

        String archivesName = "chronos-backup-fabric-" + minecraftLineTag(mc);
        String pluginId = mc.startsWith("26.") ? "net.fabricmc.fabric-loom" : "net.fabricmc.fabric-loom-remap";
        String depConf = mc.startsWith("26.") ? "implementation" : "modImplementation";
        String mappings = mc.startsWith("26.") ? "" : "mappings loom.officialMojangMappings()";
        String javaDep = mc.startsWith("26.") ? ">=25" : ">=21";
        String mcDep = fabricMcDep(mc);
        String remapJar = mc.startsWith("26.") ? "" : "\n\ntasks.named('remapJar') {\n    archiveClassifier = ''\n}\n";

        write(dir.resolve("build.gradle"), """
                plugins {
                    id '%s' version '%s'
                }

                group = rootProject.findProperty('chronos.mod.group')
                version = rootProject.findProperty('chronos.mod.version')

                base { archivesName = "%s" }

                repositories { mavenCentral() }

                dependencies {
                    minecraft "com.mojang:minecraft:%s"
                    %s
                    %s "net.fabricmc:fabric-loader:%s"
                    %s "net.fabricmc.fabric-api:fabric-api:%s"
                }

                sourceSets.main.java.srcDirs(
                    rootProject.file('core/src/main/java'),
                    rootProject.file('shell-shared/src/main/java'),
                    rootProject.file('shell-fabric/src/main/java'),
                )

                processResources {
                    inputs.property 'version', project.version
                    inputs.property 'minecraft', '%s'
                    inputs.property 'mod_id', rootProject.findProperty('chronos.mod.id')
                    inputs.property 'mod_name', rootProject.findProperty('chronos.mod.name')
                    inputs.property 'mod_description', rootProject.findProperty('chronos.mod.description')
                    inputs.property 'mod_author', rootProject.findProperty('chronos.mod.author')
                    inputs.property 'mod_license', rootProject.findProperty('chronos.mod.license')
                    filesMatching('fabric.mod.json') {
                        expand 'version': project.version,
                               'minecraft': '%s',
                               'minecraft_dep': '%s',
                               'java_dep': '%s',
                               'mod_id': rootProject.findProperty('chronos.mod.id'),
                               'mod_name': rootProject.findProperty('chronos.mod.name'),
                               'mod_description': rootProject.findProperty('chronos.mod.description'),
                               'mod_author': rootProject.findProperty('chronos.mod.author'),
                               'mod_license': rootProject.findProperty('chronos.mod.license')
                    }
                }

                java {
                    withSourcesJar()
                    toolchain { languageVersion = JavaLanguageVersion.of(%s) }
                }

                tasks.withType(JavaCompile).configureEach {
                    options.encoding = 'UTF-8'
                    options.release = %s
                }%s
                """.formatted(
                pluginId, loomVersion, archivesName, mc, mappings, depConf, fabricLoader, depConf, fabricApi,
                mc, mc, mcDep, javaDep, mc.startsWith("26.") ? "25" : "21", mc.startsWith("26.") ? "25" : "21",
                remapJar));

        Path resources = dir.resolve("src/main/resources");
        Files.createDirectories(resources);
        write(resources.resolve("fabric.mod.json"),
                """
                        {
                          "schemaVersion": 1,
                          "id": "${mod_id}",
                          "version": "${version}",
                          "name": "${mod_name}",
                          "description": "${mod_description}",
                          "authors": ["${mod_author}"],
                          "license": "${mod_license}",
                          "icon": "assets/chronosbackup/icon.png",
                          "environment": "*",
                          "entrypoints": { "main": ["com.magicjinn.chronos.shell.fabric.ChronosFabricEntrypoint"] },
                          "depends": {
                            "fabricloader": ">=0.15.0",
                            "minecraft": "${minecraft_dep}",
                            "java": "${java_dep}",
                            "fabric-api": "*"
                          }
                        }
                        """);
    }

    private static void writeNeoProject(Path dir, String compileGroup, String mc, String neoVersion,
            String moddevVersion, boolean lineRange) throws IOException {
        Files.createDirectories(dir);
        write(dir.resolve("gradle.properties"), """
                # Generated by tooling Java generator — do not hand-edit.
                compileGroup=%s
                minecraftVersion=%s
                neoForgeVersion=%s
                """.formatted(compileGroup, mc, neoVersion));

        String archivesName = "chronos-backup-neoforge-" + minecraftLineTag(mc);
        String mcRange = lineRange ? lineRange(mc) : neoRange(mc);
        String javaMajor = mc.startsWith("26.") ? "25" : "21";
        write(dir.resolve("build.gradle.kts"), """
                plugins {
                    `java-library`
                    id("net.neoforged.moddev") version "%s"
                }

                group = rootProject.findProperty("chronos.mod.group") as String
                version = rootProject.findProperty("chronos.mod.version") as String

                base { archivesName.set("%s") }

                neoForge {
                    version = "%s"
                    mods {
                        register("chronosbackup") { sourceSet(sourceSets["main"]) }
                    }
                    runs {
                        register("server") {
                            server()
                            programArgument("--nogui")
                        }
                    }
                }

                repositories { mavenCentral() }

                sourceSets.named("main") {
                    java.srcDirs(
                        rootProject.file("core/src/main/java"),
                        rootProject.file("shell-shared/src/main/java"),
                        rootProject.file("shell-neoforge/src/main/java"),
                    )
                }

                tasks.processResources {
                    inputs.property("version", project.version)
                    inputs.property("minecraft", "%s")
                    inputs.property("mod_id", rootProject.findProperty("chronos.mod.id") as String)
                    inputs.property("mod_name", rootProject.findProperty("chronos.mod.name") as String)
                    inputs.property("mod_description", rootProject.findProperty("chronos.mod.description") as String)
                    inputs.property("mod_author", rootProject.findProperty("chronos.mod.author") as String)
                    inputs.property("mod_license", rootProject.findProperty("chronos.mod.license") as String)
                    filesMatching("META-INF/neoforge.mods.toml") {
                        expand(
                            mapOf(
                                "version" to project.version,
                                "minecraft_range" to "%s",
                                "neo_version" to "%s",
                                "mod_id" to (rootProject.findProperty("chronos.mod.id") as String),
                                "mod_name" to (rootProject.findProperty("chronos.mod.name") as String),
                                "mod_description" to (rootProject.findProperty("chronos.mod.description") as String),
                                "mod_author" to (rootProject.findProperty("chronos.mod.author") as String),
                                "mod_license" to (rootProject.findProperty("chronos.mod.license") as String),
                            ),
                        )
                    }
                }

                java {
                    withSourcesJar()
                    toolchain { languageVersion.set(JavaLanguageVersion.of(%s)) }
                }
                tasks.withType<JavaCompile>().configureEach {
                    options.encoding = "UTF-8"
                    options.release.set(%s)
                }
                """.formatted(moddevVersion, archivesName, neoVersion, mc, mcRange, neoVersion, javaMajor, javaMajor));

        Path meta = dir.resolve("src/main/resources/META-INF");
        Files.createDirectories(meta);
        write(meta.resolve("neoforge.mods.toml"),
                """
                        modLoader = "javafml"
                        loaderVersion = "[1,)"
                        license = "${mod_license}"

                        [[mods]]
                        modId = "${mod_id}"
                        version = "${version}"
                        displayName = "${mod_name}"
                        description = "${mod_description}"
                        authors = "${mod_author}"

                        [[dependencies.chronosbackup]]
                        modId = "neoforge"
                        type = "required"
                        versionRange = "[${neo_version},)"
                        ordering = "NONE"
                        side = "BOTH"

                        [[dependencies.chronosbackup]]
                        modId = "minecraft"
                        type = "required"
                        versionRange = "${minecraft_range}"
                        ordering = "NONE"
                        side = "BOTH"
                        """);
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

    private static String minecraftLineTag(String version) {
        String[] p = version.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] + ".x" : version + ".x";
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
                        if ((name.startsWith("fabric-") || name.startsWith("neoforge-"))
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

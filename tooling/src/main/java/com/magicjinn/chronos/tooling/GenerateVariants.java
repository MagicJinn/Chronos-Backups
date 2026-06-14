package com.magicjinn.chronos.tooling;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.magicjinn.chronos.tooling.TestServers.ChronosJavaMatrix;

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
    private static final Path GROUPS_FILE = ROOT.resolve("gradle/chronos-compile-groups.json");
    private static final Path TEMPLATES_DIR = ROOT.resolve("tooling/templates/generate-variants");
    private static final Map<String, String> TEMPLATE_FILES = readTemplateFiles();
    private static final Path VARIANTS_ROOT = ROOT.resolve("variants");
    private static final Map<String, String> TEMPLATES = readTemplates();
    private static final Path JAVA_MATRIX_FILE = ROOT.resolve("gradle/chronos-java-matrix.json");
    private static final ChronosJavaMatrix JAVA_MATRIX = loadJavaMatrix(JAVA_MATRIX_FILE);
    private static final Path FORGE_PACK_FORMAT_FILE = ROOT.resolve("gradle/chronos-forge-pack-format.json");
    private static final ForgePackFormatMatrix FORGE_PACK_FORMAT = loadForgePackFormat(FORGE_PACK_FORMAT_FILE);

    private GenerateVariants() {
    }

    public static void main(String[] args) throws Exception {
        if (!Files.exists(GROUPS_FILE)) {
            throw new IllegalStateException("Missing matrix file under gradle/: chronos-compile-groups.json");
        }

        Map<String, Object> groupsJson = readObject(GROUPS_FILE);
        List<Map<String, Object>> groups = castList(groupsJson.get("groups"));

        Map<String, String> rootProps = readRootProps();
        String loomVersion = rootProps.getOrDefault("loom.version", "1.10.1");
        String neoforgeModdev = rootProps.getOrDefault("neoforge.moddev.plugin.version", "2.0.141");

        Set<Path> validPaths = new HashSet<>();
        int generated = 0;

        for (Map<String, Object> g : groups) {
            if (!shouldBuildGroup(g))
                continue;
            String gid = str(g.get("id"));
            if (g.containsKey("fabricUnified")) {
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
            for (String neoKey : List.of("neoForgeEarlyUnified", "neoForgeUnified")) {
                if (!g.containsKey(neoKey)) {
                    continue;
                }
                Map<String, Object> nu = castMap(g.get(neoKey));
                String line = primaryLinePrefix(g).replace(".", "_");
                String projectName = "neoForgeEarlyUnified".equals(neoKey)
                        ? "neoforge-line-" + line + "_early"
                        : "neoforge-line-" + line;
                Path dir = VARIANTS_ROOT.resolve(gid).resolve(projectName);
                String neoMcRange = nu.get("minecraftRange") != null ? str(nu.get("minecraftRange")) : null;
                if (neoMcRange != null && neoMcRange.isBlank()) {
                    neoMcRange = null;
                }
                writeNeoProject(dir, gid, str(nu.get("referenceMinecraft")), str(nu.get("neoForge")), neoforgeModdev,
                        neoMcRange, nu);
                validPaths.add(dir);
                generated++;
            }
            if (g.containsKey("forgeUnified")) {
                Map<String, Object> fu = castMap(g.get("forgeUnified"));
                String line = primaryLinePrefix(g);
                String projectName = "forge-line-" + line.replace(".", "_");
                Path dir = VARIANTS_ROOT.resolve(gid).resolve(projectName);
                writeForgeProject(dir, gid, str(fu.get("referenceMinecraft")), str(fu.get("forge")), fu);
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
                # Generated by tooling Java generator - do not hand-edit.
                compileGroup=%s
                minecraftVersion=%s
                fabricLoaderVersion=%s
                fabricApiVersion=%s
                neoForgeVersion=
                """.formatted(compileGroup, mc, fabricLoader, fabricApi));

        String archivesName = "chronos-backups-fabric-" + archiveSuffix(unifiedArchiveSource, mc);
        String pluginId = isYearMinorMc(mc) ? "net.fabricmc.fabric-loom" : "net.fabricmc.fabric-loom-remap";
        String depConf = isYearMinorMc(mc) ? "implementation" : "modImplementation";
        String mappings = isYearMinorMc(mc) ? "" : "mappings loom.officialMojangMappings()";
        String toolchainEff = JAVA_MATRIX.gradleToolchainMajor(mc);
        String compileRel = JAVA_MATRIX.compileRelease(mc);
        String javaDep = JAVA_MATRIX.fabricJavaDependencyLine(mc);
        String mcDep = minecraftDepOverride != null && !minecraftDepOverride.isBlank()
                ? minecraftDepOverride
                : fabricMcDep(mc);
        String remapJar = isYearMinorMc(mc) ? "" : "\n\ntasks.named('remapJar') {\n    archiveClassifier = ''\n}\n";

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
        values.put("toolchainMajor", toolchainEff);
        values.put("javaDependencyResolutionMajor", toolchainEff);
        values.put("fabricJavaCompileOptionsGroovy", fabricJavaCompileOptionsGroovy(toolchainEff, compileRel));
        values.put("fabricCompileClasspathJvmAttrsGroovy", fabricCompileClasspathJvmAttrsGroovy(toolchainEff, compileRel));
        values.put("remapJarBlock", remapJar);
        putMojmapSourceDirLines(values, mc);
        putFabricRegistrarSourceDirLines(values, mc);
        values.put("fabricShellMessengerCompatGroovy", fabricShellMessengerCompatBlock(mc));
        values.put("fabricMojmapLegacyWorldGroovy", fabricMojmapLegacyWorldBlock(mc));
        write(dir.resolve("build.gradle"), renderTemplate("fabricBuildGradle", values));

        Path resources = dir.resolve("src/main/resources");
        Files.createDirectories(resources);
        Map<String, String> fabricModJsonValues = new HashMap<>();
        fabricModJsonValues.put("fabricLoaderDep", fabricLoaderDepForModJson(fabricLoader));
        fabricModJsonValues.put("fabricDependencyModId", fabricDependencyModIdForModJson(mc));
        write(resources.resolve("fabric.mod.json"), renderTemplate("fabricModJson", fabricModJsonValues));
    }

    private static void writeNeoProject(Path dir, String compileGroup, String mc, String neoVersion,
            String moddevVersion, String minecraftRangeOverride,
            Map<String, Object> unifiedArchiveSource) throws IOException {
        Files.createDirectories(dir);
        write(dir.resolve("gradle.properties"), """
                # Generated by tooling Java generator - do not hand-edit.
                compileGroup=%s
                minecraftVersion=%s
                neoForgeVersion=%s
                """.formatted(compileGroup, mc, neoVersion));

        String archivesName = "chronos-backups-neoforge-" + archiveSuffix(unifiedArchiveSource, mc);
        String mcRange = minecraftRangeOverride != null && !minecraftRangeOverride.isBlank()
                ? minecraftRangeOverride
                : lineRange(mc);
        ChronosJavaMatrix.ToolchainAndRelease neoJava = JAVA_MATRIX.toolchainAndRelease(mc);
        Map<String, String> neoValues = new HashMap<>();
        neoValues.put("moddevVersion", moddevVersion);
        neoValues.put("archivesName", archivesName);
        neoValues.put("neoVersion", neoVersion);
        neoValues.put("minecraft", mc);
        neoValues.put("minecraftRange", mcRange);
        neoValues.put("javaToolchainMajor", neoJava.toolchainMajor());
        neoValues.put("javaDependencyResolutionMajor", neoJava.toolchainMajor());
        neoValues.put("neoJavaCompileOptionsKts",
                neoJava.toolchainMajor().equals(neoJava.compileRelease())
                        ? "options.release.set(" + neoJava.compileRelease() + ")"
                        : "options.compilerArgs.addAll(listOf(\"--release\", \"" + neoJava.compileRelease() + "\"))");
        putNeoForgeShellSourceLines(neoValues, mc, unifiedArchiveSource);
        putMojmapSourceDirLines(neoValues, mc);
        write(dir.resolve("build.gradle.kts"), renderTemplate("neoBuildGradleKts", neoValues));

        Path meta = dir.resolve("src/main/resources/META-INF");
        Files.createDirectories(meta);
        String metadataKey = unifiedArchiveSource != null ? str(unifiedArchiveSource.get("metadataTemplate")) : "";
        if (metadataKey.isBlank()) {
            metadataKey = "neoModsToml";
        }
        String neoModsToml = renderTemplate(metadataKey, Map.of());
        // NeoForge 20.5+ only reads neoforge.mods.toml. 20.2-20.4 requires mods.toml
        write(meta.resolve("neoforge.mods.toml"), neoModsToml);
        write(meta.resolve("mods.toml"), neoModsToml);
    }

    /**
     * Early NeoForge lines (1.20.2-1.20.4) replace {@code ChronosNeoForgeMod} from
     * {@code shell-neoforge/src} with a line tree using legacy tick events.
     */
    private static void putNeoForgeShellSourceLines(Map<String, String> neoValues, String mc,
            Map<String, Object> unified) {
        String shellVariant = unified != null ? str(unified.get("neoShellVariant")) : "";
        if (shellVariant.isBlank()) {
            neoValues.put("neoForgeLineDir", minecraftLineFolder(mc));
            neoValues.put("neoShellExcludeKts", "");
            neoValues.put("neoShellExtraSrcKts", "");
            return;
        }
        neoValues.put("neoForgeLineDir", shellVariant);
        neoValues.put("neoShellExcludeKts", """
                java.exclude { details ->
                    val path = details.file.absolutePath.replace("\\\\", "/")
                    path.contains("shell-neoforge/src/main/java") && details.name == "ChronosNeoForgeMod.java"
                }
                """);
        neoValues.put("neoShellExtraSrcKts", "");
    }

    private static void writeForgeProject(Path dir, String compileGroup, String mc, String forgeVersion,
            Map<String, Object> unifiedArchiveSource) throws IOException {
        Files.createDirectories(dir);
        write(dir.resolve("gradle.properties"), """
                # Generated by tooling Java generator - do not hand-edit.
                compileGroup=%s
                minecraftVersion=%s
                forgeVersion=%s
                """.formatted(compileGroup, mc, forgeVersion));

        String archivesName = "chronos-backups-forge-" + archiveSuffix(unifiedArchiveSource, mc);
        String gradleTemplate = str(unifiedArchiveSource.get("buildTemplateKey"));
        String mappingsKind = str(unifiedArchiveSource.get("mappingsKind"));
        String metadataTemplate = str(unifiedArchiveSource.get("metadataTemplate"));
        if (gradleTemplate.isBlank() || mappingsKind.isBlank() || metadataTemplate.isBlank()) {
            throw new IllegalStateException("compile group \"" + compileGroup
                    + "\" forgeUnified must define buildTemplateKey, mappingsKind, and metadataTemplate in "
                    + GROUPS_FILE.toAbsolutePath().normalize());
        }
        Map<String, String> forgeGradleValues = new HashMap<>();
        forgeGradleValues.put("archivesName", archivesName);
        forgeGradleValues.put("minecraft", mc);
        forgeGradleValues.put("forgeVersion", forgeVersion);
        forgeGradleValues.put("forgeMojmapLegacyWorldGroovy", "");
        forgeGradleValues.put("forgeShellMessengerCompatGroovy", "");
        if ("mojmap".equalsIgnoreCase(mappingsKind)) {
            putMojmapSourceDirLines(forgeGradleValues, mc);
            putForgeMojmapAuxiliaryGroovy(forgeGradleValues, mc);
        } else {
            putForgeMcpMappings(forgeGradleValues, unifiedArchiveSource, compileGroup);
        }
        putForge17LineSourcesGroovy(forgeGradleValues, gradleTemplate, mc);
        write(dir.resolve("build.gradle"), renderTemplate(gradleTemplate, forgeGradleValues));

        Path resources = dir.resolve("src/main/resources");
        Files.createDirectories(resources);
        // mods.toml logoFile="icon.png" > icon at jar root, vanilla requires
        // pack.mcmeta or ResourcePackInfo fails
        write(resources.resolve("pack.mcmeta"), forgePackMcmeta(mc));
        if ("forgeMcmodInfo".equals(metadataTemplate)) {
            write(resources.resolve("mcmod.info"), renderTemplate("forgeMcmodInfo", Map.of(
                    "minecraft", mc)));
        } else if ("forgeModsToml".equals(metadataTemplate)) {
            Path meta = resources.resolve("META-INF");
            Files.createDirectories(meta);
            Map<String, String> modsTomlValues = new HashMap<>();
            modsTomlValues.put("loaderVersion", "*");
            modsTomlValues.put("minecraftRange", forgeModsTomlMinecraftRange(mc, unifiedArchiveSource));
            write(meta.resolve("mods.toml"), renderTemplate("forgeModsToml", modsTomlValues));
        } else {
            throw new IllegalStateException("Unknown Forge metadata template key \"" + metadataTemplate
                    + "\" for compile group \"" + compileGroup + "\"");
        }
    }

    /**
     * Matches {@code logoFile} / root {@code icon.png}, pack_format follows
     * Minecraft Java resource pack versions for {@code referenceMinecraft} (see
     * {@code gradle/chronos-forge-pack-format.json}).
     */
    private static String forgePackMcmeta(String mc) {
        int pf = FORGE_PACK_FORMAT.packFormatForMc(mc);
        return "{\n  \"pack\": {\n    \"pack_format\": " + pf + ",\n    \"description\": \"Chronos Backup\"\n  }\n}\n";
    }

    private static String fabricMcDep(String mc) {
        if (isYearMinorMc(mc)) {
            String[] p = mc.split("\\.");
            if (p.length >= 2) {
                try {
                    int minor = Integer.parseInt(p[1]);
                    return ">=" + p[0] + "." + p[1] + " <" + p[0] + "." + (minor + 1);
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        String[] p = mc.split("\\.");
        if (p.length == 2)
            return ">=" + mc + " <" + p[0] + "." + (Integer.parseInt(p[1]) + 1);
        if (p.length >= 3)
            return "~" + mc;
        return ">=" + mc;
    }

    private static boolean isYearMinorMc(String mc) {
        return JAVA_MATRIX.isYearMinorMc(mc);
    }

    private static String fabricJavaCompileOptionsGroovy(String toolchainMajor, String compileRelease) {
        if (toolchainMajor.equals(compileRelease)) {
            try {
                int c = Integer.parseInt(compileRelease);
                if (c >= 9) {
                    return "        def chronosRelease = " + compileRelease
                            + " as int\n        options.release = chronosRelease";
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
            return "        sourceCompatibility = JavaVersion.VERSION_1_8\n        targetCompatibility = JavaVersion.VERSION_1_8";
        }
        return "        options.compilerArgs.add('--release')\n        options.compilerArgs.add('" + compileRelease + "')";
    }

    private static String fabricCompileClasspathJvmAttrsGroovy(String toolchainMajor, String compileRelease) {
        if (toolchainMajor.equals(compileRelease)) {
            return "";
        }
        return "configurations.named('compileClasspath').configure {\n"
                + "    attributes {\n"
                + "        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, "
                + toolchainMajor
                + ")\n"
                + "    }\n"
                + "}\n";
    }

    /**
     * {@code fabric.mod.json} {@code fabricloader} constraint. Build still pins
     * {@code fabricLoader} from compile groups. Runtime accepts any loader version.
     */
    private static String fabricLoaderDepForModJson(String fabricLoader) {
        return "*";
    }

    /**
     * {@code fabric.mod.json} {@code depends} on Fabric API. Fabric API
     * published as {@code fabric} until Minecraft 1.19.1, from 1.19.2 onward the
     * jar id is {@code fabric-api} (which {@code provides} {@code fabric} on
     * those lines). Unified 1.19.x jars depend on {@code fabric} so
     * 1.19.0-1.19.1 resolve correctly. Minecraft 1.20+ and current-year lines
     * (e.g. 26.x) use {@code fabric-api} only.
     */
    private static String fabricDependencyModIdForModJson(String mc) {
        if (isYearMinorMc(mc)) {
            return "fabric-api";
        }
        String[] p = mc.split("\\.");
        try {
            if (p.length >= 2 && "1".equals(p[0])) {
                int minor = Integer.parseInt(p[1]);
                return minor >= 20 ? "fabric-api" : "fabric";
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return "fabric-api";
    }

    private static String lineRange(String linePrefix) {
        String[] p = linePrefix.split("\\.");
        if (p.length == 2)
            return "[" + linePrefix + "," + p[0] + "." + (Integer.parseInt(p[1]) + 1) + ")";
        return "[" + linePrefix + ",)";
    }

    /**
     * Minecraft version range for Forge {@code mods.toml} on 1.13+ (unified jars).
     * Optional {@code minecraftRange} on {@code forgeUnified} overrides. Otherwise
     * {@code referenceMc} {@code 1.20} without an override still maps to
     * {@code [1.20,1.20.1)} for the legacy single-patch Forge line.
     */
    private static String forgeModsTomlMinecraftRange(String referenceMc, Map<String, Object> forgeUnified) {
        if (forgeUnified != null) {
            String override = str(forgeUnified.get("minecraftRange"));
            if (!override.isBlank()) {
                return override;
            }
        }
        if ("1.20".equals(referenceMc)) {
            return "[1.20,1.20.1)";
        }
        return lineRange(minecraftLinePrefix(referenceMc));
    }

    /** {@code 1.14.4} → {@code 1.14} for unified-line {@code mods.toml} ranges. */
    private static String minecraftLinePrefix(String mc) {
        String[] p = mc.split("\\.");
        if (p.length >= 2) {
            return p[0] + "." + p[1];
        }
        return mc;
    }

    /**
     * MCP coordinates for Unimined {@code searge() + mcp} mappings on legacy Forge
     * (1.7–1.13).
     * Reads {@code mcpChannel} and {@code mcpVersion} from {@code forgeUnified}.
     */
    private static void putForgeMcpMappings(Map<String, String> out, Map<String, Object> forgeUnified,
            String compileGroup) {
        String channel = str(forgeUnified.get("mcpChannel"));
        String artifact = str(forgeUnified.get("mcpVersion"));
        if (channel.isBlank() || artifact.isBlank()) {
            throw new IllegalStateException(
                    "compile group \"" + compileGroup
                            + "\" forgeUnified needs mcpChannel and mcpVersion when mappingsKind is mcp");
        }
        out.put("mcpChannel", channel);
        out.put("mcpVersion", artifact);
    }

    /**
     * Forge 1.7.10 vs 1.7.2 share {@code shell-forge/v1_7_10} sources but need
     * different
     * {@link cpw.mods.fml.common.Mod#acceptedMinecraftVersions()} (via
     * {@code ForgeShellMcManifest}) and different game/loader resolution
     * (launchwrapper 1.9 vs 1.12).
     */
    private static void putForge17LineSourcesGroovy(Map<String, String> forgeGradleValues, String gradleTemplateKey,
            String referenceMinecraft) {
        if (!"forgeBuildGradle17".equals(gradleTemplateKey)) {
            forgeGradleValues.put("forge17LineSourcesGroovy", "");
            forgeGradleValues.put("seargeInvocation", "searge()");
            return;
        }
        if ("1.7.2".equals(referenceMinecraft)) {
            forgeGradleValues.put("seargeInvocation", "searge(\"1.7.10\")");
            forgeGradleValues.put("forge17LineSourcesGroovy",
                    """
                            sourceSets.main.java.srcDir(rootProject.file('shell-forge/v1_7_2/src/main/java'))
                            sourceSets.main.java.srcDir(rootProject.fileTree([dir: rootProject.file('shell-forge/v1_7_10/src/main/java'), excludes: ['**/ForgeShellMcManifest.java']]))
                            """
                            .stripLeading());
        } else {
            forgeGradleValues.put("seargeInvocation", "searge()");
            forgeGradleValues.put("forge17LineSourcesGroovy", """
                    sourceSets.main.java.srcDir(rootProject.file('shell-forge/v1_7_10/src/main/java'))
                    """.stripLeading());
        }
    }

    private static String minecraftLineTag(String version) {
        String[] p = version.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] + ".x" : version + ".x";
    }

    /**
     * Suffix for {@code base.archivesName} (after
     * {@code chronos-backups-<loader>-}). When {@code unifiedArchiveSource}
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
     * Directory name under {@code shell-neoforge/} for line-specific NeoForge
     * sources (e.g. {@code 1.20},
     * {@code 1.21}, {@code 26.1}) - not a separate Gradle module.
     */
    private static String minecraftLineFolder(String minecraftVersion) {
        String[] p = minecraftVersion.split("\\.");
        if (p.length >= 2)
            return p[0] + "." + p[1];
        return minecraftVersion;
    }

    /**
     * Mojmap messenger overrides: exclude {@code MojmapShellMessenger} from
     * {@code shell-mojmap/common}. For 1.14-1.17 the matching slice directory is
     * registered only in {@link #fabricMojmapLegacyWorldBlock(String)} so Gradle
     * does not
     * list the same folder twice (breaks {@code sourcesJar}). Uses
     * {@link #fabricShellMessengerCompat118()} for 1.18 only.
     */
    private static String fabricShellMessengerCompatBlock(String mc) {
        try {
            String[] p = mc.split("\\.");
            if (p.length >= 2 && "1".equals(p[0])) {
                int minor = Integer.parseInt(p[1]);
                if (minor <= 15) {
                    return fabricShellMessengerCompat114_115();
                }
                if (minor <= 17) {
                    return fabricShellMessengerCompat116_117();
                }
                if (minor == 18) {
                    return fabricShellMessengerCompat118();
                }
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return "";
    }

    private static String fabricShellMessengerCompat114_115() {
        return """
                sourceSets.main.java.exclude { details ->
                    details.file.absolutePath.replace(java.io.File.separator, '/').contains('shell-mojmap/common/src/main/java')
                            && details.name == 'MojmapShellMessenger.java'
                }
                """
                .stripLeading();
    }

    private static String fabricShellMessengerCompat118() {
        return """
                sourceSets.main.java.exclude { details ->
                    details.file.absolutePath.replace(java.io.File.separator, '/').contains('shell-mojmap/common/src/main/java')
                            && details.name == 'MojmapShellMessenger.java'
                }
                sourceSets.main.java.srcDir(rootProject.file('shell-mojmap/v1_18/src/main/java'))
                """
                .stripLeading();
    }

    /**
     * Minecraft 1.16-1.17 - {@link net.minecraft.network.chat.TextComponent} chat
     * until {@code Component#literal} stabilizes.
     */
    private static String fabricShellMessengerCompat116_117() {
        return """
                sourceSets.main.java.exclude { details ->
                    details.file.absolutePath.replace(java.io.File.separator, '/').contains('shell-mojmap/common/src/main/java')
                            && details.name == 'MojmapShellMessenger.java'
                }
                """
                .stripLeading();
    }

    /**
     * Older Fabric lines replace pieces of {@code shell-mojmap/common}: 1.14-1.15
     * swap env + backup
     * ({@code v1_14-v1_15}), 1.16-1.17 swap only backup ({@code v1_16-v1_17})
     * because env matches common.
     */
    private static String fabricMojmapLegacyWorldBlock(String mc) {
        try {
            String[] p = mc.split("\\.");
            if (p.length >= 2 && "1".equals(p[0])) {
                int minor = Integer.parseInt(p[1]);
                if (minor <= 15) {
                    return """
                            sourceSets.main.java.exclude { details ->
                                def path = details.file.absolutePath.replace(java.io.File.separator, '/')
                                path.contains('shell-mojmap/common/src/main/java')
                                        && (details.name == 'MojmapBackupWorldController.java'
                                            || details.name == 'MojmapServerEnvironment.java')
                            }
                            sourceSets.main.java.srcDir(rootProject.file('shell-mojmap/v1_14-v1_15/src/main/java'))
                            """.stripLeading();
                }
                if (minor <= 17) {
                    return """
                            sourceSets.main.java.exclude { details ->
                                def path = details.file.absolutePath.replace(java.io.File.separator, '/')
                                path.contains('shell-mojmap/common/src/main/java')
                                        && details.name == 'MojmapBackupWorldController.java'
                            }
                            sourceSets.main.java.srcDir(rootProject.file('shell-mojmap/v1_16-v1_17/src/main/java'))
                            """.stripLeading();
                }
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return "";
    }

    /**
     * Picks the {@code FabricCommandRegistrar} source slice (Fabric command API v1
     * vs v2, {@code sendSuccess} overloads).
     * TODO. Softcode this
     */
    private static void putFabricRegistrarSourceDirLines(Map<String, String> values, String mc) {
        int minor = minecraftMinorLine(mc);
        String slice;
        if (minor >= 14 && minor <= 18) {
            slice = "    rootProject.file('shell-fabric/v1_14-v1_18/src/main/java'),";
        } else if (minor == 19) {
            slice = "    rootProject.file('shell-fabric/v1_19-v1_20/src/main/java'),\n"
                    + "    rootProject.file('shell-fabric/v1_19/src/main/java'),";
        } else {
            slice = "    rootProject.file('shell-fabric/v1_19-v1_20/src/main/java'),\n"
                    + "    rootProject.file('shell-fabric/v1_20/src/main/java'),";
        }
        values.put("fabricRegistrarSliceGroovy", slice);
    }

    /**
     * Minecraft 1.x minor version for Fabric-only toolchain splits (1.14-1.17 chat
     * APIs). Non-1.x uses the
     * modern slice.
     */
    private static int minecraftMinorLine(String mc) {
        if (isYearMinorMc(mc)) {
            return 99;
        }
        String[] p = mc.split("\\.");
        try {
            if (p.length >= 2 && "1".equals(p[0])) {
                return Integer.parseInt(p[1]);
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return 99;
    }

    /**
     * Fills Gradle/Kotlin template placeholders for Mojmap command gate sources: adds
     * {@code shell-mojmap/v1_14} for numeric {@code CommandSourceStack#hasPermission(int)}, or
     * {@code shell-mojmap/v1_21_11} for the {@code PermissionSet}-based implementation (mutually
     * exclusive).
     */
    /**
     * Mojmap-era Forge builds reuse the same {@code shell-mojmap} exclusions /
     * extra source dirs as Fabric for messenger + legacy world slices (1.14-1.18).
     */
    private static void putForgeMojmapAuxiliaryGroovy(Map<String, String> values, String mc) {
        values.put("forgeMojmapLegacyWorldGroovy", fabricMojmapLegacyWorldBlock(mc));
        values.put("forgeShellMessengerCompatGroovy", fabricShellMessengerCompatBlock(mc));
    }

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

    private static Map<String, Object> readObject(Path file) throws IOException {
        Type type = new TypeToken<Map<String, Object>>() {
        }.getType();
        return GSON.fromJson(Files.readString(file), type);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readTemplateFiles() {
        try {
            if (!Files.isRegularFile(GROUPS_FILE)) {
                throw new IllegalStateException("Missing matrix file under gradle/: chronos-compile-groups.json");
            }
            Map<String, Object> root = readObject(GROUPS_FILE);
            Object gvObj = root.get("generateVariants");
            if (!(gvObj instanceof Map)) {
                throw new IllegalStateException(GROUPS_FILE + " must define generateVariants.templateFiles");
            }
            Map<String, Object> gv = (Map<String, Object>) gvObj;
            Object tfObj = gv.get("templateFiles");
            if (!(tfObj instanceof Map)) {
                throw new IllegalStateException(
                        GROUPS_FILE + " must define generateVariants.templateFiles as a JSON object");
            }
            Map<String, Object> tf = (Map<String, Object>) tfObj;
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, Object> e : tf.entrySet()) {
                String v = str(e.getValue());
                if (v.isBlank()) {
                    throw new IllegalStateException("generateVariants.templateFiles[\"" + e.getKey()
                            + "\"] must be non-blank in " + GROUPS_FILE);
                }
                out.put(e.getKey(), v);
            }
            if (out.isEmpty()) {
                throw new IllegalStateException("generateVariants.templateFiles must not be empty in " + GROUPS_FILE);
            }
            return Map.copyOf(out);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + GROUPS_FILE, ex);
        }
    }

    private static Map<String, String> readTemplates() {
        Map<String, String> out = new HashMap<>();
        Path rootNeoModsToml = ROOT.resolve("META-INF/neoforge.mods.toml");
        for (Map.Entry<String, String> e : TEMPLATE_FILES.entrySet()) {
            Path path = TEMPLATES_DIR.resolve(e.getValue());
            try {
                if ("neoModsToml".equals(e.getKey()) && Files.isRegularFile(rootNeoModsToml)) {
                    out.put(e.getKey(), Files.readString(rootNeoModsToml, StandardCharsets.UTF_8));
                    continue;
                }
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

    /* shouldBuild flag in JSON determines if the group is built */
    private static boolean shouldBuildGroup(Map<String, Object> group) {
        Object value = group.get("shouldBuild");
        return !(value instanceof Boolean b) || b;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    private static ForgePackFormatMatrix loadForgePackFormat(Path file) {
        try {
            if (!Files.isRegularFile(file))
                throw new IOException("Missing Forge pack format file: " + file);
            Map<String, Object> root = readObject(file);
            int fallback = intProp(root.get("nonLegacyFallbackPackFormat"), 15);
            String legacyMajor = str(root.get("legacyMajor"));
            if (legacyMajor.isEmpty())
                legacyMajor = "1";
            Map<Integer, Integer> fixedMinor = new HashMap<>();
            for (Map<String, Object> row : castList(root.get("fixedMinor"))) {
                int minor = intProp(row.get("minor"), -1);
                int pf = intProp(row.get("packFormat"), -1);
                if (minor >= 0 && pf >= 0)
                    fixedMinor.put(minor, pf);
            }
            Map<String, Object> m16 = castMap(root.get("minor16"));
            int m16Low = intProp(m16.get("packFormatWhenPatchBelow"), 5);
            int m16PatchAtLeast = intProp(m16.get("patchAtLeast"), 2);
            int m16High = intProp(m16.get("packFormatWhenPatchAtLeast"), 6);
            List<PatchThreshold> t20 = readPatchThresholds(castList(root.get("minor20PatchThresholds")), file);
            List<PatchThreshold> t21 = readPatchThresholds(castList(root.get("minor21PatchThresholds")), file);
            Map<String, Object> unmatched = castMap(root.get("unmatchedLegacyMinor"));
            int uAtLeast = intProp(unmatched.get("minorAtLeast"), 21);
            int uHigh = intProp(unmatched.get("packFormatWhenAtLeast"), 48);
            int uLow = intProp(unmatched.get("packFormatOtherwise"), 15);
            return new ForgePackFormatMatrix(fallback, legacyMajor, fixedMinor, m16Low, m16PatchAtLeast, m16High, t20,
                    t21, uAtLeast, uHigh, uLow);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static List<PatchThreshold> readPatchThresholds(List<Map<String, Object>> rows, Path sourceFile) {
        if (rows == null || rows.isEmpty())
            throw new IllegalStateException("Patch thresholds must be non-empty in " + sourceFile);
        List<PatchThreshold> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int max = intProp(row.get("patchMaxInclusive"), -1);
            int pf = intProp(row.get("packFormat"), -1);
            if (max < 0 || pf < 0)
                throw new IllegalStateException("Invalid patch threshold row in " + sourceFile + ": " + row);
            out.add(new PatchThreshold(max, pf));
        }
        return List.copyOf(out);
    }

    private static int intProp(Object value, int defaultValue) {
        if (value instanceof Number n)
            return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private record PatchThreshold(int patchMaxInclusive, int packFormat) {
    }

    private static final class ForgePackFormatMatrix {
        private final int nonLegacyFallbackPackFormat;
        private final String legacyMajor;
        private final Map<Integer, Integer> fixedMinor;
        private final int minor16Low;
        private final int minor16PatchAtLeast;
        private final int minor16High;
        private final List<PatchThreshold> minor20Thresholds;
        private final List<PatchThreshold> minor21Thresholds;
        private final int unmatchedMinorAtLeast;
        private final int unmatchedPackHigh;
        private final int unmatchedPackLow;

        private ForgePackFormatMatrix(int nonLegacyFallbackPackFormat, String legacyMajor,
                Map<Integer, Integer> fixedMinor, int minor16Low, int minor16PatchAtLeast, int minor16High,
                List<PatchThreshold> minor20Thresholds, List<PatchThreshold> minor21Thresholds,
                int unmatchedMinorAtLeast, int unmatchedPackHigh, int unmatchedPackLow) {
            this.nonLegacyFallbackPackFormat = nonLegacyFallbackPackFormat;
            this.legacyMajor = legacyMajor;
            this.fixedMinor = Map.copyOf(fixedMinor);
            this.minor16Low = minor16Low;
            this.minor16PatchAtLeast = minor16PatchAtLeast;
            this.minor16High = minor16High;
            this.minor20Thresholds = minor20Thresholds;
            this.minor21Thresholds = minor21Thresholds;
            this.unmatchedMinorAtLeast = unmatchedMinorAtLeast;
            this.unmatchedPackHigh = unmatchedPackHigh;
            this.unmatchedPackLow = unmatchedPackLow;
        }

        private int packFormatForMc(String mc) {
            if (mc == null || mc.isBlank())
                return nonLegacyFallbackPackFormat;
            String[] parts = mc.split("\\.");
            try {
                if (parts.length >= 2 && legacyMajor.equals(parts[0])) {
                    int minor = Integer.parseInt(parts[1]);
                    int patch = parsePatch(parts);
                    Integer fixed = fixedMinor.get(minor);
                    if (fixed != null)
                        return fixed;
                    if (minor == 16)
                        return patch >= minor16PatchAtLeast ? minor16High : minor16Low;
                    if (minor == 20)
                        return resolvePatchThreshold(minor20Thresholds, patch);
                    if (minor == 21)
                        return resolvePatchThreshold(minor21Thresholds, patch);
                    return minor >= unmatchedMinorAtLeast ? unmatchedPackHigh : unmatchedPackLow;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
            return nonLegacyFallbackPackFormat;
        }

        private static int parsePatch(String[] parts) {
            if (parts.length < 3)
                return 0;
            String patchRaw = parts[2].replaceAll("[^0-9].*", "");
            if (patchRaw.isEmpty())
                return 0;
            return Integer.parseInt(patchRaw);
        }

        private static int resolvePatchThreshold(List<PatchThreshold> thresholds, int patch) {
            for (PatchThreshold t : thresholds) {
                if (patch <= t.patchMaxInclusive)
                    return t.packFormat;
            }
            return thresholds.get(thresholds.size() - 1).packFormat;
        }
    }

    private static ChronosJavaMatrix loadJavaMatrix(Path file) {
        try {
            return ChronosJavaMatrix.load(file);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

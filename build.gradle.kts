import groovy.json.JsonSlurper
import org.gradle.language.jvm.tasks.ProcessResources
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

val packableSubprojects = subprojects.filter { it.path != ":core" && it.path != ":tooling" }
val rustPrunerOutputRoot = layout.buildDirectory.dir("generated/rust-pruner-resources")
data class RustNativeTarget(
    val osId: String,
    val archId: String,
    val libraryFileName: String,
    val rustTargetTriple: String,
)

val rustNativeTargets =
    listOf(
        RustNativeTarget("windows", "x86_64", "rust_pruner.dll", "x86_64-pc-windows-msvc"),
        RustNativeTarget("windows", "aarch64", "rust_pruner.dll", "aarch64-pc-windows-msvc"),
        RustNativeTarget("linux", "x86_64", "librust_pruner.so", "x86_64-unknown-linux-gnu"),
        RustNativeTarget("linux", "aarch64", "librust_pruner.so", "aarch64-unknown-linux-gnu"),
        RustNativeTarget("macos", "x86_64", "librust_pruner.dylib", "x86_64-apple-darwin"),
        RustNativeTarget("macos", "aarch64", "librust_pruner.dylib", "aarch64-apple-darwin"),
    )

fun currentOsId(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("win") -> "windows"
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        else -> "linux"
    }
}

fun currentArchId(): String {
    val arch = System.getProperty("os.arch").lowercase()
    return when (arch) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> arch.replace(Regex("[^a-z0-9_]+"), "_")
    }
}

val rustProjectDir = rootProject.file("core/native/rust-pruner")
val cargoCmd = if (currentOsId() == "windows") "cargo.exe" else "cargo"
val rustupCmd = if (currentOsId() == "windows") "rustup.exe" else "rustup"
fun gradleBooleanProperty(defaultValue: Boolean, vararg keys: String): Provider<Boolean> =
    providers.provider {
        keys.firstNotNullOfOrNull { key ->
            providers.gradleProperty(key).orNull?.equals("true", ignoreCase = true)
        } ?: defaultValue
    }

val rustSkipBuildProvider =
    gradleBooleanProperty(
        false,
        "chronosRustSkipBuild",
        "chronos.rust.skipBuild",
    )
val rustBuildAllTargetsProvider =
    gradleBooleanProperty(
        (System.getenv("CI") ?: "false").equals("true", ignoreCase = true),
        "chronosRustBuildAllTargets",
        "chronos.rust.buildAllTargets",
    )
val rustRequireAllTargetsProvider =
    gradleBooleanProperty(
        rustBuildAllTargetsProvider.get(),
        "chronosRustRequireAllTargets",
        "chronos.rust.requireAllTargets",
    )
val activeRustNativeTargetsProvider =
    providers.provider {
        val buildAll = rustBuildAllTargetsProvider.get()
        val skipBuild = rustSkipBuildProvider.get()
        if (buildAll || skipBuild) {
            rustNativeTargets
        } else {
            val hostOs = currentOsId()
            val hostArch = currentArchId()
            rustNativeTargets.filter { target -> target.osId == hostOs && target.archId == hostArch }
        }
    }
val buildRustTargetTasks =
    rustNativeTargets.map { target ->
        val slug = "${target.osId}_${target.archId}".replace('-', '_')
        val ensureTask =
            tasks.register<Exec>("rustTargetAdd_$slug") {
                group = "build"
                description = "Installs Rust target ${target.rustTargetTriple} (nightly)."
                workingDir = rustProjectDir
                onlyIf {
                    !rustSkipBuildProvider.get() &&
                        activeRustNativeTargetsProvider.get().any { active -> active == target }
                }
                commandLine(rustupCmd, "target", "add", target.rustTargetTriple, "--toolchain", "nightly")
            }
        tasks.register<Exec>("buildRust_$slug") {
            group = "build"
            description = "Builds rust-pruner for ${target.rustTargetTriple}."
            dependsOn(ensureTask)
            workingDir = rustProjectDir
            environment("RUSTUP_TOOLCHAIN", "nightly")
            onlyIf {
                !rustSkipBuildProvider.get() &&
                    activeRustNativeTargetsProvider.get().any { active -> active == target }
            }
            commandLine(cargoCmd, "build", "--release", "--target", target.rustTargetTriple)
        }
    }

val buildRust =
    tasks.register("buildRust") {
        group = "build"
        description = "Builds rust-pruner native libraries for active OS/arch targets."
        dependsOn(buildRustTargetTasks)
        onlyIf { !rustSkipBuildProvider.get() }
        doFirst {
            val active = activeRustNativeTargetsProvider.get()
            logger.lifecycle(
                "Active Rust native targets: ${active.joinToString { it.rustTargetTriple }} " +
                    "(set -Pchronos.rust.buildAllTargets=true for full matrix)"
            )
        }
    }

val stageRustPrunerNative =
    tasks.register("stageRustPrunerNative") {
        group = "build"
        description = "Stages all rust-pruner native libs as jar resources."
        dependsOn(buildRust)
        doLast {
            val outputRoot = rustPrunerOutputRoot.get().asFile
            outputRoot.deleteRecursively()
            outputRoot.mkdirs()
            val missing = mutableListOf<String>()
            val activeTargets = activeRustNativeTargetsProvider.get()
            val requireAllTargets = rustRequireAllTargetsProvider.get()

            for (target in activeTargets) {
                val source =
                    rootProject.file(
                        "core/native/rust-pruner/target/${target.rustTargetTriple}/release/${target.libraryFileName}"
                    )
                if (!source.isFile) {
                    missing += "${target.osId}-${target.archId}/${target.libraryFileName}"
                    continue
                }

                val destinationDir = outputRoot.resolve("natives/${target.osId}-${target.archId}")
                destinationDir.mkdirs()
                copy {
                    from(source)
                    into(destinationDir)
                }
            }

            if (missing.isNotEmpty() && requireAllTargets) {
                throw GradleException(
                    "Missing rust-pruner native libraries for active targets: ${
                        missing.joinToString(", ")
                    }. " +
                        "Expected them under core/native/rust-pruner/target/<rust-target>/release/. " +
                        "Run './gradlew buildRust --stacktrace' to diagnose cross-compilation issues."
                )
            }
            if (missing.isNotEmpty()) {
                logger.lifecycle(
                    "Some rust-pruner targets are missing and were not staged: ${missing.joinToString(", ")}. " +
                        "Set -Pchronos.rust.requireAllTargets=true to fail when any target is missing."
                )
            }
        }
    }

val generatedBuildIcon = layout.buildDirectory.file("generated/icon-128.png")

val prepareBuildIcon by tasks.registering {
    group = "build setup"
    description = "Generates a 128x128 icon for packaging."
    val sourceIcon = layout.projectDirectory.file("icon.png")
    inputs.file(sourceIcon)
    outputs.file(generatedBuildIcon)
    doLast {
        val source = sourceIcon.asFile
        require(source.exists()) { "Missing icon source: ${source.absolutePath}" }
        val image = ImageIO.read(source)
            ?: throw IllegalStateException("Failed to read image: ${source.absolutePath}")
        val resized = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        val g = resized.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(image, 0, 0, 128, 128, null)
        g.dispose()
        val output = generatedBuildIcon.get().asFile
        output.parentFile.mkdirs()
        ImageIO.write(resized, "png", output)
    }
}

// Per line slug: optional `jarTargetLabel` (group-wide fallback for collectAllJars).
@Suppress("UNCHECKED_CAST")
val jarTargetLabelByLineSlug: Map<String, String> = run {
    val f = layout.projectDirectory.file("gradle/chronos-compile-groups.json").asFile
    if (!f.exists()) {
        return@run emptyMap()
    }
    val root = JsonSlurper().parseText(f.readText()) as Map<String, Any>
    val groups = root["groups"] as? List<*> ?: return@run emptyMap()
    val out = mutableMapOf<String, String>()
    for (g in groups) {
        val gm = g as? Map<*, *> ?: continue
        val label = gm["jarTargetLabel"]?.toString() ?: continue
        val pfx = gm["minecraftVersionPrefixes"] as? List<*> ?: continue
        if (pfx.isEmpty()) continue
        val primary = pfx.map { it.toString() }.minByOrNull { it.length } ?: continue
        val slug = primary.replace(".", "_")
        out[slug] = label
    }
    out.toMap()
}

// Loader-specific archive suffix for collectAllJars: `fabricUnified` / `neoForgeUnified` /
// `forgeUnified`.`archiveVersionTag` in chronos-compile-groups.json (matches variant base.archivesName).
@Suppress("UNCHECKED_CAST")
val jarArchiveTagByLoaderAndSlug: Map<String, Map<String, String>> = run {
    val f = layout.projectDirectory.file("gradle/chronos-compile-groups.json").asFile
    if (!f.exists()) {
        return@run emptyMap()
    }
    val root = JsonSlurper().parseText(f.readText()) as Map<String, Any>
    val groups = root["groups"] as? List<*> ?: return@run emptyMap()
    val out = mutableMapOf<String, MutableMap<String, String>>()
    fun putTag(loader: String, slug: String, tag: String) {
        out.getOrPut(loader) { mutableMapOf() }[slug] = tag
    }
    for (g in groups) {
        val gm = g as? Map<*, *> ?: continue
        val pfx = gm["minecraftVersionPrefixes"] as? List<*> ?: continue
        if (pfx.isEmpty()) continue
        val primary = pfx.map { it.toString() }.minByOrNull { it.length } ?: continue
        val slug = primary.replace(".", "_")
        (gm["fabricUnified"] as? Map<*, *>)?.get("archiveVersionTag")?.toString()?.takeIf { it.isNotBlank() }?.let {
            putTag("fabric", slug, it)
        }
        (gm["neoForgeUnified"] as? Map<*, *>)?.get("archiveVersionTag")?.toString()?.takeIf { it.isNotBlank() }?.let {
            putTag("neoforge", slug, it)
        }
        (gm["forgeUnified"] as? Map<*, *>)?.get("archiveVersionTag")?.toString()?.takeIf { it.isNotBlank() }?.let {
            putTag("forge", slug, it)
        }
    }
    out.mapValues { it.value.toMap() }
}

configure(packableSubprojects) {
    tasks.withType<ProcessResources>().configureEach {
        dependsOn(rootProject.tasks.named("stageRustPrunerNative"))
        from(rootProject.layout.buildDirectory.dir("generated/rust-pruner-resources"))
        dependsOn(rootProject.tasks.named("prepareBuildIcon"))
        val projectName = project.name
        if (projectName.startsWith("fabric-")) {
            from(rootProject.layout.buildDirectory.file("generated/icon-128.png")) {
                into("assets/chronosbackup")
                rename { "icon.png" }
            }
        } else if (projectName.startsWith("neoforge-")) {
            from(rootProject.layout.buildDirectory.file("generated/icon-128.png")) {
                into("assets/chronosbackup")
                rename { "icon.png" }
            }
        }
    }
}

tasks.register("generateVariants") {
    group = "chronos"
    description = "Generates variants/<compileGroup> via Java tooling."
    dependsOn(":tooling:runGenerateVariants")
}

tasks.register("cleanVariants") {
    group = "chronos"
    description = "Force-deletes the variants/ folder via Java tooling; retries to handle locked files (e.g. on Windows)."
    dependsOn(":tooling:runCleanVariants")
}

tasks.register("smokeTest") {
    group = "verification"
    description = "Runs dedicated server smoke tests via Java tooling."
    dependsOn(":tooling:runSmokeTest")
}

fun collectedJarPrefix(variantProjectName: String): String {
    val lineMatch = Regex("""^(fabric|forge|neoforge)-line-(.+)$""").matchEntire(variantProjectName)
    if (lineMatch != null) {
        val loader = lineMatch.groupValues[1]
        val slug = lineMatch.groupValues[2]
        return "$loader-${variantSlugToVersionLabel(loader, slug)}"
    }
    val bareMatch = Regex("""^(fabric|forge|neoforge)-(.+)$""").matchEntire(variantProjectName)
    if (bareMatch != null) {
        val loader = bareMatch.groupValues[1]
        val slug = bareMatch.groupValues[2]
        return "$loader-${variantSlugToVersionLabel(loader, slug)}"
    }
    return variantProjectName.replace("-line-", "-")
}

fun loaderAndMcTarget(variantProjectName: String): Pair<String, String> {
    val prefix = collectedJarPrefix(variantProjectName)
    val i = prefix.indexOf('-')
    require(i > 0) { "Expected loader-target prefix, got: $prefix" }
    return prefix.substring(0, i) to prefix.substring(i + 1)
}

fun collectedJarName(modId: String, modVersion: String, variantProjectName: String): String {
    val (loader, mcTarget) = loaderAndMcTarget(variantProjectName)
    return "$modId-$mcTarget-$modVersion-$loader.jar"
}

fun variantSlugToVersionLabel(loader: String, slug: String): String =
    jarArchiveTagByLoaderAndSlug[loader]?.get(slug)
        ?: jarTargetLabelByLineSlug[slug]
        ?: slug.split("-").joinToString("-") { part ->
            part.replace('_', '.')
        }

tasks.register<Copy>("collectAllJars") {
    group = "build"
    description =
        "Copies each variant production JAR into root build/libs (renamed). Variant build/libs are left intact so Gradle incremental builds keep valid remap/jar outputs."
    into(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(packableSubprojects.map { it.tasks.named("build") })
    val modId =
        (findProperty("chronos.mod.id") ?: "chronosbackups").toString().lowercase()
    val modVersion = (findProperty("chronos.mod.version") ?: "0.0.0").toString()
    for (sub in packableSubprojects) {
        from(sub.layout.buildDirectory.dir("libs")) {
            include("*.jar")
            exclude("*-sources.jar", "*-javadoc.jar", "*-dev.jar")
            rename { collectedJarName(modId, modVersion, sub.name) }
        }
    }
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds every included variant subproject and collects final jars."
    dependsOn("collectAllJars")
}

import groovy.json.JsonSlurper
import org.gradle.jvm.tasks.Jar
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
val rustRequireAllTargetsProvider =
    gradleBooleanProperty(
        false,
        "chronosRustRequireAllTargets",
        "chronos.rust.requireAllTargets",
    )
val rustExtraTargetsPropertyProvider =
    providers.gradleProperty("chronosRustExtraTargets")
        .orElse(providers.gradleProperty("chronos.rust.extraTargets"))
val rustLinuxViaDockerPropertyProvider =
    providers.gradleProperty("chronosRustLinuxViaDocker")
        .orElse(providers.gradleProperty("chronos.rust.linuxViaDocker"))
val rustLinuxDockerImageProvider =
    providers.gradleProperty("chronosRustLinuxDockerImage")
        .orElse(providers.gradleProperty("chronos.rust.linuxDockerImage"))
        .orElse("rust:bookworm")
val rustLinuxGlibcMaxProvider =
    providers.gradleProperty("chronosRustLinuxGlibcMax")
        .orElse(providers.gradleProperty("chronos.rust.linuxGlibcMax"))
        .orElse("2.17")
val rustLinuxViaDockerProvider =
    providers.provider {
        rustLinuxViaDockerPropertyProvider.orNull?.equals("true", ignoreCase = true)
            ?: (isTestServersGradleInvocation() && currentOsId() != "linux")
    }
fun isTestServersGradleInvocation(): Boolean =
    gradle.startParameter.taskNames.any { taskName ->
        val bare = taskName.substringAfterLast(':')
        bare == "testServers" || bare == "prepareTestServers" || bare == "runTestServers"
    }

fun useDockerForLinuxTarget(target: RustNativeTarget): Boolean {
    if (target.osId != "linux") {
        return false
    }
    if (!rustCompileTargetsProvider.get().any { active -> active == target }) {
        return false
    }
    val explicit = rustLinuxViaDockerPropertyProvider.orNull
    if (explicit?.equals("false", ignoreCase = true) == true) {
        return false
    }
    if (explicit?.equals("true", ignoreCase = true) == true) {
        return true
    }
    // linux-gnu is always built in an old-glibc container so the .so loads on older
    // dedicated-server images (e.g. itzg/minecraft-server:java8 ships glibc 2.31).
    if (target.rustTargetTriple == "x86_64-unknown-linux-gnu") {
        return true
    }
    // Cross-compiling linux-aarch64 from x86_64 (or other hosts) needs Zig in Docker.
    if (target.rustTargetTriple == "aarch64-unknown-linux-gnu" && currentArchId() != "aarch64") {
        return true
    }
    if (!rustLinuxViaDockerProvider.get()) {
        return false
    }
    val host = hostRustTarget()
    return !(host.osId == target.osId && host.archId == target.archId)
}

fun runDockerRustBuild(target: RustNativeTarget) {
    val repo = layout.projectDirectory.asFile.absolutePath.replace('\\', '/')
    val dockerImage = rustLinuxDockerImageProvider.get()
    val glibcMax = rustLinuxGlibcMaxProvider.get()
    val script =
        if (target.rustTargetTriple == "x86_64-unknown-linux-gnu" ||
            target.rustTargetTriple == "aarch64-unknown-linux-gnu"
        ) {
            val zigTarget =
                when (target.rustTargetTriple) {
                    "x86_64-unknown-linux-gnu" -> "x86_64-unknown-linux-gnu.$glibcMax"
                    "aarch64-unknown-linux-gnu" -> "aarch64-unknown-linux-gnu.$glibcMax"
                    else -> error("unexpected zig linux target: ${target.rustTargetTriple}")
                }
            """
            #!/usr/bin/env bash
            set -euo pipefail
            ZIG_VER=0.13.0
            ZIG_DIR=/tmp/zig-${'$'}ZIG_VER
            if [ ! -x "${'$'}ZIG_DIR/zig" ]; then
              echo "[chronos] Downloading Zig ${'$'}ZIG_VER to container /tmp..."
              mkdir -p "${'$'}ZIG_DIR"
              curl -fsSL "https://ziglang.org/download/${'$'}ZIG_VER/zig-linux-x86_64-${'$'}ZIG_VER.tar.xz" | tar -xJ -C "${'$'}ZIG_DIR" --strip-components=1
            else
              echo "[chronos] Reusing cached Zig in container /tmp"
            fi
            export PATH="${'$'}ZIG_DIR:/usr/local/cargo/bin:${'$'}PATH"
            echo "[chronos] Ensuring Rust nightly..."
            rustup toolchain install nightly --profile minimal
            if ! command -v cargo-zigbuild >/dev/null 2>&1; then
              echo "[chronos] Installing cargo-zigbuild..."
              cargo +nightly install cargo-zigbuild --locked
            else
              echo "[chronos] Reusing cached cargo-zigbuild"
            fi
            echo "[chronos] Building rust-pruner for $zigTarget..."
            cd /workspace/core/native/rust-pruner
            cargo +nightly zigbuild --release --target "$zigTarget"
            echo "[chronos] rust-pruner build finished"
            """.trimIndent()
        } else {
            """
            #!/usr/bin/env bash
            set -euo pipefail
            echo "[chronos] Ensuring Rust nightly..."
            rustup toolchain install nightly --profile minimal
            rustup target add ${target.rustTargetTriple} --toolchain nightly 2>/dev/null || true
            echo "[chronos] Building rust-pruner for ${target.rustTargetTriple}..."
            cd /workspace/core/native/rust-pruner
            cargo +nightly build --release --target ${target.rustTargetTriple}
            echo "[chronos] rust-pruner build finished"
            """.trimIndent()
        }
    val scriptFile =
        layout.buildDirectory.file("tmp/rust-docker-${target.osId}-${target.archId}.sh").get().asFile
    scriptFile.parentFile.mkdirs()
    scriptFile.writeText(script.replace("\r\n", "\n"))
    val scriptInContainer = "/workspace/build/tmp/rust-docker-${target.osId}-${target.archId}.sh"
    logger.lifecycle(
        "Starting Docker rust-pruner build for ${target.rustTargetTriple} (image=$dockerImage). " +
            "First run downloads Zig and cargo-zigbuild and can take several minutes."
    )
    val process =
        ProcessBuilder(
            "docker",
            "run",
            "--rm",
            "-v",
            "$repo:/workspace",
            "-w",
            "/workspace",
            dockerImage,
            "bash",
            scriptInContainer,
        )
            .redirectErrorStream(true)
            .start()
    process.inputStream.bufferedReader().forEachLine { line ->
        logger.lifecycle(line)
    }
    if (process.waitFor() != 0) {
        throw GradleException(
            "Docker rust-pruner build failed for ${target.rustTargetTriple} in $dockerImage " +
                "(exit ${process.exitValue()}). Ensure Docker is running."
        )
    }
}

fun hostRustTarget(): RustNativeTarget {
    val hostOs = currentOsId()
    val hostArch = currentArchId()
    return rustNativeTargets.first { it.osId == hostOs && it.archId == hostArch }
}

/** cargo-zigbuild with a glibc suffix may emit under `triple.glibcMax` instead of `triple`. */
fun rustReleaseLibraryFile(target: RustNativeTarget): java.io.File {
    val base = rootProject.file("core/native/rust-pruner/target")
    val candidates = mutableListOf<java.io.File>()
    if (target.rustTargetTriple == "x86_64-unknown-linux-gnu" ||
        target.rustTargetTriple == "aarch64-unknown-linux-gnu"
    ) {
        val glibcMax = rustLinuxGlibcMaxProvider.get()
        candidates +=
            base.resolve("${target.rustTargetTriple}.$glibcMax/release/${target.libraryFileName}")
    }
    candidates += base.resolve("${target.rustTargetTriple}/release/${target.libraryFileName}")
    return candidates.firstOrNull { it.isFile } ?: candidates.last()
}

fun parseRustExtraTargetIds(raw: String): List<Pair<String, String>> =
    raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { spec ->
            val dash = spec.lastIndexOf('-')
            require(dash > 0) { "Invalid chronos.rust.extraTargets entry: '$spec' (expected os-arch, e.g. linux-x86_64)" }
            spec.substring(0, dash) to spec.substring(dash + 1)
        }

val rustCompileTargetsProvider =
    providers.provider {
        val extraRaw =
            rustExtraTargetsPropertyProvider.orNull?.takeIf { it.isNotBlank() }
                ?: if (isTestServersGradleInvocation()) {
                    "linux-x86_64"
                } else {
                    ""
                }
        val extras =
            parseRustExtraTargetIds(extraRaw)
                .map { (osId, archId) ->
                    rustNativeTargets.firstOrNull { it.osId == osId && it.archId == archId }
                        ?: error(
                            "Unknown chronos.rust.extraTargets entry: $osId-$archId " +
                                "(supported: ${rustNativeTargets.joinToString { "${it.osId}-${it.archId}" }})"
                        )
                }
        linkedSetOf(hostRustTarget()).apply { addAll(extras) }.toList()
    }

val buildRustTargetTasks =
    rustNativeTargets.flatMap { target ->
        val slug = "${target.osId}_${target.archId}".replace('-', '_')
        val compileTarget = { rustCompileTargetsProvider.get().any { active -> active == target } }
        val ensureTask =
            tasks.register<Exec>("rustTargetAdd_$slug") {
                group = "build"
                description = "Installs Rust target ${target.rustTargetTriple} (nightly)."
                workingDir = rustProjectDir
                onlyIf {
                    !rustSkipBuildProvider.get() &&
                        compileTarget() &&
                        !useDockerForLinuxTarget(target)
                }
                environment("RUSTUP_TOOLCHAIN", "nightly")
                commandLine(rustupCmd, "target", "add", target.rustTargetTriple, "--toolchain", "nightly")
            }
        val nativeBuild =
            tasks.register<Exec>("buildRust_$slug") {
                group = "build"
                description = "Builds rust-pruner for ${target.rustTargetTriple}."
                dependsOn(ensureTask)
                workingDir = rustProjectDir
                environment("RUSTUP_TOOLCHAIN", "nightly")
                onlyIf {
                    !rustSkipBuildProvider.get() &&
                        compileTarget() &&
                        !useDockerForLinuxTarget(target)
                }
                commandLine(cargoCmd, "build", "--release", "--target", target.rustTargetTriple)
            }
        val dockerBuild =
            if (target.osId == "linux") {
                tasks.register("buildRust_${slug}_docker") {
                    group = "build"
                    description = "Builds rust-pruner for ${target.rustTargetTriple} in a Rust Docker container."
                    onlyIf { !rustSkipBuildProvider.get() && useDockerForLinuxTarget(target) }
                    doLast {
                        runDockerRustBuild(target)
                    }
                }
            } else {
                null
            }
        listOfNotNull(nativeBuild, dockerBuild)
    }

val buildRust =
    tasks.register("buildRust") {
        group = "build"
        description = "Builds rust-pruner native libraries for host (+ optional extra targets)."
        dependsOn(buildRustTargetTasks)
        onlyIf { !rustSkipBuildProvider.get() }
        doFirst {
            val compile = rustCompileTargetsProvider.get()
            val viaDocker = compile.filter { useDockerForLinuxTarget(it) }
            val dockerImage = rustLinuxDockerImageProvider.get()
            val glibcMax = rustLinuxGlibcMaxProvider.get()
            logger.lifecycle(
                "Rust compile targets: ${compile.joinToString { "${it.osId}-${it.archId} (${it.rustTargetTriple})" }}. " +
                    "Staging copies every built library from target/*/release/. " +
                    if (viaDocker.isNotEmpty()) {
                        "Linux via Docker ($dockerImage, glibc $glibcMax max): " +
                            viaDocker.joinToString { it.rustTargetTriple } +
                            "."
                    } else {
                        ""
                    }
            )
        }
    }

val stageRustPrunerNative =
    tasks.register("stageRustPrunerNative") {
        group = "build"
        description = "Stages rust-pruner native libs present under target/*/release/ as jar resources."
        dependsOn(buildRust)
        doLast {
            val outputRoot = rustPrunerOutputRoot.get().asFile
            outputRoot.deleteRecursively()
            outputRoot.mkdirs()
            val missing = mutableListOf<String>()
            val requireAllTargets = rustRequireAllTargetsProvider.get()

            for (target in rustNativeTargets) {
                val source = rustReleaseLibraryFile(target)
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
                    "Some rust-pruner targets were not staged: ${missing.joinToString(", ")}. " +
                        "Set -Pchronos.rust.requireAllTargets=true to fail when any target is missing."
                )
            }
            if (isTestServersGradleInvocation()) {
                val linuxLib = outputRoot.resolve("natives/linux-x86_64/librust_pruner.so")
                if (!linuxLib.isFile) {
                    val glibcMax = rustLinuxGlibcMaxProvider.get()
                    throw GradleException(
                        "testServers requires natives/linux-x86_64/librust_pruner.so in the mod jar. " +
                            "The Linux rust-pruner build did not produce a library under " +
                            "core/native/rust-pruner/target/x86_64-unknown-linux-gnu/release/ " +
                            "or core/native/rust-pruner/target/x86_64-unknown-linux-gnu.$glibcMax/release/. " +
                            "Ensure Docker is running, then run: ./gradlew clean testServers"
                    )
                }
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
val chronosCompileGroupsRoot: Map<String, Any> = run {
    val f = layout.projectDirectory.file("gradle/chronos-compile-groups.json").asFile
    require(f.exists()) { "Missing ${f.path}" }
    JsonSlurper().parseText(f.readText()) as Map<String, Any>
}

data class ChronosLoaderSpec(
    val variantPrefix: String,
    val configKey: String,
    val collectedKey: String,
    val slugSuffix: String,
)

@Suppress("UNCHECKED_CAST")
val chronosLoaderSpecs: List<ChronosLoaderSpec> = run {
    val gv = chronosCompileGroupsRoot["generateVariants"] as? Map<*, *>
        ?: error("gradle/chronos-compile-groups.json must define generateVariants")
    val loaders = gv["loaders"] as? List<*>
        ?: error("gradle/chronos-compile-groups.json must define generateVariants.loaders")
    val out = loaders.map { raw ->
        val m = raw as? Map<*, *> ?: error("generateVariants.loaders entries must be objects")
        val variantPrefix = m["variantPrefix"]?.toString()?.takeIf { it.isNotBlank() }
            ?: error("generateVariants.loaders entry missing variantPrefix")
        val configKey = m["configKey"]?.toString()?.takeIf { it.isNotBlank() }
            ?: error("generateVariants.loaders entry missing configKey")
        val collectedKey = m["collectedKey"]?.toString()?.takeIf { it.isNotBlank() }
            ?: error("generateVariants.loaders entry missing collectedKey")
        ChronosLoaderSpec(variantPrefix, configKey, collectedKey, m["slugSuffix"]?.toString() ?: "")
    }
    require(out.isNotEmpty()) { "generateVariants.loaders must not be empty" }
    out
}

val collectedKeyByVariantPrefix: Map<String, String> =
    chronosLoaderSpecs.associate { it.variantPrefix to it.collectedKey }

val chronosVariantPrefixAlt: String =
    chronosLoaderSpecs.map { it.variantPrefix }.distinct()
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }

val variantLineNameRegex = Regex("^($chronosVariantPrefixAlt)-line-(.+)$")
val variantBareNameRegex = Regex("^($chronosVariantPrefixAlt)-(.+)$")

@Suppress("UNCHECKED_CAST")
val jarTargetLabelByLineSlug: Map<String, String> = run {
    val groups = chronosCompileGroupsRoot["groups"] as? List<*> ?: return@run emptyMap()
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

@Suppress("UNCHECKED_CAST")
val jarArchiveTagByLoaderAndSlug: Map<String, Map<String, String>> = run {
    val groups = chronosCompileGroupsRoot["groups"] as? List<*> ?: return@run emptyMap()
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
        for (spec in chronosLoaderSpecs) {
            (gm[spec.configKey] as? Map<*, *>)?.get("archiveVersionTag")?.toString()?.takeIf { it.isNotBlank() }?.let {
                putTag(spec.collectedKey, slug + spec.slugSuffix, it)
            }
        }
    }
    out.mapValues { it.value.toMap() }
}

val chronosRustPrunerResources =
    rootProject.layout.buildDirectory.dir("generated/rust-pruner-resources")

/**
 * Forge 1.13–1.16 ModLauncher installs a package filter that refuses to load any
 * `com.google.*` class from a mod jar (so mods cannot replace Guava). Flattened
 * Google Drive API classes live under `com.google.api` / `auth` / `oauth`, so the
 * filter delegates to the parent loader, which does not have them >
 * ClassNotFoundException. Relocate those prefixes out of `com.google.*` after the
 * fat jar is built. Leave `com.google.common` / `com.google.gson` alone (Forge-provided).
 * Fabric JiJ / NeoForge jarJar do not need this. Yes this fucking sucks.
 */
val chronosGoogleRelocations =
    listOf(
        "com.google.api" to "com.magicjinn.chronos.repack.google.api",
        "com.google.auth" to "com.magicjinn.chronos.repack.google.auth",
        "com.google.oauth" to "com.magicjinn.chronos.repack.google.oauth",
    )

/** ModLauncher SKIPPACKAGES `com.google.` starts with Forge 1.13. LaunchClassLoader lines do not need relocate. */
fun forgeLineNeedsGoogleRelocate(projectName: String): Boolean {
    val match = Regex("""^forge(?:-line)?-1_(\d+)(?:_|$)""").find(projectName) ?: return false
    return match.groupValues[1].toInt() >= 13
}

fun relocateGooglePackagesInJar(inputJar: File, relocatorClasspath: Iterable<File>) {
    val tmp = File(inputJar.parentFile, "${inputJar.nameWithoutExtension}.relocating.jar")
    if (tmp.exists() && !tmp.delete())
        throw GradleException("Could not delete temp relocate jar: ${tmp.absolutePath}")
    
    val urls = relocatorClasspath.map { it.toURI().toURL() }.toTypedArray()
    java.net.URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { cl ->
        val relocationCl = cl.loadClass("me.lucko.jarrelocator.Relocation")
        val jarRelocatorCl = cl.loadClass("me.lucko.jarrelocator.JarRelocator")
        val relocationCtor =
            relocationCl.getConstructor(String::class.java, String::class.java)
        val relocations =
            ArrayList<Any>(
                chronosGoogleRelocations.map { (from, to) ->
                    relocationCtor.newInstance(from, to)
                },
            )
        val jarRelocatorCtor =
            jarRelocatorCl.getConstructor(
                File::class.java,
                File::class.java,
                Collection::class.java,
            )
        val relocator = jarRelocatorCtor.newInstance(inputJar, tmp, relocations)
        jarRelocatorCl.getMethod("run").invoke(relocator)
    }
    if (!inputJar.delete())
        throw GradleException("Could not replace jar after relocate: ${inputJar.absolutePath}")
    
    if (!tmp.renameTo(inputJar))
        throw GradleException(
            "Relocated jar written to ${tmp.absolutePath} but could not rename over ${inputJar.absolutePath}",
        )
}

configure(packableSubprojects) {
    val googleApiClientVersion =
        rootProject.findProperty("chronos.google.api.client.version") as String? ?: "2.7.2"
    val googleOauthClientVersion =
        rootProject.findProperty("chronos.google.oauth.client.version") as String? ?: "1.36.0"
    val googleDriveApiVersion =
        rootProject.findProperty("chronos.google.api.services.drive.version") as String?
            ?: "v3-rev20230822-2.0.0"
    val googleHttpClientVersion =
        rootProject.findProperty("chronos.google.http.client.version") as String? ?: "1.45.2"
    val googleAuthLibraryVersion =
        rootProject.findProperty("chronos.google.auth.library.version") as String? ?: "1.30.0"
    val opencensusVersion =
        rootProject.findProperty("chronos.opencensus.version") as String? ?: "0.31.1"
    val grpcApiVersion =
        rootProject.findProperty("chronos.grpc.api.version") as String? ?: "1.68.2"
    // Direct + nestable transitives. Fabric `include` / Forge flatten do not nest transitives.
    // Nest grpc-api (not grpc-context): context jar is empty since 1.57 and only depends on api.
    val googleDriveCoords =
        listOf(
            "com.google.api-client:google-api-client:$googleApiClientVersion",
            "com.google.oauth-client:google-oauth-client-jetty:$googleOauthClientVersion",
            "com.google.oauth-client:google-oauth-client-java6:$googleOauthClientVersion",
            "com.google.oauth-client:google-oauth-client:$googleOauthClientVersion",
            "com.google.apis:google-api-services-drive:$googleDriveApiVersion",
            "com.google.http-client:google-http-client:$googleHttpClientVersion",
            "com.google.http-client:google-http-client-gson:$googleHttpClientVersion",
            "com.google.http-client:google-http-client-apache-v2:$googleHttpClientVersion",
            "com.google.auth:google-auth-library-oauth2-http:$googleAuthLibraryVersion",
            "com.google.auth:google-auth-library-credentials:$googleAuthLibraryVersion",
            "io.opencensus:opencensus-api:$opencensusVersion",
            "io.opencensus:opencensus-contrib-http-util:$opencensusVersion",
            "io.grpc:grpc-api:$grpcApiVersion",
        )

    /**
     * Minecraft / NeoForge already ship these (often with `{strictly ...}`). Pulling Google's
     * newer transitives makes compileClasspath unsatisfiable on NeoForge 1.20+.
     * Annotation jars (jsr305 / error_prone / j2objc) must not be flattened into Forge mods:
     * JPMS rejects split packages such as javax.annotation on 1.17+.
     */
    fun org.gradle.api.artifacts.ExternalModuleDependency.excludeMinecraftProvidedGoogleTransitives() {
        exclude(group = "com.google.guava")
        exclude(group = "com.google.code.gson")
        exclude(group = "com.google.code.findbugs")
        exclude(group = "com.google.errorprone")
        exclude(group = "com.google.j2objc")
        exclude(group = "commons-codec")
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "commons-logging")
    }

    pluginManager.withPlugin("java") {
        // NeoForge variants already jarJar(implementation(...)) Google Drive in their buildscript.
        if (!name.startsWith("neoforge-")) {
            dependencies {
                for (coord in googleDriveCoords) {
                    "implementation"(coord) { excludeMinecraftProvidedGoogleTransitives() }
                }
            }
        }
    }
    afterEvaluate {
        // Fabric JiJ + legacy Forge flatten need configurations created by the variant buildscript.
        if (name.startsWith("fabric-") && configurations.findByName("include") != null) {
            dependencies {
                for (coord in googleDriveCoords) {
                    "include"(coord) { excludeMinecraftProvidedGoogleTransitives() }
                }
            }
        }
        if (configurations.findByName("chronosEmbedded") != null) {
            // Template chronosEmbedded deps often omit excludes. Strip Forge-provided / annotation
            // Google libs so flatten does not embed them (JPMS split-package crash on Forge 1.17+).
            configurations.named("chronosEmbedded").configure {
                exclude(mapOf("group" to "com.google.guava"))
                exclude(mapOf("group" to "com.google.code.gson"))
                exclude(mapOf("group" to "com.google.code.findbugs"))
                exclude(mapOf("group" to "com.google.errorprone"))
                exclude(mapOf("group" to "com.google.j2objc"))
                exclude(mapOf("group" to "commons-codec"))
                exclude(mapOf("group" to "commons-logging"))
                exclude(mapOf("group" to "org.apache.httpcomponents"))
            }
            dependencies {
                for (coord in googleDriveCoords) {
                    "chronosEmbedded"(coord) { excludeMinecraftProvidedGoogleTransitives() }
                }
            }
            tasks.withType<Jar>().configureEach {
                if (name == "jar" || name == "remapJar") {
                    exclude("com/google/common/**")
                    exclude("com/google/gson/**")
                    exclude("javax/annotation/**")
                    exclude("com/google/errorprone/**")
                    exclude("com/google/j2objc/**")
                    // Minecraft/Forge already ship these. Embedding splits JPMS packages on 1.17+.
                    exclude("org/apache/commons/**")
                    exclude("org/apache/http/**")
                }
            }
        }
        tasks.matching { it.name == "build" || it.name == "jar" || it.name == "remapJar" }.configureEach {
            dependsOn(rootProject.tasks.named("stageRustPrunerNative"))
        }

        // Forge 1.13+ ModLauncher SKIPPACKAGES blocks com.google.* from mod jars.
        // Relocate after flatten (chronosEmbedded on Forge lines that ship Drive in the jar).
        if (forgeLineNeedsGoogleRelocate(name) && configurations.findByName("chronosEmbedded") != null) {
            val packagingTaskName =
                if (tasks.findByName("remapJar") != null) "remapJar" else "jar"
            val packagingTask = tasks.named(packagingTaskName, Jar::class.java)
            val relocatorConf =
                configurations.create("chronosJarRelocator") {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    isVisible = false
                }
            dependencies.add(relocatorConf.name, "me.lucko:jar-relocator:1.5")
            val relocateGoogle =
                tasks.register("relocateGoogleDrivePackages") {
                    group = "chronos"
                    description =
                        "Relocate Google API packages out of com.google.* so Forge ModLauncher loads them"
                    dependsOn(packagingTask)
                    val archive = packagingTask.flatMap { it.archiveFile }
                    inputs.files(relocatorConf)
                    inputs.file(archive)
                    outputs.file(archive)
                    doLast {
                        relocateGooglePackagesInJar(archive.get().asFile, relocatorConf)
                    }
                }
            packagingTask.configure { finalizedBy(relocateGoogle) }
            tasks.named("build").configure { dependsOn(relocateGoogle) }
        }
    }
    tasks.withType<ProcessResources>().configureEach {
        dependsOn(rootProject.tasks.named("stageRustPrunerNative"))
        inputs.dir(chronosRustPrunerResources).withPropertyName("chronosRustPrunerResources")
        from(chronosRustPrunerResources)
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
    tasks.withType<Jar>().configureEach {
        if (name != "jar" && name != "remapJar") {
            return@configureEach
        }
        dependsOn(rootProject.tasks.named("stageRustPrunerNative"))
        inputs.dir(chronosRustPrunerResources).withPropertyName("chronosRustPrunerResources")
        from(chronosRustPrunerResources)
        // Embedded Google libs ship Multi-Release / JPMS entries. Forge ASM 5.x on 1.7–1.12
        // fails ClassReader on them and drops the whole mod as a "corrupt zip".
        exclude("META-INF/versions/**")
        exclude("**/module-info.class")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

val generateVariants =
    tasks.register("generateVariants") {
        group = "chronos"
        description = "Cleans and regenerates variants/<compileGroup> via Java tooling."
        dependsOn(cleanVariants)
        dependsOn(":tooling:runGenerateVariants")
    }

val cleanVariants =
    tasks.register("cleanVariants") {
        group = "chronos"
        description = "Force-deletes the variants/ folder via Java tooling, retries to handle locked files (e.g. on Windows)."
        dependsOn(":tooling:runCleanVariants")
    }

tasks.register("cleanUniminedCache") {
    group = "chronos"
    description =
        "Deletes global (~/.gradle/unimined) and per-variant (.gradle/unimined) Unimined caches. " +
            "Use when Forge remapJar fails with Error reading file ... srg2mcp.jar, or configure fails with Gson MalformedJsonException."
    doLast {
        val global = File(System.getProperty("user.home"), ".gradle/unimined")
        if (global.exists()) {
            global.deleteRecursively()
            logger.lifecycle("Deleted Unimined global cache: ${global.absolutePath}")
        } else {
            logger.lifecycle("Unimined global cache not present: ${global.absolutePath}")
        }
        val variantsRoot = layout.projectDirectory.dir("variants").asFile
        if (variantsRoot.isDirectory) {
            variantsRoot.listFiles()?.filter { it.isDirectory }?.forEach { group ->
                group.listFiles()?.filter { it.isDirectory }?.forEach { project ->
                    val local = File(project, ".gradle/unimined")
                    if (local.exists()) {
                        local.deleteRecursively()
                        logger.lifecycle("Deleted Unimined local cache: ${local.absolutePath}")
                    }
                }
            }
        }
    }
}

fun normalizeCollectedLoader(loader: String): String =
    collectedKeyByVariantPrefix[loader] ?: loader

fun collectedJarPrefix(variantProjectName: String): String {
    val lineMatch = variantLineNameRegex.matchEntire(variantProjectName)
    if (lineMatch != null) {
        val loader = normalizeCollectedLoader(lineMatch.groupValues[1])
        val slug = lineMatch.groupValues[2]
        return "$loader-${variantSlugToVersionLabel(loader, slug)}"
    }
    val bareMatch = variantBareNameRegex.matchEntire(variantProjectName)
    if (bareMatch != null) {
        val loader = normalizeCollectedLoader(bareMatch.groupValues[1])
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

val collectAllJars =
    tasks.register<Copy>("collectAllJars") {
        group = "build"
    description =
        "Copies each variant production JAR into root build/libs (renamed). Variant build/libs are left intact so Gradle incremental builds keep valid remap/jar outputs."
    into(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(stageRustPrunerNative)
    dependsOn(packableSubprojects.map { it.tasks.named("build") })
    val modId =
        (findProperty("chronos.mod.id") ?: "chronosbackups").toString().lowercase()
    val modVersion = (findProperty("chronos.mod.version") ?: "0.0.0").toString()
    doFirst {
        val dest = layout.buildDirectory.dir("libs").get().asFile
        dest.mkdirs()
        dest
            .listFiles { f ->
                f.isFile && f.name.startsWith("$modId-") && f.name.endsWith(".jar")
            }?.forEach { stale ->
                if (!stale.delete()) {
                    throw GradleException("Could not remove stale collected jar: ${stale.absolutePath}")
                }
            }
    }
    for (sub in packableSubprojects) {
        from(sub.layout.buildDirectory.dir("libs")) {
            // Only the current mod version; stale jars from prior version bumps stay in variant build/libs.
            include("*-$modVersion.jar")
            rename { collectedJarName(modId, modVersion, sub.name) }
        }
    }
    }

fun collectedChronosJars(libsDir: java.io.File, modId: String, modVersion: String): Array<java.io.File> {
    val versionToken = "-$modVersion-"
    return libsDir.listFiles { f ->
        f.isFile &&
            f.name.endsWith(".jar") &&
            f.name.startsWith("$modId-") &&
            f.name.contains(versionToken)
    } ?: emptyArray()
}

fun zipEntryNames(jar: java.io.File): Set<String> =
    java.util.zip.ZipFile(jar).use { zip ->
        zip.entries().asSequence().map { it.name }.toSet()
    }

fun nestedJarPresent(entries: Set<String>, nestDir: String, artifactPrefix: String): Boolean =
    entries.any { name ->
        name.startsWith(nestDir) &&
            name.substringAfterLast('/').startsWith(artifactPrefix) &&
            name.endsWith(".jar")
    }

/** True when a nested jar under {@code nestDir} matching {@code artifactPrefix} contains {@code classEntry}. */
fun nestedJarContainsClass(
    jar: java.io.File,
    nestDir: String,
    artifactPrefix: String,
    classEntry: String,
): Boolean {
    java.util.zip.ZipFile(jar).use { zip ->
        val nested =
            zip.entries().asSequence().firstOrNull { entry ->
                !entry.isDirectory &&
                    entry.name.startsWith(nestDir) &&
                    entry.name.substringAfterLast('/').startsWith(artifactPrefix) &&
                    entry.name.endsWith(".jar")
            } ?: return false
        zip.getInputStream(nested).use { input ->
            java.util.zip.ZipInputStream(input).use { inner ->
                var e = inner.nextEntry
                while (e != null) {
                    if (e.name == classEntry) return true
                    e = inner.nextEntry
                }
            }
        }
    }
    return false
}

/** True when the collected jar's MC target starts at Forge 1.13+ */
fun forgeMcTargetNeedsGoogleRelocate(mcTarget: String): Boolean {
    val match = Regex("""^1\.(\d+)""").find(mcTarget) ?: return false
    return match.groupValues[1].toInt() >= 13
}

fun parseCollectedJar(modId: String, modVersion: String, fileName: String): Pair<String, String>? {
    val prefix = "$modId-"
    val suffixMatch =
        Regex("""-(fabric|neoforge|forge|plugin)\.jar$""").find(fileName) ?: return null
    if (!fileName.startsWith(prefix) || !fileName.endsWith(".jar")) return null
    val loader = suffixMatch.groupValues[1]
    val mid =
        fileName.removePrefix(prefix).removeSuffix("-${loader}.jar")
    val versionToken = "-$modVersion"
    if (!mid.endsWith(versionToken)) return null
    val mcTarget = mid.removeSuffix(versionToken)
    return loader to mcTarget
}

val verifyTestServerNativeJars =
    tasks.register("verifyTestServerNativeJars") {
        group = "verification"
        description = "Fails if collected mod jars are missing natives/linux-x86_64/librust_pruner.so."
        dependsOn(collectAllJars)
        onlyIf { isTestServersGradleInvocation() }
        doLast {
            val libsDir = layout.buildDirectory.dir("libs").get().asFile
            if (!libsDir.isDirectory) {
                throw GradleException("Missing ${libsDir.absolutePath}; run buildAll first.")
            }
            val modId =
                (findProperty("chronos.mod.id") ?: "chronosbackups").toString().lowercase()
            val modVersion = (findProperty("chronos.mod.version") ?: "0.0.0").toString()
            val jars = collectedChronosJars(libsDir, modId, modVersion)
            if (jars.isEmpty()) {
                throw GradleException("No chronosbackups jars in ${libsDir.absolutePath}.")
            }
            val entry = "natives/linux-x86_64/librust_pruner.so"
            val missing =
                jars.filter { jar ->
                    java.util.zip.ZipFile(jar).use { zip -> zip.getEntry(entry) == null }
                }
            if (missing.isNotEmpty()) {
                val names = missing.map { it.name }.sorted().joinToString(", ")
                throw GradleException(
                    "testServers mod jar(s) missing $entry: $names. " +
                        "Run ./gradlew clean prepareTestServers with Docker running so the Linux rust-pruner is built and packaged."
                )
            }
        }
    }

val verifyGoogleDriveJarContents =
    tasks.register("verifyGoogleDriveJarContents") {
        group = "verification"
        description =
            "Fails if collected jars are missing Google Drive packaging (JiJ / flattened / relocated) " +
                "or Forge jars embed JPMS-conflicting packages (Guava/Gson/jsr305/commons)."
        dependsOn(collectAllJars)
        doLast {
            val libsDir = layout.buildDirectory.dir("libs").get().asFile
            if (!libsDir.isDirectory) {
                throw GradleException("Missing ${libsDir.absolutePath}; run buildAll / collectAllJars first.")
            }
            val modId =
                (findProperty("chronos.mod.id") ?: "chronosbackups").toString().lowercase()
            val modVersion = (findProperty("chronos.mod.version") ?: "0.0.0").toString()
            val jars = collectedChronosJars(libsDir, modId, modVersion).sortedBy { it.name }
            if (jars.isEmpty()) {
                throw GradleException("No chronosbackups jars in ${libsDir.absolutePath}.")
            }

            val nestedArtifacts =
                listOf(
                    "google-http-client-",
                    "google-api-client-",
                    "google-oauth-client-",
                    "opencensus-api-",
                    "grpc-api-",
                )
            val flatHttpInit = "com/google/api/client/http/HttpRequestInitializer.class"
            val relocatedHttpInit =
                "com/magicjinn/chronos/repack/google/api/client/http/HttpRequestInitializer.class"
            val flatOpenCensusSetter =
                "io/opencensus/trace/propagation/TextFormat\$Setter.class"
            val flatGrpcContext = "io/grpc/Context.class"
            // Forge already ships these. Embedding them splits JPMS packages on 1.17+.
            val forgeForbiddenPrefixes =
                listOf(
                    "com/google/common/" to "Forge-provided Guava / failureaccess",
                    "com/google/gson/" to "Forge-provided Gson",
                    "javax/annotation/" to "jsr305; splits with server module",
                    "com/google/errorprone/" to "error_prone_annotations",
                    "com/google/j2objc/" to "j2objc-annotations",
                    "org/apache/commons/" to "Forge-provided commons-codec/logging",
                    "org/apache/http/" to "Forge-provided httpcomponents",
                )

            val problems = mutableListOf<String>()
            for (jar in jars) {
                val parsed = parseCollectedJar(modId, modVersion, jar.name)
                if (parsed == null) {
                    problems += "${jar.name}: could not parse loader/mcTarget from name"
                    continue
                }
                val (loader, mcTarget) = parsed
                val entries = zipEntryNames(jar)
                val missing = mutableListOf<String>()

                when (loader) {
                    "fabric" -> {
                        val nest = "META-INF/jars/"
                        for (prefix in nestedArtifacts) {
                            if (!nestedJarPresent(entries, nest, prefix)) {
                                missing += "$nest$prefix*.jar"
                            }
                        }
                        if (!nestedJarContainsClass(jar, nest, "grpc-api-", flatGrpcContext))
                            missing += "${nest}grpc-api-*.jar!$flatGrpcContext"
                    }
                    "neoforge" -> {
                        val nest = "META-INF/jarjar/"
                        for (prefix in nestedArtifacts) {
                            if (!nestedJarPresent(entries, nest, prefix)) {
                                missing += "$nest$prefix*.jar"
                            }
                        }
                        if (!nestedJarContainsClass(jar, nest, "grpc-api-", flatGrpcContext))
                            missing += "${nest}grpc-api-*.jar!$flatGrpcContext"
                    }
                    "plugin" -> {
                        if (flatHttpInit !in entries) missing += flatHttpInit
                        if (flatOpenCensusSetter !in entries) missing += flatOpenCensusSetter
                        if (flatGrpcContext !in entries) missing += flatGrpcContext
                    }
                    "forge" -> {
                        val hasFlat = flatHttpInit in entries
                        val hasRelocated = relocatedHttpInit in entries
                        if (forgeMcTargetNeedsGoogleRelocate(mcTarget)) {
                            when {
                                hasRelocated -> Unit
                                hasFlat ->
                                    missing +=
                                        "$relocatedHttpInit (still under com.google.*; ModLauncher will skip it)"
                                else ->
                                    missing +=
                                        "Google Drive stack not packaged (need flattened+relocated $relocatedHttpInit)"
                            }
                            // OpenCensus / grpc stay under their original packages (not relocated).
                            if (flatOpenCensusSetter !in entries) missing += flatOpenCensusSetter
                            if (flatGrpcContext !in entries) missing += flatGrpcContext
                        } else {
                            if (!hasFlat) missing += flatHttpInit
                            if (flatOpenCensusSetter !in entries) missing += flatOpenCensusSetter
                            if (flatGrpcContext !in entries) missing += flatGrpcContext
                        }
                        for ((prefix, reason) in forgeForbiddenPrefixes) {
                            if (entries.any { it.startsWith(prefix) }) {
                                missing += "must not embed $prefix* ($reason)"
                            }
                        }
                    }
                    else -> problems += "${jar.name}: unknown loader '$loader'"
                }

                if (missing.isNotEmpty()) {
                    problems += "${jar.name}: missing ${missing.joinToString(", ")}"
                }
            }

            if (problems.isNotEmpty()) {
                throw GradleException(
                    "Google Drive jar content check failed (${problems.size}):\n" +
                        problems.joinToString("\n") { "  - $it" },
                )
            }
            logger.lifecycle(
                "verifyGoogleDriveJarContents: OK (${jars.size} jars have Drive packaging).",
            )
        }
    }

tasks.register("buildAll") {
    group = "build"
    description =
        "Runs :core:test, builds every included variant subproject, collects jars, " +
            "and verifies Google Drive packaging."
    dependsOn(":core:test")
    dependsOn(collectAllJars)
    dependsOn(verifyGoogleDriveJarContents)
}

val prepareTestServers =
    tasks.register("prepareTestServers") {
        group = "verification"
        description = "Builds rust-pruner (Docker Linux on Windows), stages natives, then buildAll."
        dependsOn(buildRust)
        dependsOn(stageRustPrunerNative)
        dependsOn("buildAll")
        dependsOn(verifyTestServerNativeJars)
        dependsOn(verifyGoogleDriveJarContents)
    }

tasks.register("testServers") {
    group = "verification"
    description = "Docker integration tests. Requires Docker; builds deps automatically (see prepareTestServers)."
    dependsOn(":tooling:runTestServers")
}

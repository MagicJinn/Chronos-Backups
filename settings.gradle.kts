import groovy.json.JsonSlurper
import java.io.File
import org.gradle.internal.os.OperatingSystem

pluginManagement {
    repositories {
        // Resolve plugin dependencies (Kotlin, Guava, etc.) from Central as well as loader mirrors.
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.wagyourtail.xyz/releases")
        maven("https://repo.papermc.io/repository/maven-public/")
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "chronos-backups"

apply(from = "gradle/chronos-unimined-retry.gradle")

include(":core")
include(":tooling")

val compileGroupsFile = file("gradle/chronos-compile-groups.json")
if (!compileGroupsFile.exists()) {
    throw GradleException("Missing ${compileGroupsFile.path}. Run: ./gradlew generateVariants")
}

@Suppress("UNCHECKED_CAST")
val chronosVariantPrefixes: List<String> = run {
    val root = JsonSlurper().parseText(compileGroupsFile.readText()) as Map<String, Any>
    val gv = root["generateVariants"] as? Map<*, *>
        ?: throw GradleException("${compileGroupsFile.path} must define generateVariants")
    val loaders = gv["loaders"] as? List<*>
        ?: throw GradleException("${compileGroupsFile.path} must define generateVariants.loaders")
    val prefixes = loaders.map { raw ->
        val m = raw as? Map<*, *>
            ?: throw GradleException("generateVariants.loaders entries must be objects")
        m["variantPrefix"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw GradleException("generateVariants.loaders entry missing variantPrefix")
    }.distinct()
    if (prefixes.isEmpty()) {
        throw GradleException("generateVariants.loaders must not be empty")
    }
    prefixes
}

fun isChronosVariantProjectName(name: String): Boolean =
    chronosVariantPrefixes.any { name.startsWith("$it-") }

fun isChronosForgeVariantProjectName(name: String): Boolean =
    chronosVariantPrefixes.contains("forge") && name.startsWith("forge-")

val requestedTasks = gradle.startParameter.taskNames

fun taskBareName(taskName: String): String = taskName.substringAfterLast(':')

fun taskProjectName(taskName: String): String? {
    val trimmed = taskName.trim()
    if (!trimmed.contains(':')) {
        return null
    }
    val segments = trimmed.split(':').filter { it.isNotEmpty() }
    return if (segments.size >= 2) segments[segments.size - 2] else null
}

fun taskNeedsVariantsRoot(taskName: String): Boolean {
    val bare = taskBareName(taskName)
    return bare == "buildAll" ||
        bare == "collectAllJars" ||
        bare == "prepareTestServers" ||
        bare == "testServers" ||
        bare == "runTestServers" ||
        bare == "verifyTestServerNativeJars"
}

fun taskTargetsVariantProject(taskName: String): Boolean {
    val projectName = taskProjectName(taskName) ?: return false
    return isChronosVariantProjectName(projectName)
}

fun taskRequiresExistingVariants(taskName: String): Boolean {
    val bare = taskBareName(taskName)
    return taskNeedsVariantsRoot(taskName) ||
        taskTargetsVariantProject(taskName) ||
        bare == "generateVariants" ||
        bare == "runGenerateVariants"
}

val bootstrapVariantGeneration = requestedTasks.any { taskName ->
    val bare = taskBareName(taskName)
    bare == "generateVariants" || bare == "runGenerateVariants"
}
val cleanVariantsRequested = requestedTasks.any { taskName ->
    val bare = taskBareName(taskName)
    bare == "cleanVariants" || bare == "runCleanVariants"
}
val rustOnlyBuild = requestedTasks.isNotEmpty() && requestedTasks.all { taskName ->
    val bare = taskBareName(taskName)
    bare == "buildRust" || bare.startsWith("buildRust_") || bare.startsWith("rustTargetAdd_")
}
/** Explicit {@code :core:…} / {@code :tooling:…} tasks do not need loader variants on the project graph. */
fun taskTargetsCoreOrTooling(taskName: String): Boolean {
    val projectName = taskProjectName(taskName) ?: return false
    return projectName == "core" || projectName == "tooling"
}
val coreToolingOnlyBuild =
    requestedTasks.isNotEmpty() && requestedTasks.all { taskTargetsCoreOrTooling(it) }
val skipVariantIncludes = rustOnlyBuild || coreToolingOnlyBuild
val variantsRootNeeded = requestedTasks.any { taskNeedsVariantsRoot(it) }

fun chronosVariantGenerationSkipRequested(): Boolean {
    val raw = System.getenv("CHRONOS_VARIANT_GENERATION") ?: return false
    val v = raw.trim().lowercase()
    return v == "skip" || v == "0" || v == "false" || v == "off"
}

fun runGenerateVariantsBootstrap() {
    val wrapperPath = if (OperatingSystem.current().isWindows) "gradlew.bat" else "gradlew"
    val wrapper = file(wrapperPath)
    if (!wrapper.exists()) {
        return
    }
    val command = mutableListOf(wrapper.absolutePath, "generateVariants", "--quiet", "--no-daemon")
    val process = ProcessBuilder(command)
        .directory(rootDir)
        .redirectErrorStream(true)
        .apply {
            environment()["CHRONOS_VARIANT_GENERATION"] = "skip"
        }
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    if (exit != 0) {
        throw GradleException("Variant generation failed during settings evaluation.\n$output")
    }
}

val variantsRoot = file("variants")
if (!chronosVariantGenerationSkipRequested() && !cleanVariantsRequested && variantsRootNeeded) {
    runGenerateVariantsBootstrap()
}

fun File.directoriesSorted(): List<File> =
    listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

if (!variantsRoot.isDirectory) {
    val requiresVariants =
        requestedTasks.isNotEmpty() &&
            requestedTasks.any { taskRequiresExistingVariants(it) } &&
            !skipVariantIncludes &&
            !cleanVariantsRequested
    if (requiresVariants && !bootstrapVariantGeneration) {
        throw GradleException(
            "Missing ${variantsRoot.path}. Run: ./gradlew generateVariants",
        )
    }
} else if (!cleanVariantsRequested && !skipVariantIncludes) {
    var includesForgeLine = false
    for (groupDir in variantsRoot.directoriesSorted()) {
        for (projectDir in groupDir.directoriesSorted()) {
            val name = projectDir.name
            if (isChronosVariantProjectName(name)) {
                include(":$name")
                project(":$name").projectDir = projectDir
                if (isChronosForgeVariantProjectName(name)) {
                    includesForgeLine = true
                }
            }
        }
    }
    if (includesForgeLine && chronosSerializeForgeConfigure()) {
        // Parallel configure races on ~/.gradle/unimined (useGlobalCache=true on every Forge variant).
        gradle.startParameter.isParallelProjectExecutionEnabled = false
        logger.lifecycle(
            "[Chronos] Serializing Gradle project configuration for Forge variants " +
                "(set chronos.gradle.serializeForgeConfigure=false to disable).",
        )
    }
} else if (coreToolingOnlyBuild) {
    logger.lifecycle("[Chronos] Skipping loader variant includes (core/tooling-only task selection).")
}

fun chronosSerializeForgeConfigure(): Boolean {
    System.getenv("CHRONOS_SERIALIZE_FORGE_CONFIGURE")?.let { raw ->
        val v = raw.trim().lowercase()
        return !(v == "skip" || v == "0" || v == "false" || v == "off")
    }
    val propsFile = file("gradle.properties")
    if (propsFile.isFile) {
        propsFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("chronos.gradle.serializeForgeConfigure=")) {
                val v = trimmed.substringAfter('=').trim().lowercase()
                return v == "true" || v == "1" || v == "yes" || v == "on"
            }
        }
    }
    return true
}

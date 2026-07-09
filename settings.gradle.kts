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
    return projectName.startsWith("fabric-") ||
        projectName.startsWith("fabric-line-") ||
        projectName.startsWith("forge-") ||
        projectName.startsWith("forge-line-") ||
        projectName.startsWith("neoforge-") ||
        projectName.startsWith("neoforge-line-") ||
        projectName.startsWith("paper-") ||
        projectName.startsWith("paper-line-")
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

val compileGroupsFile = file("gradle/chronos-compile-groups.json")
if (!compileGroupsFile.exists()) {
    throw GradleException("Missing ${compileGroupsFile.path}. Run: ./gradlew generateVariants")
}

fun File.directoriesSorted(): List<File> =
    listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

if (!variantsRoot.isDirectory) {
    val requiresVariants =
        requestedTasks.isNotEmpty() &&
            requestedTasks.any { taskRequiresExistingVariants(it) } &&
            !rustOnlyBuild &&
            !cleanVariantsRequested
    if (requiresVariants && !bootstrapVariantGeneration) {
        throw GradleException(
            "Missing ${variantsRoot.path}. Run: ./gradlew generateVariants",
        )
    }
} else if (!cleanVariantsRequested) {
    var includesForgeLine = false
    for (groupDir in variantsRoot.directoriesSorted()) {
        for (projectDir in groupDir.directoriesSorted()) {
            val name = projectDir.name
            if (name.startsWith("fabric-") || name.startsWith("fabric-line-") || name.startsWith("neoforge-") || name.startsWith("forge-") || name.startsWith("paper-") || name.startsWith("paper-line-")) {
                include(":$name")
                project(":$name").projectDir = projectDir
                if (name.startsWith("forge-line-") || name.startsWith("forge-")) {
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

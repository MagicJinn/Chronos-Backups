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
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "chronos-backup"

include(":core")
include(":tooling")

val requestedTasks = gradle.startParameter.taskNames
val bootstrapVariantGeneration = requestedTasks.any { taskName ->
    taskName == "generateVariants" || taskName.endsWith(":generateVariants")
}
val cleanVariantsRequested = requestedTasks.any { taskName ->
    val bare = taskName.substringAfterLast(':')
    bare == "cleanVariants" || bare == "runCleanVariants"
}
val rustOnlyBuild = requestedTasks.isNotEmpty() && requestedTasks.all { taskName ->
    val bare = taskName.substringAfterLast(':')
    bare == "buildRust" || bare.startsWith("buildRust_") || bare.startsWith("rustTargetAdd_")
}

fun chronosVariantGenerationSkipRequested(): Boolean {
    val raw = System.getenv("CHRONOS_VARIANT_GENERATION") ?: return false
    val v = raw.trim().lowercase()
    return v == "skip" || v == "0" || v == "false" || v == "off"
}

if (!chronosVariantGenerationSkipRequested() && !cleanVariantsRequested) {
    val wrapperPath = if (OperatingSystem.current().isWindows) "gradlew.bat" else "gradlew"
    val wrapper = file(wrapperPath)
    if (wrapper.exists()) {
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
            throw GradleException("Automatic variant generation failed during settings evaluation.\n$output")
        }
    }
}

val compileGroupsFile = file("gradle/chronos-compile-groups.json")
if (!compileGroupsFile.exists()) {
    throw GradleException("Missing ${compileGroupsFile.path}. Run: ./gradlew generateVariants")
}

fun File.directoriesSorted(): List<File> =
    listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

val variantsRoot = file("variants")
if (!variantsRoot.isDirectory) {
    if (!bootstrapVariantGeneration && !rustOnlyBuild && !cleanVariantsRequested) {
        throw GradleException(
            "Missing ${variantsRoot.path}. Run: ./gradlew generateVariants",
        )
    }
} else if (!cleanVariantsRequested) {
    for (groupDir in variantsRoot.directoriesSorted()) {
        for (projectDir in groupDir.directoriesSorted()) {
            val name = projectDir.name
            if (name.startsWith("fabric-") || name.startsWith("fabric-line-") || name.startsWith("neoforge-") || name.startsWith("forge-")) {
                include(":$name")
                project(":$name").projectDir = projectDir
            }
        }
    }
}

import groovy.json.JsonSlurper
import java.io.File
import org.gradle.internal.os.OperatingSystem

pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.wagyourtail.xyz/releases")
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "chronos-backup"

include(":core")
include(":tooling")

if (System.getenv("CHRONOS_SKIP_VARIANT_AUTOGEN") != "1") {
    val wrapperPath = if (OperatingSystem.current().isWindows) "gradlew.bat" else "gradlew"
    val wrapper = file(wrapperPath)
    if (wrapper.exists()) {
        val command = mutableListOf(wrapper.absolutePath, "generateVariantProjects", "--quiet", "--no-daemon")
        val process = ProcessBuilder(command)
            .directory(rootDir)
            .redirectErrorStream(true)
            .apply {
                environment()["CHRONOS_SKIP_VARIANT_AUTOGEN"] = "1"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException("Automatic variant generation failed during settings evaluation.\n$output")
        }
    }
}

val versionsFile = file("gradle/chronos-versions.json")
if (!versionsFile.exists()) {
    throw GradleException("Missing ${versionsFile.path}. Run: ./gradlew generateVariantProjects")
}

@Suppress("UNCHECKED_CAST")
val chronosVersions = JsonSlurper().parseText(versionsFile.readText()) as List<Map<String, Any>>

fun File.directoriesSorted(): List<File> =
    listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

val variantsRoot = file("variants")
if (!variantsRoot.isDirectory) {
    throw GradleException(
        "Missing ${variantsRoot.path}. Run: ./gradlew generateVariantProjects",
    )
}

for (groupDir in variantsRoot.directoriesSorted()) {
    for (projectDir in groupDir.directoriesSorted()) {
        val name = projectDir.name
        if (name.startsWith("fabric-") || name.startsWith("fabric-line-") || name.startsWith("neoforge-") || name.startsWith("forge-")) {
            include(":$name")
            project(":$name").projectDir = projectDir
        }
    }
}

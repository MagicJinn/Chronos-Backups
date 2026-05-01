import org.gradle.internal.os.OperatingSystem
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

val forgeProjectDir = layout.projectDirectory.dir("forge")
val forgeGradleJavaHome = run {
    val gradleJdksDir = File(System.getProperty("user.home"), ".gradle/jdks")
    val jdk21Home = gradleJdksDir
        .listFiles()
        ?.firstOrNull { it.isDirectory && it.name.contains("-21-") }
        ?.absolutePath

    // Prefer a Java 21 runtime for the Forge Gradle 8.12 subprocess.
    jdk21Home
        ?: System.getenv("JAVA21_HOME")
        ?: System.getenv("JDK21_HOME")
}

tasks.register<Exec>("buildForge") {
    group = "build"
    description = "Builds the standalone Forge shell (Gradle 8.12 + Unimined)."
    workingDir(forgeProjectDir)
    val forgeWrapper = if (OperatingSystem.current().isWindows) {
        forgeProjectDir.file("gradlew.bat").asFile.absolutePath
    } else {
        forgeProjectDir.file("gradlew").asFile.absolutePath
    }
    val args = mutableListOf(forgeWrapper)
    if (forgeGradleJavaHome != null) {
        args.add("-Dorg.gradle.java.home=$forgeGradleJavaHome")
    }
    args.addAll(listOf("clean", "jar", "--no-daemon"))
    commandLine(args)
}

val packableSubprojects = subprojects.filter { it.path != ":core" }

subprojects {
    tasks.withType<ProcessResources>().configureEach {
        val projectName = project.name
        if (projectName.startsWith("fabric-")) {
            from(rootProject.layout.projectDirectory.file("icon.png")) {
                into("assets/chronosbackup")
            }
        } else if (projectName.startsWith("neoforge-")) {
            from(rootProject.layout.projectDirectory.file("icon.png")) {
                into("assets/chronosbackup")
            }
        }
    }
}

tasks.register("generateVariantProjects") {
    group = "chronos"
    description = "Generates variants/<compileGroup> via Java tooling."
    dependsOn(":tooling:runGenerateVariantProjects")
}

tasks.register("smokeTestServers") {
    group = "verification"
    description = "Runs dedicated server smoke tests via Java tooling."
    dependsOn(":tooling:runSmokeTestServers")
}

val cleanCollectedLibs = tasks.register<Delete>("cleanCollectedLibs") {
    group = "build"
    description = "Deletes root build/libs before collectAllJars so removed variants do not leave stale jars."
    delete(layout.buildDirectory.dir("libs"))
    mustRunAfter(packableSubprojects.map { it.tasks.named("build") })
    mustRunAfter(tasks.named("buildForge"))
}

tasks.register<Copy>("collectAllJars") {
    group = "build"
    description = "Copies every subproject remapped/production JAR into root build/libs."
    from(packableSubprojects.map { it.layout.buildDirectory.dir("libs") })
    from(forgeProjectDir.dir("build/libs")) {
        include("*-dev.jar")
        rename { fileName: String ->
            if (fileName.endsWith("-dev.jar")) {
                fileName.removeSuffix("-dev.jar") + ".jar"
            } else {
                fileName
            }
        }
    }
    into(layout.buildDirectory.dir("libs"))
    include("*.jar")
    exclude("*-sources.jar", "*-javadoc.jar")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(cleanCollectedLibs)
    dependsOn(packableSubprojects.map { it.tasks.named("build") })
    dependsOn("buildForge")
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds every included variant subproject plus the Forge shell jar."
    dependsOn("collectAllJars")
}

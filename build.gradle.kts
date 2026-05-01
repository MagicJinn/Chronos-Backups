import org.gradle.language.jvm.tasks.ProcessResources

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
}

tasks.register<Copy>("collectAllJars") {
    group = "build"
    description = "Copies every subproject remapped/production JAR into root build/libs."
    from(packableSubprojects.map { it.layout.buildDirectory.dir("libs") })
    into(layout.buildDirectory.dir("libs"))
    include("*.jar")
    exclude("*-sources.jar", "*-javadoc.jar")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(cleanCollectedLibs)
    dependsOn(packableSubprojects.map { it.tasks.named("build") })
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds every included variant subproject and collects final jars."
    dependsOn("collectAllJars")
}

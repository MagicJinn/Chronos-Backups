plugins {
    application
}

group = rootProject.findProperty("chronos.mod.group") as String
version = rootProject.findProperty("chronos.mod.version") as String

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.1")
}

application {
    mainClass.set("com.magicjinn.chronos.tooling.GenerateVariants")
}

fun parseCliArgs(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val tokenRegex = Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)")
    return tokenRegex.findAll(raw)
        .map { match ->
            match.groups[1]?.value
                ?: match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: ""
        }
        .filter { it.isNotBlank() }
        .toList()
}

val runGenerateVariants by tasks.registering(JavaExec::class) {
    group = "chronos"
    description = "Regenerates variants/<compileGroup> from JSON matrix files."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.magicjinn.chronos.tooling.GenerateVariants")
}

val runCleanVariants by tasks.registering(JavaExec::class) {
    group = "chronos"
    description = "Force-deletes variants/ with retries to tolerate locked files."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.magicjinn.chronos.tooling.CleanVariants")
}

val runTestServers by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs server integration tests."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.magicjinn.chronos.tooling.TestServers.TestServers")
    dependsOn(rootProject.tasks.named("prepareTestServers"))
}

val runSmokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs dedicated server smoke tests in parallel."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.magicjinn.chronos.tooling.SmokeTestServers")
    val rawArgs = project.findProperty("chronos.smoke.args")?.toString() ?: ""
    args = parseCliArgs(rawArgs)
}

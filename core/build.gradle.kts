plugins {
    `java-library`
}

group = rootProject.findProperty("chronos.mod.group") as String
version = rootProject.findProperty("chronos.mod.version") as String

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.Querz:NBT:6.1")
    val nightConfigVersion = findProperty("chronos.nightconfig.version") as String? ?: "3.6.7"
    implementation("com.electronwill.night-config:toml:$nightConfigVersion")
}

java {
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Core is authored against modern Java but emits bytecode for older runtimes.
    // Increase `chronos.core.java.release` when every supported loader ships a newer JVM floor.
    options.release.set((findProperty("chronos.core.java.release") as String?)?.toIntOrNull() ?: 8)
}

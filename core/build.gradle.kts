plugins {
    `java-library`
}

group = rootProject.findProperty("chronos.mod.group") as String
version = rootProject.findProperty("chronos.mod.version") as String

repositories {
    mavenCentral()
}

dependencies {
    val nightConfigVersion = findProperty("chronos.nightconfig.version") as String? ?: "3.6.7"
    implementation("com.electronwill.night-config:toml:$nightConfigVersion")

    // Google Drive OAuth + API (Java 7 bytecode, works on Java 8+)
    val googleApiClientVersion =
        findProperty("chronos.google.api.client.version") as String? ?: "2.7.2"
    val googleOauthClientVersion =
        findProperty("chronos.google.oauth.client.version") as String? ?: "1.36.0"
    val googleDriveApiVersion =
        findProperty("chronos.google.api.services.drive.version") as String? ?: "v3-rev20230822-2.0.0"
    val gsonVersion = findProperty("chronos.gson.version") as String? ?: "2.11.0"
    implementation("com.google.api-client:google-api-client:$googleApiClientVersion")
    implementation("com.google.oauth-client:google-oauth-client-jetty:$googleOauthClientVersion")
    implementation("com.google.apis:google-api-services-drive:$googleDriveApiVersion")
    implementation("com.google.code.gson:gson:$gsonVersion")

    compileOnly("org.apache.logging.log4j:log4j-api:2.24.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets.named("main") {
    java.srcDir(rootProject.file("shell-shared/src/main/java"))
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

tasks.test {
    useJUnitPlatform()
}

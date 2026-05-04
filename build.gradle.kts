import groovy.json.JsonSlurper
import org.gradle.language.jvm.tasks.ProcessResources

val packableSubprojects = subprojects.filter { it.path != ":core" && it.path != ":tooling" }

// Per line slug: optional `jarTargetLabel` (group-wide fallback for collectAllJars).
@Suppress("UNCHECKED_CAST")
val jarTargetLabelByLineSlug: Map<String, String> = run {
    val f = layout.projectDirectory.file("gradle/chronos-compile-groups.json").asFile
    if (!f.exists()) {
        return@run emptyMap()
    }
    val root = JsonSlurper().parseText(f.readText()) as Map<String, Any>
    val groups = root["groups"] as? List<*> ?: return@run emptyMap()
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

// Loader-specific archive suffix for collectAllJars: `fabricUnified` / `neoForgeUnified` /
// `forgeUnified`.`archiveVersionTag` in chronos-compile-groups.json (matches variant base.archivesName).
@Suppress("UNCHECKED_CAST")
val jarArchiveTagByLoaderAndSlug: Map<String, Map<String, String>> = run {
    val f = layout.projectDirectory.file("gradle/chronos-compile-groups.json").asFile
    if (!f.exists()) {
        return@run emptyMap()
    }
    val root = JsonSlurper().parseText(f.readText()) as Map<String, Any>
    val groups = root["groups"] as? List<*> ?: return@run emptyMap()
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
        (gm["fabricUnified"] as? Map<*, *>)?.get("archiveVersionTag")?.toString()?.takeIf { it.isNotBlank() }?.let {
            putTag("fabric", slug, it)
        }
        (gm["neoForgeUnified"] as? Map<*, *>)?.get("archiveVersionTag")?.toString()?.takeIf { it.isNotBlank() }?.let {
            putTag("neoforge", slug, it)
        }
        (gm["forgeUnified"] as? Map<*, *>)?.get("archiveVersionTag")?.toString()?.takeIf { it.isNotBlank() }?.let {
            putTag("forge", slug, it)
        }
    }
    out.mapValues { it.value.toMap() }
}

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

fun collectedJarPrefix(variantProjectName: String): String {
    val lineMatch = Regex("""^(fabric|forge|neoforge)-line-(.+)$""").matchEntire(variantProjectName)
    if (lineMatch != null) {
        val loader = lineMatch.groupValues[1]
        val slug = lineMatch.groupValues[2]
        return "$loader-${variantSlugToVersionLabel(loader, slug)}"
    }
    val bareMatch = Regex("""^(fabric|forge|neoforge)-(.+)$""").matchEntire(variantProjectName)
    if (bareMatch != null) {
        val loader = bareMatch.groupValues[1]
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

tasks.register<Copy>("collectAllJars") {
    group = "build"
    description =
        "Copies each variant production JAR into root build/libs (renamed), then deletes those JARs from the variant build/libs folders."
    into(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
    dependsOn(packableSubprojects.map { it.tasks.named("build") })
    val modId =
        (findProperty("chronos.mod.id") ?: "chronosbackup").toString().lowercase()
    val modVersion = (findProperty("chronos.mod.version") ?: "0.0.0").toString()
    for (sub in packableSubprojects) {
        from(sub.layout.buildDirectory.dir("libs")) {
            include("*.jar")
            exclude("*-sources.jar", "*-javadoc.jar", "*-dev.jar")
            rename { collectedJarName(modId, modVersion, sub.name) }
        }
    }
    doLast {
        for (sub in packableSubprojects) {
            val libsDir = sub.layout.buildDirectory.dir("libs").get().asFile
            if (!libsDir.isDirectory) continue
            libsDir.listFiles { _, name ->
                name.endsWith(".jar") &&
                    !name.endsWith("-sources.jar") &&
                    !name.endsWith("-javadoc.jar") &&
                    !name.endsWith("-dev.jar")
            }?.forEach { it.delete() }
        }
    }
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds every included variant subproject and collects final jars."
    dependsOn("collectAllJars")
}

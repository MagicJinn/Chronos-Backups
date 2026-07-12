import { readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { uploadCurseForgeFile, CurseForgeVersionResolver } from "./curseforge.js";
import {
  defaultCompileGroupsPath,
  knownLoadersFromIndex,
  loadJarTargetIndex,
  lookupJarTarget,
} from "./compile-groups-index.js";
import { getModPageDescription } from "./create-mod-page-description.js";
import { createModrinthVersion, resolveModrinthProjectId, updateModrinthProjectBody } from "./modrinth.js";
import { ModrinthGameVersionResolver } from "./modrinth-versions.js";
import { ModrinthLoaderResolver } from "./modrinth-loaders.js";
import { artifactVersionNumber, parseJarFileName } from "./parse-jar.js";
import {
  curseforgeProjectIdForLoader,
  defaultPublishConfigPath,
  loadPublishPlatformConfig,
  type PublishPlatformConfig,
} from "./publish-config.js";

export interface PublishConfig {
  jarsDir: string;
  repoRoot: string;
  title: string;
  changelog: string;
  platform: PublishPlatformConfig;
  modrinthToken?: string;
  curseforgeToken?: string;
  dryRun: boolean;
  skipModrinth: boolean;
  skipCurseforge: boolean;
}

function envFlag(name: string, defaultValue = false): boolean {
  const value = process.env[name]?.trim().toLowerCase();
  if (!value) {
    return defaultValue;
  }
  return value === "1" || value === "true" || value === "yes";
}

function optionalEnv(name: string): string | undefined {
  const value = process.env[name]?.trim();
  return value || undefined;
}

function applyEnvOverrides(platform: PublishPlatformConfig): PublishPlatformConfig {
  const modId = process.env.CHRONOS_MOD_ID?.trim();
  const modrinthProject = process.env.MODRINTH_PROJECT?.trim();
  const curseforgeModsProjectId =
    process.env.CURSEFORGE_PROJECT_ID?.trim() || process.env.CURSEFORGE_ID?.trim();
  const curseforgePluginsProjectId = process.env.CURSEFORGE_PLUGINS_PROJECT_ID?.trim();

  return {
    ...platform,
    modId: modId || platform.modId,
    modrinth: {
      ...platform.modrinth,
      project: modrinthProject || platform.modrinth.project,
    },
    curseforge: {
      modsProjectId: curseforgeModsProjectId || platform.curseforge.modsProjectId,
      pluginsProjectId: curseforgePluginsProjectId || platform.curseforge.pluginsProjectId,
    },
  };
}

export async function loadConfigFromEnv(): Promise<PublishConfig> {
  const scriptDir = path.dirname(fileURLToPath(import.meta.url));
  const defaultRepoRoot = path.resolve(scriptDir, "..", "..", "..");
  const configPath = process.env.CHRONOS_PUBLISH_CONFIG?.trim() || defaultPublishConfigPath(scriptDir);

  const repoRoot = path.resolve(process.env.CHRONOS_REPO_ROOT?.trim() || defaultRepoRoot);
  const jarsDir = path.resolve(process.env.CHRONOS_JARS_DIR?.trim() || path.join(repoRoot, "release-jars"));

  const title = process.env.RELEASE_TITLE?.trim() || process.env.GITHUB_RELEASE_NAME?.trim();
  const changelog = process.env.RELEASE_BODY?.trim() ?? process.env.GITHUB_RELEASE_BODY?.trim() ?? "";

  if (!title) {
    throw new Error("Missing RELEASE_TITLE (or GITHUB_RELEASE_NAME)");
  }

  const dryRun = envFlag("CHRONOS_PUBLISH_DRY_RUN");
  const skipModrinth = envFlag("CHRONOS_PUBLISH_SKIP_MODRINTH");
  const skipCurseforge = envFlag("CHRONOS_PUBLISH_SKIP_CURSEFORGE");

  const modrinthToken = optionalEnv("MODRINTH_TOKEN");
  const curseforgeToken = optionalEnv("CURSEFORGE_TOKEN");

  if (!dryRun && !skipModrinth && !modrinthToken) {
    throw new Error("MODRINTH_TOKEN is required unless CHRONOS_PUBLISH_SKIP_MODRINTH or CHRONOS_PUBLISH_DRY_RUN is set");
  }
  if (!dryRun && !skipCurseforge && !curseforgeToken) {
    throw new Error(
      "CURSEFORGE_TOKEN is required unless CHRONOS_PUBLISH_SKIP_CURSEFORGE or CHRONOS_PUBLISH_DRY_RUN is set",
    );
  }

  const platform = applyEnvOverrides(await loadPublishPlatformConfig(configPath));

  return {
    jarsDir,
    repoRoot,
    title,
    changelog,
    platform,
    modrinthToken,
    curseforgeToken,
    dryRun,
    skipModrinth,
    skipCurseforge,
  };
}

async function listJarFiles(jarsDir: string): Promise<string[]> {
  const entries = await readdir(jarsDir, { withFileTypes: true });
  return entries
    .filter((entry) => entry.isFile() && entry.name.endsWith(".jar"))
    .map((entry) => entry.name)
    .sort((a, b) => a.localeCompare(b));
}

export async function publishRelease(config: PublishConfig): Promise<void> {
  const compileGroupsPath = defaultCompileGroupsPath(config.repoRoot);
  const index = await loadJarTargetIndex(compileGroupsPath);
  const knownLoaders = knownLoadersFromIndex(index);
  const modrinthProjectId = config.skipModrinth
    ? config.platform.modrinth.project
    : await resolveModrinthProjectId(
        config.platform.modrinth.project,
        config.platform.userAgent,
        config.modrinthToken,
        config.dryRun,
      );
  const modrinthVersionResolver = config.skipModrinth ? null : new ModrinthGameVersionResolver();
  if (modrinthVersionResolver && !config.dryRun) {
    await modrinthVersionResolver.load();
  }
  const modrinthLoaderResolver = config.skipModrinth ? null : new ModrinthLoaderResolver(config.platform.userAgent);
  if (modrinthLoaderResolver) {
    await modrinthLoaderResolver.load();
  }
  const curseforgeResolver =
    config.skipCurseforge || config.dryRun
      ? null
      : new CurseForgeVersionResolver(config.curseforgeToken ?? "", config.platform.userAgent);
  if (curseforgeResolver) {
    await curseforgeResolver.load();
  }

  const jarNames = await listJarFiles(config.jarsDir);
  if (jarNames.length === 0) {
    throw new Error(`No jar files found in ${config.jarsDir}`);
  }

  console.log(
    `Publishing ${jarNames.length} jar(s) from ${config.jarsDir}` +
      `${config.dryRun ? " [dry-run]" : ""}` +
      ` as release (loaders: ${[...knownLoaders].sort().join(", ")})`,
  );

  let failures = 0;

  for (const fileName of jarNames) {
    try {
      const parsed = parseJarFileName(fileName, config.platform.modId, knownLoaders);
      const target = lookupJarTarget(index, parsed.mcTarget, parsed.loader);
      const filePath = path.join(config.jarsDir, fileName);
      const versionNumber = artifactVersionNumber(parsed);
      const curseforgeProjectId = curseforgeProjectIdForLoader(config.platform, parsed.loader);
      const modrinthGameVersions =
        config.dryRun || !modrinthVersionResolver
          ? target.gameVersions
          : modrinthVersionResolver.resolve(target.gameVersions);
      const curseforgeGameVersions =
        config.dryRun || !curseforgeResolver
          ? target.gameVersions
          : curseforgeResolver.resolve(target.gameVersions);
      const modrinthVersionsDiffer =
        modrinthGameVersions.length !== target.gameVersions.length ||
        modrinthGameVersions.some((version, index) => version !== target.gameVersions[index]);
      const curseforgeVersionsDiffer =
        curseforgeGameVersions.length !== target.gameVersions.length ||
        curseforgeGameVersions.some((version, index) => version !== target.gameVersions[index]);

      console.log(
        `\n> ${fileName}\n  loader=${parsed.loader} mc=${parsed.mcTarget} mod=${parsed.modVersion} ` +
          `versions=[${target.gameVersions.join(", ")}]` +
          (target.publishLoaders.length > 1 || target.publishLoaders[0] !== parsed.loader
            ? ` publishLoaders=[${target.publishLoaders.join(", ")}]`
            : "") +
          (modrinthVersionsDiffer ? ` modrinth=[${modrinthGameVersions.join(", ")}]` : "") +
          (curseforgeVersionsDiffer ? ` curseforge=[${curseforgeGameVersions.join(", ")}]` : "") +
          ` curseforgeProject=${curseforgeProjectId}`,
      );

      if (!config.skipModrinth && (config.modrinthToken || config.dryRun)) {
        const result = await createModrinthVersion(config.modrinthToken ?? "", {
          projectId: modrinthProjectId,
          versionNumber,
          name: fileName, // Filename instead of title to unify Curseforge and Modrinth approach
          changelog: config.changelog,
          loaders: target.publishLoaders,
          gameVersions: modrinthGameVersions,
          environment: modrinthLoaderResolver?.environmentForLoaders(
            target.publishLoaders,
            config.platform.modrinth.environment,
          ),
          dependencies: config.platform.dependencies,
          userAgent: config.platform.userAgent,
          filePath,
          fileName,
          dryRun: config.dryRun,
        });
        console.log(`  Modrinth: ${result.url}`);
      }

      if (!config.skipCurseforge && (config.curseforgeToken || config.dryRun)) {
        const cfResolver =
          curseforgeResolver ?? new CurseForgeVersionResolver("", config.platform.userAgent);
        const result = await uploadCurseForgeFile(config.curseforgeToken ?? "", cfResolver, {
          projectId: curseforgeProjectId,
          displayName: fileName,
          changelog: config.changelog,
          loaders: target.publishLoaders,
          gameVersions: curseforgeGameVersions,
          isPlugin: config.platform.pluginLoaders.includes(parsed.loader),
          dependencies: config.platform.dependencies,
          userAgent: config.platform.userAgent,
          filePath,
          fileName,
          dryRun: config.dryRun,
        });
        console.log(`  CurseForge file id: ${result.id}`);
      }
    } catch (error) {
      failures += 1;
      const message = error instanceof Error ? error.message : String(error);
      console.error(`  ERROR: ${message}`);
    }
  }

  if (failures > 0) {
    throw new Error(`${failures} jar(s) failed to publish`);
  }

  if (!config.skipModrinth && (config.modrinthToken || config.dryRun)) {
    const modPageDescription = await getModPageDescription(config.repoRoot);
    await updateModrinthProjectBody(
      config.modrinthToken ?? "",
      modrinthProjectId,
      modPageDescription,
      config.platform.userAgent,
      config.dryRun,
    );
    console.log(`\nModrinth project page updated (${modPageDescription.length} chars).`);
  }

  console.log(`\nDone. Published ${jarNames.length} jar(s).`);
}

async function main(): Promise<void> {
  const config = await loadConfigFromEnv();
  await publishRelease(config);
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (invokedDirectly) {
  main().catch((error: unknown) => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}

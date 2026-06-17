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
import { createModrinthVersion, ensureModrinthProjectEnvironment, resolveModrinthProjectId, CHRONOS_MODRINTH_ENVIRONMENT } from "./modrinth.js";
import { ModrinthGameVersionResolver } from "./modrinth-versions.js";
import { artifactVersionNumber, parseJarFileName } from "./parse-jar.js";

export interface PublishConfig {
  jarsDir: string;
  repoRoot: string;
  title: string;
  changelog: string;
  modId: string;
  modrinthProjectId: string;
  curseforgeProjectId: string;
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

export function loadConfigFromEnv(): PublishConfig {
  const scriptDir = path.dirname(fileURLToPath(import.meta.url));
  const defaultRepoRoot = path.resolve(scriptDir, "..", "..", "..");

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

  const curseforgeProjectId =
    process.env.CURSEFORGE_PROJECT_ID?.trim() ||
    process.env.CURSEFORGE_ID?.trim() ||
    (dryRun ? "0" : undefined);
  if (!curseforgeProjectId) {
    throw new Error("Missing CURSEFORGE_PROJECT_ID (or CURSEFORGE_ID)");
  }

  return {
    jarsDir,
    repoRoot,
    title,
    changelog,
    modId: process.env.CHRONOS_MOD_ID?.trim() || "chronosbackups",
    modrinthProjectId: process.env.MODRINTH_PROJECT?.trim() || "chronos-backups",
    curseforgeProjectId,
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
    ? config.modrinthProjectId
    : await resolveModrinthProjectId(
        config.modrinthProjectId,
        config.modrinthToken,
        config.dryRun,
      );
  if (!config.skipModrinth && (config.modrinthToken || config.dryRun)) {
    const environmentUpdated = await ensureModrinthProjectEnvironment(
      config.modrinthToken ?? "",
      modrinthProjectId,
      CHRONOS_MODRINTH_ENVIRONMENT,
      config.dryRun,
    );
    if (!environmentUpdated && !config.dryRun) {
      console.warn("Continuing Modrinth version uploads without updating project environment.");
    }
  }
  const modrinthVersionResolver = config.skipModrinth ? null : new ModrinthGameVersionResolver();
  if (modrinthVersionResolver && !config.dryRun) {
    await modrinthVersionResolver.load();
  }
  const curseforgeResolver =
    config.skipCurseforge || config.dryRun ? null : new CurseForgeVersionResolver(config.curseforgeToken ?? "");
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
      const parsed = parseJarFileName(fileName, config.modId, knownLoaders);
      const target = lookupJarTarget(index, parsed.mcTarget, parsed.loader);
      const filePath = path.join(config.jarsDir, fileName);
      const versionNumber = artifactVersionNumber(parsed);
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
          (modrinthVersionsDiffer ? ` modrinth=[${modrinthGameVersions.join(", ")}]` : "") +
          (curseforgeVersionsDiffer ? ` curseforge=[${curseforgeGameVersions.join(", ")}]` : ""),
      );

      if (!config.skipModrinth && (config.modrinthToken || config.dryRun)) {
        const result = await createModrinthVersion(config.modrinthToken ?? "", {
          projectId: modrinthProjectId,
          versionNumber,
          name: fileName, // Filename instead of title to unify Curseforge and Modrinth approach
          changelog: config.changelog,
          loaders: [parsed.loader],
          gameVersions: modrinthGameVersions,
          filePath,
          fileName,
          dryRun: config.dryRun,
        });
        console.log(`  Modrinth: ${result.url}`);
      }

      if (!config.skipCurseforge && (config.curseforgeToken || config.dryRun)) {
        const cfResolver = curseforgeResolver ?? new CurseForgeVersionResolver("");
        const result = await uploadCurseForgeFile(config.curseforgeToken ?? "", cfResolver, {
          projectId: config.curseforgeProjectId,
          displayName: fileName,
          changelog: config.changelog,
          loader: parsed.loader,
          gameVersions: curseforgeGameVersions,
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

  console.log(`\nDone. Published ${jarNames.length} jar(s).`);
}

async function main(): Promise<void> {
  const config = loadConfigFromEnv();
  await publishRelease(config);
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (invokedDirectly) {
  main().catch((error: unknown) => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}

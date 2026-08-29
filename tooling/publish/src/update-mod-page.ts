import path from "node:path";
import { fileURLToPath } from "node:url";
import { getModPageDescription } from "./create-mod-page-description.js";
import { resolveModrinthProjectId, updateModrinthProjectBody } from "./modrinth.js";
import { defaultPublishConfigPath, loadPublishPlatformConfig } from "./publish-config.js";

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

export async function updateModPageDescription(): Promise<void> {
  const scriptDir = path.dirname(fileURLToPath(import.meta.url));
  const defaultRepoRoot = path.resolve(scriptDir, "..", "..", "..");
  const configPath = process.env.CHRONOS_PUBLISH_CONFIG?.trim() || defaultPublishConfigPath(scriptDir);
  const repoRoot = path.resolve(process.env.CHRONOS_REPO_ROOT?.trim() || defaultRepoRoot);
  const dryRun = envFlag("CHRONOS_PUBLISH_DRY_RUN");
  const modrinthToken = optionalEnv("MODRINTH_TOKEN");

  if (!dryRun && !modrinthToken) {
    throw new Error("MODRINTH_TOKEN is required unless CHRONOS_PUBLISH_DRY_RUN is set");
  }

  const platform = await loadPublishPlatformConfig(configPath);
  const modrinthProject =
    process.env.MODRINTH_PROJECT?.trim() || platform.modrinth.project;
  const projectId = await resolveModrinthProjectId(
    modrinthProject,
    platform.userAgent,
    modrinthToken,
    dryRun,
  );
  const body = await getModPageDescription(repoRoot);

  await updateModrinthProjectBody(
    modrinthToken ?? "",
    projectId,
    body,
    platform.userAgent,
    dryRun,
  );

  const banner = "=".repeat(72);
  console.log(`\n${banner}`);
  console.log(`Modrinth project page updated (${body.length} chars)`);
  console.log(banner);
  console.log(body);
  console.log(`${banner}\n`);
}

async function main(): Promise<void> {
  await updateModPageDescription();
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (invokedDirectly) {
  main().catch((error: unknown) => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}

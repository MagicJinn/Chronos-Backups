import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export interface LoaderDependencyConfig {
  modrinthProjectId?: string;
  curseforgeSlug?: string;
}

export interface PublishPlatformConfig {
  modId: string;
  modrinth: {
    project: string;
    environment: "server_only";
  };
  curseforge: {
    modsProjectId: string;
    pluginsProjectId: string;
  };
  pluginLoaders: string[];
  userAgent: string;
  dependencies: Record<string, LoaderDependencyConfig>;
}

export function defaultPublishConfigPath(fromDir?: string): string {
  const scriptDir = fromDir ?? path.dirname(fileURLToPath(import.meta.url));
  return path.resolve(scriptDir, "..", "publish-config.json");
}

function requireNonEmpty(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`Invalid publish config: ${field} must be a non-empty string`);
  }
  return value.trim();
}

export function parsePublishPlatformConfig(raw: unknown): PublishPlatformConfig {
  if (typeof raw !== "object" || raw === null) {
    throw new Error("Invalid publish config: expected a JSON object");
  }

  const config = raw as Record<string, unknown>;
  const modrinth = config.modrinth;
  const curseforge = config.curseforge;

  if (typeof modrinth !== "object" || modrinth === null) {
    throw new Error("Invalid publish config: modrinth must be an object");
  }
  if (typeof curseforge !== "object" || curseforge === null) {
    throw new Error("Invalid publish config: curseforge must be an object");
  }

  const modrinthRecord = modrinth as Record<string, unknown>;
  const curseforgeRecord = curseforge as Record<string, unknown>;

  const pluginLoaders = Array.isArray(config.pluginLoaders)
    ? config.pluginLoaders.map((loader) => requireNonEmpty(loader, "pluginLoaders[]")).map((loader) => loader.toLowerCase())
    : [];

  const dependenciesRaw = config.dependencies;
  const dependencies: Record<string, LoaderDependencyConfig> = {};
  if (dependenciesRaw !== undefined) {
    if (typeof dependenciesRaw !== "object" || dependenciesRaw === null || Array.isArray(dependenciesRaw)) {
      throw new Error("Invalid publish config: dependencies must be an object");
    }
    for (const [loader, entry] of Object.entries(dependenciesRaw)) {
      if (typeof entry !== "object" || entry === null) {
        throw new Error(`Invalid publish config: dependencies.${loader} must be an object`);
      }
      const dep = entry as Record<string, unknown>;
      dependencies[loader.toLowerCase()] = {
        modrinthProjectId: dep.modrinthProjectId === undefined ? undefined : requireNonEmpty(dep.modrinthProjectId, `dependencies.${loader}.modrinthProjectId`),
        curseforgeSlug: dep.curseforgeSlug === undefined ? undefined : requireNonEmpty(dep.curseforgeSlug, `dependencies.${loader}.curseforgeSlug`),
      };
    }
  }

  const environment = requireNonEmpty(modrinthRecord.environment, "modrinth.environment");
  if (environment !== "server_only") {
    throw new Error(`Invalid publish config: unsupported modrinth.environment "${environment}"`);
  }

  return {
    modId: requireNonEmpty(config.modId, "modId"),
    modrinth: {
      project: requireNonEmpty(modrinthRecord.project, "modrinth.project"),
      environment,
    },
    curseforge: {
      modsProjectId: requireNonEmpty(curseforgeRecord.modsProjectId, "curseforge.modsProjectId"),
      pluginsProjectId: requireNonEmpty(curseforgeRecord.pluginsProjectId, "curseforge.pluginsProjectId"),
    },
    pluginLoaders,
    userAgent: requireNonEmpty(config.userAgent, "userAgent"),
    dependencies,
  };
}

export async function loadPublishPlatformConfig(configPath?: string): Promise<PublishPlatformConfig> {
  const resolvedPath = configPath ?? defaultPublishConfigPath();
  const raw = await readFile(resolvedPath, "utf8");
  return parsePublishPlatformConfig(JSON.parse(raw));
}

export function curseforgeProjectIdForLoader(platform: PublishPlatformConfig, loader: string): string {
  if (platform.pluginLoaders.includes(loader.toLowerCase())) {
    return platform.curseforge.pluginsProjectId;
  }
  return platform.curseforge.modsProjectId;
}

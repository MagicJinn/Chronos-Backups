import type { LoaderDependencyConfig, PublishPlatformConfig } from "./publish-config.js";

export interface ModrinthVersionDependency {
  project_id: string;
  dependency_type: "required";
}

export interface CurseForgeProjectRelation {
  slug: string;
  type: "requiredDependency";
}

export interface CurseForgeUploadRelations {
  projects: CurseForgeProjectRelation[];
}

export function modrinthDependenciesForLoaders(
  loaders: string[],
  dependencies: PublishPlatformConfig["dependencies"],
): ModrinthVersionDependency[] {
  const out: ModrinthVersionDependency[] = [];
  for (const loader of loaders) {
    const modrinthProjectId = dependencies[loader.toLowerCase()]?.modrinthProjectId;
    if (modrinthProjectId) {
      out.push({ project_id: modrinthProjectId, dependency_type: "required" });
    }
  }
  return out;
}

export function curseforgeRelationsForLoader(
  loader: string,
  dependencies: PublishPlatformConfig["dependencies"],
): CurseForgeUploadRelations | undefined {
  const curseforgeSlug = dependencies[loader.toLowerCase()]?.curseforgeSlug;
  if (!curseforgeSlug) {
    return undefined;
  }
  return {
    projects: [{ slug: curseforgeSlug, type: "requiredDependency" }],
  };
}

export function dependencyConfigForTests(): PublishPlatformConfig["dependencies"] {
  return {
    fabric: {
      modrinthProjectId: "P7dR8mSH",
      curseforgeSlug: "fabric-api",
    } satisfies LoaderDependencyConfig,
  };
}

export const FABRIC_API_MODRINTH_PROJECT_ID = "fabric-api";
export const FABRIC_API_CURSEFORGE_SLUG = "fabric-api";

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

export function modrinthDependenciesForLoaders(loaders: string[]): ModrinthVersionDependency[] {
  if (loaders.includes("fabric")) {
    return [{ project_id: FABRIC_API_MODRINTH_PROJECT_ID, dependency_type: "required" }];
  }
  return [];
}

export function curseforgeRelationsForLoader(loader: string): CurseForgeUploadRelations | undefined {
  if (loader === "fabric") {
    return {
      projects: [{ slug: FABRIC_API_CURSEFORGE_SLUG, type: "requiredDependency" }],
    };
  }
  return undefined;
}

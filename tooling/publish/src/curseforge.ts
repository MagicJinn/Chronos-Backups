import { openAsBlob } from "node:fs";

const CURSEFORGE_API = "https://minecraft.curseforge.com/api";

/** Minecraft Java release versions use names like 1.20.1, 26.1.2, etc. */
const MINECRAFT_VERSION_NAME = /^\d+(?:\.\d+)+$/;

interface CurseForgeGameVersion {
  id: number;
  name: string;
  slug: string;
  gameVersionTypeID: number;
}

interface VersionCandidate {
  id: number;
  gameVersionTypeID: number;
}

export interface CurseForgeVersionRequest {
  projectId: string;
  displayName: string;
  changelog: string;
  loader: string;
  gameVersions: string[];
  filePath: string;
  fileName: string;
  dryRun: boolean;
}

function preferVersionCandidates(candidates: VersionCandidate[]): VersionCandidate[] {
  return [...candidates].sort((a, b) => b.gameVersionTypeID - a.gameVersionTypeID);
}

function addCandidate(map: Map<string, VersionCandidate[]>, key: string, candidate: VersionCandidate): void {
  const existing = map.get(key) ?? [];
  if (existing.some((entry) => entry.id === candidate.id)) {
    return;
  }
  existing.push(candidate);
  map.set(key, existing);
}

export function parseCurseForgeUploadFileId(body: string, fileName: string): number {
  const trimmed = body.trim();

  try {
    const parsed = JSON.parse(trimmed) as unknown;
    if (typeof parsed === "number" && Number.isFinite(parsed)) {
      return parsed;
    }
    if (typeof parsed === "object" && parsed !== null && "id" in parsed) {
      const id = Number((parsed as { id: unknown }).id);
      if (Number.isFinite(id)) {
        return id;
      }
    }
  } catch {
    // Plain numeric body.
  }

  const id = Number(trimmed);
  if (!Number.isFinite(id)) {
    throw new Error(`CurseForge returned unexpected file id for ${fileName}: ${body}`);
  }
  return id;
}

function invalidDependencyId(errorBody: string): number | undefined {
  const match = /Invalid game version ID: (\d+)/.exec(errorBody);
  if (!match) {
    return undefined;
  }
  const id = Number(match[1]);
  return Number.isFinite(id) ? id : undefined;
}

export class CurseForgeVersionResolver {
  private readonly mcVersionCandidatesByName = new Map<string, VersionCandidate[]>();
  private readonly loaderCandidatesBySlug = new Map<string, VersionCandidate[]>();
  private loaded = false;

  constructor(private readonly token: string) {}

  async load(): Promise<void> {
    if (this.loaded) {
      return;
    }

    const response = await fetch(`${CURSEFORGE_API}/game/versions`, {
      headers: {
        "X-Api-Token": this.token,
        "User-Agent": "Chronos-Backups-Publish/1.0 (github.com/MagicJinn/Chronos-Backups)",
      },
    });

    if (!response.ok) {
      const body = await response.text();
      throw new Error(`CurseForge game versions fetch failed (${response.status}): ${body}`);
    }

    const versions = (await response.json()) as CurseForgeGameVersion[];
    for (const version of versions) {
      const candidate = { id: version.id, gameVersionTypeID: version.gameVersionTypeID };
      if (MINECRAFT_VERSION_NAME.test(version.name)) {
        addCandidate(this.mcVersionCandidatesByName, version.name, candidate);
      } else {
        addCandidate(this.loaderCandidatesBySlug, version.slug.toLowerCase(), candidate);
      }
    }

    this.loaded = true;
  }

  resolveGameVersionIdCombinations(names: string[], loader: string): number[][] {
    const mcLists = names.map((name) => {
      const candidates = this.mcVersionCandidatesByName.get(name);
      if (!candidates?.length) {
        throw new Error(
          `CurseForge has no game version id for: ${name}. ` +
            "Add supportedVersions in chronos-compile-groups.json or wait for CurseForge to list the version.",
        );
      }
      return preferVersionCandidates(candidates);
    });

    const loaderCandidates = this.loaderCandidatesBySlug.get(loader.toLowerCase());
    if (!loaderCandidates?.length) {
      throw new Error(
        `CurseForge loader id not found for "${loader}". ` +
          "Ensure CurseForge lists this mod loader slug in /api/game/versions.",
      );
    }

    const loaders = preferVersionCandidates(loaderCandidates);
    const combinations: number[][] = [];
    const seen = new Set<string>();

    const addCombination = (mcIds: number[], loaderId: number): void => {
      const key = [...mcIds, loaderId].join(",");
      if (seen.has(key)) {
        return;
      }
      seen.add(key);
      combinations.push([...mcIds, loaderId]);
    };

    for (const loaderCandidate of loaders) {
      addCombination(
        mcLists.map((list) => list[0].id),
        loaderCandidate.id,
      );
    }

    for (let mcIndex = 0; mcIndex < mcLists.length; mcIndex++) {
      const alternatives = mcLists[mcIndex];
      for (let altIndex = 1; altIndex < alternatives.length; altIndex++) {
        const mcIds = mcLists.map((list, index) =>
          index === mcIndex ? alternatives[altIndex].id : list[0].id,
        );
        addCombination(mcIds, loaders[0].id);
      }
    }

    return combinations;
  }
}

async function postCurseForgeUpload(
  token: string,
  projectId: string,
  fileName: string,
  filePath: string,
  metadata: Record<string, unknown>,
): Promise<number> {
  const form = new FormData();
  form.append("metadata", JSON.stringify(metadata));
  form.append("file", await openAsBlob(filePath), fileName);

  const response = await fetch(`${CURSEFORGE_API}/projects/${projectId}/upload-file`, {
    method: "POST",
    headers: {
      "X-Api-Token": token,
      "User-Agent": "Chronos-Backups-Publish/1.0 (github.com/MagicJinn/Chronos-Backups)",
    },
    body: form,
  });

  const body = await response.text();
  if (!response.ok) {
    throw new Error(`CurseForge upload failed (${response.status}) for ${fileName}: ${body}`);
  }

  return parseCurseForgeUploadFileId(body, fileName);
}

export async function uploadCurseForgeFile(
  token: string,
  resolver: CurseForgeVersionResolver,
  request: CurseForgeVersionRequest,
): Promise<{ id: number }> {
  if (request.dryRun) {
    const metadata = {
      changelog: request.changelog,
      changelogType: "markdown",
      displayName: request.displayName,
      releaseType: "release",
      loader: request.loader,
      gameVersions: request.gameVersions,
    };
    console.log(`[dry-run] CurseForge ${request.fileName}:`, JSON.stringify(metadata, null, 2));
    return { id: 0 };
  }

  await resolver.load();

  const combinations = resolver.resolveGameVersionIdCombinations(request.gameVersions, request.loader);
  let lastError: Error | undefined;

  for (const gameVersionIds of combinations) {
    const metadata = {
      changelog: request.changelog,
      changelogType: "markdown",
      displayName: request.displayName,
      releaseType: "release",
      gameVersions: gameVersionIds,
    };

    try {
      const id = await postCurseForgeUpload(
        token,
        request.projectId,
        request.fileName,
        request.filePath,
        metadata,
      );
      return { id };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (!message.includes('"errorCode":1009') || invalidDependencyId(message) === undefined) {
        throw error;
      }
      lastError = error instanceof Error ? error : new Error(message);
    }
  }

  throw lastError ?? new Error(`CurseForge upload failed for ${request.fileName}`);
}

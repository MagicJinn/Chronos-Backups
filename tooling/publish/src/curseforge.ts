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

export class CurseForgeVersionResolver {
  private readonly mcVersionIdByName = new Map<string, number>();
  private readonly loaderIdBySlug = new Map<string, number>();
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
      if (MINECRAFT_VERSION_NAME.test(version.name)) {
        this.mcVersionIdByName.set(version.name, version.id);
      } else {
        this.loaderIdBySlug.set(version.slug.toLowerCase(), version.id);
      }
    }

    this.loaded = true;
  }

  resolveGameVersionIds(names: string[], loader: string): number[] {
    const missing: string[] = [];
    const ids: number[] = [];

    for (const name of names) {
      const id = this.mcVersionIdByName.get(name);
      if (id === undefined) {
        missing.push(name);
      } else {
        ids.push(id);
      }
    }

    if (missing.length > 0) {
      throw new Error(
        `CurseForge has no game version id for: ${missing.join(", ")}. ` +
          "Add supportedVersions in chronos-compile-groups.json or wait for CurseForge to list the version.",
      );
    }

    const loaderId = this.loaderIdBySlug.get(loader.toLowerCase());
    if (loaderId === undefined) {
      throw new Error(
        `CurseForge loader id not found for "${loader}". ` +
          "Ensure CurseForge lists this mod loader slug in /api/game/versions.",
      );
    }

    return [...ids, loaderId];
  }
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

  const gameVersionIds = resolver.resolveGameVersionIds(request.gameVersions, request.loader);
  const metadata = {
    changelog: request.changelog,
    changelogType: "markdown",
    displayName: request.displayName,
    releaseType: "release",
    gameVersions: gameVersionIds,
  };

  const form = new FormData();
  form.append("metadata", JSON.stringify(metadata));
  form.append("file", await openAsBlob(request.filePath), request.fileName);

  const response = await fetch(`${CURSEFORGE_API}/projects/${request.projectId}/upload-file`, {
    method: "POST",
    headers: {
      "X-Api-Token": token,
      "User-Agent": "Chronos-Backups-Publish/1.0 (github.com/MagicJinn/Chronos-Backups)",
    },
    body: form,
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`CurseForge upload failed (${response.status}) for ${request.fileName}: ${body}`);
  }

  const fileId = Number(await response.text());
  if (!Number.isFinite(fileId)) {
    throw new Error(`CurseForge returned unexpected file id for ${request.fileName}`);
  }

  return { id: fileId };
}

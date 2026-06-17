import { openAsBlob } from "node:fs";

const MODRINTH_API = "https://api.modrinth.com/v2";

export interface ModrinthVersionRequest {
  projectId: string;
  versionNumber: string;
  name: string;
  changelog: string;
  loaders: string[];
  gameVersions: string[];
  filePath: string;
  fileName: string;
  dryRun: boolean;
}

export async function createModrinthVersion(
  token: string,
  request: ModrinthVersionRequest,
): Promise<{ id: string; url: string }> {
  const filePart = "primary";

  const metadata = {
    project_id: request.projectId,
    version_number: request.versionNumber,
    name: request.name,
    changelog: request.changelog,
    version_type: "release",
    loaders: request.loaders,
    game_versions: request.gameVersions,
    featured: false,
    dependencies: [],
    file_parts: [filePart],
    primary_file: filePart,
  };

  if (request.dryRun) {
    console.log(`[dry-run] Modrinth ${request.fileName}:`, JSON.stringify(metadata, null, 2));
    return { id: "dry-run", url: "dry-run" };
  }

  const form = new FormData();
  form.append("data", JSON.stringify(metadata));
  form.append(filePart, await openAsBlob(request.filePath), request.fileName);

  const response = await fetch(`${MODRINTH_API}/version`, {
    method: "POST",
    headers: {
      Authorization: token,
      "User-Agent": "Chronos-Backups-Publish/1.0 (github.com/MagicJinn/Chronos-Backups)",
    },
    body: form,
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Modrinth upload failed (${response.status}) for ${request.fileName}: ${body}`);
  }

  const created = (await response.json()) as { id: string; project_id: string; version_number: string };
  return {
    id: created.id,
    url: `https://modrinth.com/mod/${created.project_id}/version/${created.version_number}`,
  };
}

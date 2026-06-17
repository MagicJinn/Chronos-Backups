import { openAsBlob } from "node:fs";

const MODRINTH_API = "https://api.modrinth.com/v2";
const USER_AGENT = "Chronos-Backups-Publish/1.0 (github.com/MagicJinn/Chronos-Backups)";

/** Modrinth project ids are 8-character base62 strings (no hyphens). */
const MODRINTH_PROJECT_ID_PATTERN = /^[0-9A-Za-z]{8}$/;

export type ModrinthSideSupport = "required" | "optional" | "unsupported" | "unknown";

export interface ModrinthProjectEnvironment {
  client_side: ModrinthSideSupport;
  server_side: ModrinthSideSupport;
}

/** Required on dedicated servers and singleplayer integrated servers, not on clients. */
export const CHRONOS_MODRINTH_ENVIRONMENT: ModrinthProjectEnvironment = {
  client_side: "unsupported",
  server_side: "required",
};

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

function modrinthHeaders(token?: string): Record<string, string> {
  const headers: Record<string, string> = { "User-Agent": USER_AGENT };
  if (token) {
    headers.Authorization = token;
  }
  return headers;
}

/** Version create expects a base62 project id, not a slug. */
export async function resolveModrinthProjectId(
  slugOrId: string,
  token?: string,
  dryRun = false,
): Promise<string> {
  if (MODRINTH_PROJECT_ID_PATTERN.test(slugOrId)) {
    return slugOrId;
  }

  if (dryRun) {
    return "dryrunid";
  }

  const response = await fetch(`${MODRINTH_API}/project/${encodeURIComponent(slugOrId)}`, {
    headers: modrinthHeaders(token),
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Failed to resolve Modrinth project "${slugOrId}" (${response.status}): ${body}`);
  }

  const project = (await response.json()) as { id: string };
  return project.id;
}

export async function ensureModrinthProjectEnvironment(
  token: string,
  projectId: string,
  environment: ModrinthProjectEnvironment,
  dryRun = false,
): Promise<void> {
  if (dryRun) {
    console.log("[dry-run] Modrinth project environment:", JSON.stringify(environment));
    return;
  }

  const response = await fetch(`${MODRINTH_API}/project/${projectId}`, {
    method: "PATCH",
    headers: {
      ...modrinthHeaders(token),
      "Content-Type": "application/json",
    },
    body: JSON.stringify(environment),
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Modrinth project environment update failed (${response.status}): ${body}`);
  }
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
    headers: modrinthHeaders(token),
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

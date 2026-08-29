import { openAsBlob } from "node:fs";
import type { PublishPlatformConfig } from "./publish-config.js";
import { modrinthDependenciesForLoaders } from "./publish-dependencies.js";

const MODRINTH_API = "https://api.modrinth.com/v2";
const MODRINTH_V3_API = "https://api.modrinth.com/v3";

/** Modrinth project ids are 8-character base62 strings (no hyphens). */
const MODRINTH_PROJECT_ID_PATTERN = /^[0-9A-Za-z]{8}$/;

export interface ModrinthVersionRequest {
  projectId: string;
  versionNumber: string;
  name: string;
  changelog: string;
  loaders: string[];
  gameVersions: string[];
  environment?: PublishPlatformConfig["modrinth"]["environment"];
  dependencies: PublishPlatformConfig["dependencies"];
  userAgent: string;
  filePath: string;
  fileName: string;
  dryRun: boolean;
}

function normalizeModrinthToken(token: string): string {
  let value = token.trim();
  if (value.toLowerCase().startsWith("authorization:")) {
    value = value.slice("authorization:".length).trim();
  }
  if (value.toLowerCase().startsWith("bearer ")) {
    value = value.slice("bearer ".length).trim();
  }
  return value;
}

function modrinthHeaders(userAgent: string, token?: string): Record<string, string> {
  const headers: Record<string, string> = { "User-Agent": userAgent };
  if (token) {
    const normalized = normalizeModrinthToken(token);
    if (normalized) {
      headers.Authorization = normalized;
    }
  }
  return headers;
}

/** Version create expects a base62 project id, not a slug. */
export async function resolveModrinthProjectId(
  slugOrId: string,
  userAgent: string,
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
    headers: modrinthHeaders(userAgent, token),
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Failed to resolve Modrinth project "${slugOrId}" (${response.status}): ${body}`);
  }

  const project = (await response.json()) as { id: string };
  return project.id;
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
    ...(request.environment ? { environment: request.environment } : {}),
    featured: false,
    dependencies: modrinthDependenciesForLoaders(request.loaders, request.dependencies),
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

  const response = await fetch(`${MODRINTH_V3_API}/version`, {
    method: "POST",
    headers: modrinthHeaders(request.userAgent, token),
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

export async function updateModrinthProjectBody(
  token: string,
  projectId: string,
  body: string,
  userAgent: string,
  dryRun = false,
): Promise<void> {
  if (dryRun) {
    console.log(`[dry-run] Modrinth project body (${body.length} chars)`);
    return;
  }

  const response = await fetch(`${MODRINTH_API}/project/${encodeURIComponent(projectId)}`, {
    method: "PATCH",
    headers: {
      ...modrinthHeaders(userAgent, token),
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ body }),
  });

  if (!response.ok) {
    const responseBody = await response.text();
    throw new Error(`Modrinth project body update failed (${response.status}): ${responseBody}`);
  }
}

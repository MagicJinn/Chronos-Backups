import { openAsBlob } from "node:fs";
import { modrinthDependenciesForLoaders } from "./publish-dependencies.js";

const MODRINTH_API = "https://api.modrinth.com/v2";
const MODRINTH_V3_API = "https://api.modrinth.com/v3";
const USER_AGENT = "Chronos-Backups-Publish/1.0 (github.com/MagicJinn/Chronos-Backups)";

/** Modrinth project ids are 8-character base62 strings (no hyphens). */
const MODRINTH_PROJECT_ID_PATTERN = /^[0-9A-Za-z]{8}$/;

/**
 * Server-side only, including singleplayer integrated servers.
 * Maps to Modrinth UI: "Server-side only" + "Works in singleplayer too".
 */
export const CHRONOS_MODRINTH_ENVIRONMENT = "server_only" as const;

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

function modrinthHeaders(token?: string): Record<string, string> {
  const headers: Record<string, string> = { "User-Agent": USER_AGENT };
  if (token) {
    const normalized = normalizeModrinthToken(token);
    if (normalized) {
      headers.Authorization = normalized;
    }
  }
  return headers;
}

async function fetchModrinthProjectEnvironment(projectId: string): Promise<string[] | null> {
  const response = await fetch(`${MODRINTH_V3_API}/project/${projectId}`, {
    headers: { "User-Agent": USER_AGENT },
  });
  if (!response.ok) {
    return null;
  }

  const project = (await response.json()) as { environment?: string[] };
  return project.environment ?? null;
}

function projectEnvironmentMatches(current: string[] | null, desired: string): boolean {
  return current?.length === 1 && current[0] === desired;
}

async function verifyModrinthToken(token: string): Promise<boolean> {
  const response = await fetch(`${MODRINTH_API}/user`, {
    headers: modrinthHeaders(token),
  });
  return response.ok;
}

function modrinthEnvironmentAuthHelp(status: number, tokenValid: boolean): string {
  if (!tokenValid) {
    return (
      "MODRINTH_TOKEN is missing or invalid (Modrinth /user check failed). " +
      "Copy the token value only (mrp_..., no Bearer prefix) into GitHub secrets."
    );
  }

  return (
    `Modrinth returned ${status} for PATCH /v3/project. Version uploads only need VERSION_CREATE; ` +
    "setting project environment needs PROJECT_WRITE (\"Write projects\"). " +
    "Regenerate the token with Write projects enabled, update MODRINTH_TOKEN, or set " +
    `environment to ${CHRONOS_MODRINTH_ENVIRONMENT} once in Modrinth project settings.`
  );
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
  environment: string = CHRONOS_MODRINTH_ENVIRONMENT,
  dryRun = false,
): Promise<boolean> {
  if (dryRun) {
    console.log("[dry-run] Modrinth project environment:", JSON.stringify([environment]));
    return true;
  }

  const current = await fetchModrinthProjectEnvironment(projectId);
  if (projectEnvironmentMatches(current, environment)) {
    console.log(`Modrinth project environment already set (${environment}).`);
    return true;
  }

  const response = await fetch(`${MODRINTH_V3_API}/project/${projectId}`, {
    method: "PATCH",
    headers: {
      ...modrinthHeaders(token),
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ environment: [environment] }),
  });

  if (response.ok) {
    console.log(`Modrinth project environment updated (${environment}).`);
    return true;
  }

  const body = await response.text();
  const tokenValid = await verifyModrinthToken(token);
  console.warn(
    `WARN: Modrinth project environment update failed (${response.status}): ${body}\n` +
      `${modrinthEnvironmentAuthHelp(response.status, tokenValid)}`,
  );
  return false;
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
    environment: CHRONOS_MODRINTH_ENVIRONMENT,
    featured: false,
    dependencies: modrinthDependenciesForLoaders(request.loaders),
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

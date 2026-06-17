const MODRINTH_API = "https://api.modrinth.com/v2";
const USER_AGENT = "Chronos-Backups-Publish/1.0 (github.com/MagicJinn/Chronos-Backups)";

interface ModrinthGameVersionTag {
  version: string;
}

/** Live game version tags from Modrinth (source of truth for uploads). */
export async function fetchModrinthGameVersionTags(): Promise<Set<string>> {
  const response = await fetch(`${MODRINTH_API}/tag/game_version`, {
    headers: { "User-Agent": USER_AGENT },
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Modrinth game versions fetch failed (${response.status}): ${body}`);
  }

  const versions = (await response.json()) as ModrinthGameVersionTag[];
  return new Set(versions.map((entry) => entry.version));
}

/**
 * Map compile-group supportedVersions to tags Modrinth accepts.
 * Many internal labels use a `.0` patch (e.g. 1.10.0) while Modrinth lists 1.10.
 */
export function resolveModrinthGameVersions(
  supportedVersions: string[],
  validTags: ReadonlySet<string>,
): string[] {
  const resolved = new Set<string>();

  for (const version of supportedVersions) {
    if (validTags.has(version)) {
      resolved.add(version);
      continue;
    }

    const majorMinor = /^(\d+\.\d+)\.0$/.exec(version)?.[1];
    if (majorMinor && validTags.has(majorMinor)) {
      resolved.add(majorMinor);
    }
  }

  if (resolved.size === 0) {
    throw new Error(
      `No Modrinth game version tags match: ${supportedVersions.join(", ")}`,
    );
  }

  return [...resolved].sort();
}

export class ModrinthGameVersionResolver {
  private validTags: Set<string> | null = null;

  async load(): Promise<void> {
    this.validTags = await fetchModrinthGameVersionTags();
  }

  resolve(supportedVersions: string[]): string[] {
    if (!this.validTags) {
      throw new Error("ModrinthGameVersionResolver.load() must be called first");
    }
    return resolveModrinthGameVersions(supportedVersions, this.validTags);
  }
}

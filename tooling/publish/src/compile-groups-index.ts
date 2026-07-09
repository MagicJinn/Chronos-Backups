import { readFile } from "node:fs/promises";
import path from "node:path";

interface ConfigBlock {
  archiveVersionTag?: string;
  loaderKey?: string;
  supportedVersions?: string[];
}

interface CompileGroup {
  jarTargetLabel?: string;
  supportedVersions?: string[];
  [key: string]: unknown;
}

interface CompileGroupsFile {
  groups: CompileGroup[];
}

export interface JarTargetEntry {
  archiveTag: string;
  loader: string;
  gameVersions: string[];
}

const CONFIG_BLOCK_SUFFIX = "Config";

function isConfigBlock(value: unknown): value is ConfigBlock {
  return typeof value === "object" && value !== null;
}

function loaderFromBlock(blockKey: string, block: ConfigBlock): string {
  if (block.loaderKey?.trim()) {
    return block.loaderKey.trim().toLowerCase();
  }
  if (!blockKey.endsWith(CONFIG_BLOCK_SUFFIX)) {
    throw new Error(`Config block key must end with "${CONFIG_BLOCK_SUFFIX}": ${blockKey}`);
  }
  const derived = blockKey.slice(0, -CONFIG_BLOCK_SUFFIX.length);
  if (!derived) {
    throw new Error(`Cannot derive loader from block key: ${blockKey}`);
  }
  return derived.toLowerCase();
}

function configBlocksInGroup(group: CompileGroup): Array<{ blockKey: string; block: ConfigBlock }> {
  const blocks: Array<{ blockKey: string; block: ConfigBlock }> = [];
  for (const [key, value] of Object.entries(group)) {
    if (!key.endsWith(CONFIG_BLOCK_SUFFIX) || !isConfigBlock(value)) {
      continue;
    }
    blocks.push({ blockKey: key, block: value });
  }
  return blocks;
}

export function buildJarTargetIndex(groups: CompileGroup[]): Map<string, JarTargetEntry> {
  const index = new Map<string, JarTargetEntry>();

  for (const group of groups) {
    const groupVersions = group.supportedVersions;
    const fallbackTag = group.jarTargetLabel;

    for (const { blockKey, block } of configBlocksInGroup(group)) {
      const archiveTag = block.archiveVersionTag ?? fallbackTag;
      if (!archiveTag) {
        continue;
      }

      const gameVersions = block.supportedVersions ?? groupVersions;
      if (!gameVersions?.length) {
        throw new Error(
          `No supportedVersions for ${blockKey} archive tag "${archiveTag}" (add to block or group)`,
        );
      }

      const loader = loaderFromBlock(blockKey, block);
      const mapKey = `${archiveTag}\0${loader}`;
      if (index.has(mapKey)) {
        throw new Error(`Duplicate jar target index entry: ${archiveTag} + ${loader}`);
      }

      index.set(mapKey, {
        archiveTag,
        loader,
        gameVersions: [...gameVersions],
      });
    }
  }

  return index;
}

export function knownLoadersFromIndex(index: Map<string, JarTargetEntry>): Set<string> {
  const loaders = new Set<string>();
  for (const entry of index.values()) {
    loaders.add(entry.loader);
  }
  return loaders;
}

export async function loadJarTargetIndex(compileGroupsPath: string): Promise<Map<string, JarTargetEntry>> {
  const raw = await readFile(compileGroupsPath, "utf8");
  const parsed = JSON.parse(raw) as CompileGroupsFile;
  if (!Array.isArray(parsed.groups)) {
    throw new Error(`Invalid compile groups file: ${compileGroupsPath}`);
  }
  return buildJarTargetIndex(parsed.groups);
}

export function lookupJarTarget(
  index: Map<string, JarTargetEntry>,
  mcTarget: string,
  loader: string,
): JarTargetEntry {
  const entry = index.get(`${mcTarget}\0${loader}`);
  if (!entry) {
    const known = [...index.values()]
      .map((e) => `${e.archiveTag} (${e.loader})`)
      .sort()
      .join(", ");
    throw new Error(
      `No compile-groups entry for mc target "${mcTarget}" + loader "${loader}". Known: ${known}`,
    );
  }
  return entry;
}

export function defaultCompileGroupsPath(repoRoot: string): string {
  return path.join(repoRoot, "gradle", "chronos-compile-groups.json");
}

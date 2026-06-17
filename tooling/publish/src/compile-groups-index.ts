import { readFile } from "node:fs/promises";
import path from "node:path";

interface UnifiedBlock {
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

const UNIFIED_BLOCK_SUFFIX = "Unified";

function isUnifiedBlock(value: unknown): value is UnifiedBlock {
  return typeof value === "object" && value !== null;
}

function loaderFromBlock(blockKey: string, block: UnifiedBlock): string {
  if (block.loaderKey?.trim()) {
    return block.loaderKey.trim().toLowerCase();
  }
  if (!blockKey.endsWith(UNIFIED_BLOCK_SUFFIX)) {
    throw new Error(`Unified block key must end with "${UNIFIED_BLOCK_SUFFIX}": ${blockKey}`);
  }
  const derived = blockKey.slice(0, -UNIFIED_BLOCK_SUFFIX.length);
  if (!derived) {
    throw new Error(`Cannot derive loader from block key: ${blockKey}`);
  }
  return derived.toLowerCase();
}

function unifiedBlocksInGroup(group: CompileGroup): Array<{ blockKey: string; block: UnifiedBlock }> {
  const blocks: Array<{ blockKey: string; block: UnifiedBlock }> = [];
  for (const [key, value] of Object.entries(group)) {
    if (!key.endsWith(UNIFIED_BLOCK_SUFFIX) || !isUnifiedBlock(value)) {
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

    for (const { blockKey, block } of unifiedBlocksInGroup(group)) {
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

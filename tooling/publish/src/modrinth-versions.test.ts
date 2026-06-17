import assert from "node:assert/strict";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { defaultCompileGroupsPath, loadJarTargetIndex } from "./compile-groups-index.js";
import { fetchModrinthGameVersionTags, resolveModrinthGameVersions } from "./modrinth-versions.js";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");

test("resolveModrinthGameVersions throws when nothing resolves", () => {
  const tags = new Set(["1.0"]);
  assert.throws(
    () => resolveModrinthGameVersions(["2.0.0"], tags),
    /No Modrinth game version tags match/,
  );
});

test("compile group supportedVersions resolve against live Modrinth tags", async () => {
  const tags = await fetchModrinthGameVersionTags();
  const index = await loadJarTargetIndex(defaultCompileGroupsPath(repoRoot));

  for (const entry of index.values()) {
    const resolved = resolveModrinthGameVersions(entry.gameVersions, tags);
    assert.ok(
      resolved.length > 0,
      `${entry.archiveTag} (${entry.loader}): no Modrinth tags for [${entry.gameVersions.join(", ")}]`,
    );
    for (const tag of resolved) {
      assert.ok(
        tags.has(tag),
        `${entry.archiveTag} (${entry.loader}): resolved tag "${tag}" is not on Modrinth`,
      );
    }
  }
});

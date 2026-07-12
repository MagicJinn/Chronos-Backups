import assert from "node:assert/strict";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import {
  buildJarTargetIndex,
  defaultCompileGroupsPath,
  loadJarTargetIndex,
  lookupJarTarget,
} from "./compile-groups-index.js";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");

test("pluginConfig indexes jar loader separately from publish loaders", () => {
  const index = buildJarTargetIndex([
    {
      pluginConfig: {
        archiveVersionTag: "1.7.10-1.12.x",
        supportedVersions: ["1.7.10", "1.12.2"],
        loaderKeys: ["PAPER", "SPIGOT", "BUKKIT"],
        jarLoaderKey: "PLUGIN",
      },
    },
  ]);

  const entry = lookupJarTarget(index, "1.7.10-1.12.x", "plugin");
  assert.equal(entry.loader, "plugin");
  assert.deepEqual(entry.publishLoaders, ["paper", "spigot", "bukkit"]);
});

test("mod loader blocks use the same loader for jar suffix and publish", () => {
  const index = buildJarTargetIndex([
    {
      fabricConfig: {
        archiveVersionTag: "1.21.x",
        supportedVersions: ["1.21.0"],
        loaderKeys: ["FABRIC"],
      },
    },
  ]);

  const entry = lookupJarTarget(index, "1.21.x", "fabric");
  assert.equal(entry.loader, "fabric");
  assert.deepEqual(entry.publishLoaders, ["fabric"]);
});

test("live compile groups define publish loaders for every plugin jar target", async () => {
  const index = await loadJarTargetIndex(defaultCompileGroupsPath(repoRoot));

  for (const entry of index.values()) {
    if (entry.loader !== "plugin") {
      continue;
    }

    assert.ok(entry.publishLoaders.length > 0, `${entry.archiveTag}: missing publishLoaders`);
    assert.ok(
      !entry.publishLoaders.includes("plugin"),
      `${entry.archiveTag}: publishLoaders must not include internal jar loader "plugin"`,
    );
  }
});

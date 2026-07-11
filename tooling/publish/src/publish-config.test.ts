import assert from "node:assert/strict";
import test from "node:test";
import {
  curseforgeProjectIdForLoader,
  parsePublishPlatformConfig,
} from "./publish-config.js";

// Simple regression test

const PLATFORM = parsePublishPlatformConfig({
  modId: "chronosbackups",
  modrinth: {
    project: "chronos-backups",
    environment: "server_only",
  },
  curseforge: {
    modsProjectId: "1535678",
    pluginsProjectId: "1603416",
  },
  pluginLoaders: ["plugin"],
  userAgent: "test-agent",
  dependencies: {},
});

test("curseforgeProjectIdForLoader routes plugin jars to plugins project", () => {
  assert.equal(curseforgeProjectIdForLoader(PLATFORM, "plugin"), "1603416");
});

test("curseforgeProjectIdForLoader routes mod loaders to mods project", () => {
  assert.equal(curseforgeProjectIdForLoader(PLATFORM, "fabric"), "1535678");
  assert.equal(curseforgeProjectIdForLoader(PLATFORM, "forge"), "1535678");
  assert.equal(curseforgeProjectIdForLoader(PLATFORM, "neoforge"), "1535678");
});

test("parsePublishPlatformConfig rejects missing curseforge plugins project id", () => {
  assert.throws(
    () =>
      parsePublishPlatformConfig({
        modId: "chronosbackups",
        modrinth: { project: "chronos-backups", environment: "server_only" },
        curseforge: { modsProjectId: "1535678" },
        pluginLoaders: ["plugin"],
        userAgent: "test-agent",
        dependencies: {},
      }),
    /curseforge\.pluginsProjectId/,
  );
});

import assert from "node:assert/strict";
import test from "node:test";
import {
  fetchModrinthLoaderFields,
  modrinthEnvironmentForLoaders,
} from "./modrinth-loaders.js";

const USER_AGENT = "Chronos-Backups-Publish/1.0 (github.com/MagicJinn/Chronos-Backups)";

test("modrinthEnvironmentForLoaders uses Modrinth supported_fields", () => {
  const fieldsByLoader = new Map<string, Set<string>>([
    ["fabric", new Set(["game_versions", "environment"])],
    ["paper", new Set(["game_versions"])],
  ]);

  assert.equal(modrinthEnvironmentForLoaders(["fabric"], "server_only", fieldsByLoader), "server_only");
  assert.equal(
    modrinthEnvironmentForLoaders(["paper", "spigot"], "server_only", fieldsByLoader),
    undefined,
  );
});

test("live Modrinth loader tags distinguish mod and plugin environment support", async () => {
  const fieldsByLoader = await fetchModrinthLoaderFields(USER_AGENT);

  assert.ok(fieldsByLoader.get("fabric")?.has("environment"));
  assert.ok(!fieldsByLoader.get("paper")?.has("environment"));
  assert.ok(!fieldsByLoader.get("bukkit")?.has("environment"));
});

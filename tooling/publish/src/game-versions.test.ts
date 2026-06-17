import assert from "node:assert/strict";
import test from "node:test";
import { resolveSupportedGameVersions } from "./game-versions.js";

test("resolveSupportedGameVersions maps .0 patches to major.minor tags", () => {
  const valid = new Set(["9.9", "9.9.2"]);
  const resolved = resolveSupportedGameVersions(["9.9.0", "9.9.2"], valid);
  assert.deepEqual(resolved, ["9.9", "9.9.2"]);
});

test("resolveSupportedGameVersions drops versions with no platform tag", () => {
  const valid = new Set(["9.9.1", "9.9.10"]);
  const resolved = resolveSupportedGameVersions(["9.9.0", "9.9.1", "9.9.10"], valid);
  assert.deepEqual(resolved, ["9.9.1", "9.9.10"]);
});

test("resolveSupportedGameVersions returns empty when nothing resolves", () => {
  const valid = new Set(["1.0"]);
  assert.deepEqual(resolveSupportedGameVersions(["2.0.0"], valid), []);
});

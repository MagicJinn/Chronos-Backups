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

test("resolveSupportedGameVersions can fall back any patch to major.minor", () => {
  const valid = new Set(["1.10", "1.13", "1.18.2"]);
  const resolved = resolveSupportedGameVersions(["1.10.2", "1.13.2", "1.18.2"], valid, {
    fallbackToMajorMinor: true,
  });
  assert.deepEqual(resolved, ["1.10", "1.13", "1.18.2"]);
});

test("resolveSupportedGameVersions does not strip non-zero patches by default", () => {
  const valid = new Set(["1.10", "1.10.2"]);
  assert.deepEqual(resolveSupportedGameVersions(["1.10.2"], valid), ["1.10.2"]);
});

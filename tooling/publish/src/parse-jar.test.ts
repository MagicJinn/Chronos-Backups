import assert from "node:assert/strict";
import test from "node:test";
import { artifactVersionNumber, parseJarFileName } from "./parse-jar.js";

const LOADERS = new Set(["fabric", "forge", "neoforge"]);
const MOD_ID = "chronosbackups";

test("parseJarFileName handles dotted mc target and mod version", () => {
  const parsed = parseJarFileName("chronosbackups-1.20.1-1.0.0-fabric.jar", MOD_ID, LOADERS);
  assert.equal(parsed.mcTarget, "1.20.1");
  assert.equal(parsed.modVersion, "1.0.0");
  assert.equal(parsed.loader, "fabric");
});

test("parseJarFileName handles dashed mc target range", () => {
  const parsed = parseJarFileName(
    "chronosbackups-1.20.0-1.20.1-0.1.0-forge.jar",
    MOD_ID,
    LOADERS,
  );
  assert.equal(parsed.mcTarget, "1.20.0-1.20.1");
  assert.equal(parsed.modVersion, "0.1.0");
  assert.equal(parsed.loader, "forge");
});

test("parseJarFileName handles long neoforge range", () => {
  const parsed = parseJarFileName(
    "chronosbackups-1.21.0-1.21.10-0.1.0-neoforge.jar",
    MOD_ID,
    LOADERS,
  );
  assert.equal(parsed.mcTarget, "1.21.0-1.21.10");
  assert.equal(parsed.modVersion, "0.1.0");
  assert.equal(parsed.loader, "neoforge");
});

test("parseJarFileName prefers neoforge over forge suffix", () => {
  const loaders = new Set(["forge", "neoforge"]);
  const parsed = parseJarFileName("chronosbackups-1.21.11-0.1.0-neoforge.jar", MOD_ID, loaders);
  assert.equal(parsed.loader, "neoforge");
});

test("artifactVersionNumber is Modrinth-safe for dashed mc targets", () => {
  const parsed = parseJarFileName(
    "chronosbackups-1.21.0-1.21.10-0.1.0-neoforge.jar",
    MOD_ID,
    LOADERS,
  );
  assert.equal(artifactVersionNumber(parsed), "0.1.0+1.21.0_1.21.10+neoforge");
});

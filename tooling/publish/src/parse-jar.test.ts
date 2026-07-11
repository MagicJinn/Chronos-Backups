import assert from "node:assert/strict";
import test from "node:test";
import { artifactVersionNumber, parseJarFileName } from "./parse-jar.js";

const LOADERS = new Set(["fabric", "forge", "neoforge"]);
const MOD_ID = "chronosbackups";

function jarName(mcTarget: string, modVersion: string, loader: string): string {
  return `${MOD_ID}-${mcTarget}-${modVersion}-${loader}.jar`;
}

test("parseJarFileName handles dotted mc target and mod version", () => {
  const mcTarget = "1.20.1";
  const modVersion = "1.0.0";
  const parsed = parseJarFileName(jarName(mcTarget, modVersion, "fabric"), MOD_ID, LOADERS);
  assert.equal(parsed.mcTarget, mcTarget);
  assert.equal(parsed.modVersion, modVersion);
  assert.equal(parsed.loader, "fabric");
});

test("parseJarFileName handles dashed mc target range", () => {
  const mcTarget = "1.20.0-1.20.1";
  const modVersion = "2.3.4";
  const parsed = parseJarFileName(jarName(mcTarget, modVersion, "forge"), MOD_ID, LOADERS);
  assert.equal(parsed.mcTarget, mcTarget);
  assert.equal(parsed.modVersion, modVersion);
  assert.equal(parsed.loader, "forge");
});

test("parseJarFileName handles long neoforge range", () => {
  const mcTarget = "1.21.0-1.21.10";
  const modVersion = "2.3.4";
  const parsed = parseJarFileName(jarName(mcTarget, modVersion, "neoforge"), MOD_ID, LOADERS);
  assert.equal(parsed.mcTarget, mcTarget);
  assert.equal(parsed.modVersion, modVersion);
  assert.equal(parsed.loader, "neoforge");
});

test("parseJarFileName prefers neoforge over forge suffix", () => {
  const loaders = new Set(["forge", "neoforge"]);
  const parsed = parseJarFileName(jarName("1.21.11", "2.3.4", "neoforge"), MOD_ID, loaders);
  assert.equal(parsed.loader, "neoforge");
});

test("parseJarFileName handles plugin loader suffix", () => {
  const loaders = new Set(["fabric", "forge", "neoforge", "plugin"]);
  const parsed = parseJarFileName(jarName("1.21.x", "2.3.4", "plugin"), MOD_ID, loaders);
  assert.equal(parsed.loader, "plugin");
});

test("artifactVersionNumber is Modrinth-safe for dashed mc targets", () => {
  const mcTarget = "1.21.0-1.21.10";
  const modVersion = "2.3.4";
  const loader = "neoforge";
  const parsed = parseJarFileName(jarName(mcTarget, modVersion, loader), MOD_ID, LOADERS);
  assert.equal(artifactVersionNumber(parsed), `${modVersion}+${mcTarget.replace(/-/g, "_")}+${loader}`);
});

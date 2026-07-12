import assert from "node:assert/strict";
import test from "node:test";
import {
  CurseForgeVersionResolver,
  parseCurseForgeUploadFileId,
  resolveCurseForgeGameVersions,
} from "./curseforge.js";

test("parseCurseForgeUploadFileId accepts JSON bodies", () => {
  assert.equal(parseCurseForgeUploadFileId('{"id":12345}', "mod.jar"), 12345);
});

test("parseCurseForgeUploadFileId accepts plain numeric bodies", () => {
  assert.equal(parseCurseForgeUploadFileId("67890", "mod.jar"), 67890);
});

test("resolveCurseForgeGameVersions throws when nothing resolves", () => {
  const names = new Set(["1.0"]);
  assert.throws(
    () => resolveCurseForgeGameVersions(["2.0.0"], names),
    /No CurseForge game versions match/,
  );
});

test("plugin uploads use only Bukkit Minecraft version ids", async () => {
  const resolver = new CurseForgeVersionResolver("token", "test-agent");
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response(
      JSON.stringify([
        { id: 100, name: "1.12.2", slug: "1-12-2", gameVersionTypeID: 1 },
        { id: 200, name: "1.12.2", slug: "1-12-2-mod", gameVersionTypeID: 2 },
        { id: 300, name: "Server", slug: "server", gameVersionTypeID: 3 },
        { id: 400, name: "Fabric", slug: "fabric", gameVersionTypeID: 4 },
      ]),
      { status: 200 },
    );

  try {
    await resolver.load();
    assert.deepEqual(resolver.resolveGameVersionIdCombinations(["1.12.2"], ["paper", "spigot"], true), [
      [100],
    ]);

    const modCombinations = resolver.resolveGameVersionIdCombinations(["1.12.2"], ["fabric"], false);
    assert.ok(modCombinations.some((combo) => JSON.stringify(combo) === JSON.stringify([200, 400, 300])));
    assert.ok(modCombinations.every((combo) => combo.includes(400) && combo.includes(300)));
  } finally {
    globalThis.fetch = originalFetch;
  }
});

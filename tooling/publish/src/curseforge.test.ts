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

test("resolveCurseForgeGameVersions can map patches to Bukkit major.minor tags", () => {
  const names = new Set(["1.13", "1.14", "1.18.2"]);
  assert.deepEqual(
    resolveCurseForgeGameVersions(["1.13.2", "1.14.4", "1.18.2"], names, {
      fallbackToMajorMinor: true,
    }),
    ["1.13", "1.14", "1.18.2"],
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

test("plugin resolve maps patches to Bukkit major.minor and never uses mod ids", async () => {
  const resolver = new CurseForgeVersionResolver("token", "test-agent");
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response(
      JSON.stringify([
        { id: 7132, name: "1.13.2", slug: "1-13-2", gameVersionTypeID: 55023 },
        { id: 7105, name: "1.13", slug: "1-13", gameVersionTypeID: 1 },
        { id: 6170, name: "1.10.2", slug: "1-10-2", gameVersionTypeID: 572 },
        { id: 591, name: "1.10", slug: "1-10", gameVersionTypeID: 1 },
        { id: 8516, name: "1.17.1", slug: "1-17-1", gameVersionTypeID: 73242 },
        { id: 8503, name: "1.17", slug: "1-17", gameVersionTypeID: 1 },
        { id: 9016, name: "1.18.2", slug: "1-18-2", gameVersionTypeID: 1 },
        { id: 9008, name: "1.18.2", slug: "1-18-2", gameVersionTypeID: 73250 },
      ]),
      { status: 200 },
    );

  try {
    await resolver.load();

    assert.deepEqual(resolver.resolve(["1.13.2", "1.10.2", "1.17.1", "1.18.2"], true), [
      "1.10",
      "1.13",
      "1.17",
      "1.18.2",
    ]);

    assert.deepEqual(
      resolver.resolveGameVersionIdCombinations(["1.10", "1.13", "1.17", "1.18.2"], ["paper"], true),
      [[591, 7105, 8503, 9016]],
    );

    assert.throws(
      () => resolver.resolveGameVersionIdCombinations(["1.13.2"], ["paper"], true),
      /no Bukkit game version id/,
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

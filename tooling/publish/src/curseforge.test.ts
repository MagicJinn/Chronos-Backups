import assert from "node:assert/strict";
import test from "node:test";
import { parseCurseForgeUploadFileId, resolveCurseForgeGameVersions } from "./curseforge.js";

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

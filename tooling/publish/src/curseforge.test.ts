import assert from "node:assert/strict";
import test from "node:test";
import { parseCurseForgeUploadFileId } from "./curseforge.js";

test("parseCurseForgeUploadFileId accepts JSON bodies", () => {
  assert.equal(parseCurseForgeUploadFileId('{"id":12345}', "mod.jar"), 12345);
});

test("parseCurseForgeUploadFileId accepts plain numeric bodies", () => {
  assert.equal(parseCurseForgeUploadFileId("67890", "mod.jar"), 67890);
});

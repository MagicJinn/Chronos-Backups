import assert from "node:assert/strict";
import test from "node:test";
import {
  curseforgeRelationsForLoader,
  modrinthDependenciesForLoaders,
} from "./publish-dependencies.js";

test("modrinthDependenciesForLoaders adds Fabric API for fabric", () => {
  assert.deepEqual(modrinthDependenciesForLoaders(["fabric"]), [
    { project_id: "P7dR8mSH", dependency_type: "required" },
  ]);
});

test("modrinthDependenciesForLoaders is empty for other loaders", () => {
  assert.deepEqual(modrinthDependenciesForLoaders(["forge"]), []);
  assert.deepEqual(modrinthDependenciesForLoaders(["neoforge"]), []);
});

test("curseforgeRelationsForLoader adds Fabric API for fabric", () => {
  assert.deepEqual(curseforgeRelationsForLoader("fabric"), {
    projects: [{ slug: "fabric-api", type: "requiredDependency" }],
  });
});

test("curseforgeRelationsForLoader is undefined for other loaders", () => {
  assert.equal(curseforgeRelationsForLoader("forge"), undefined);
  assert.equal(curseforgeRelationsForLoader("neoforge"), undefined);
});

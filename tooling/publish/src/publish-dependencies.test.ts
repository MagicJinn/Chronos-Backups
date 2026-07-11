import assert from "node:assert/strict";
import test from "node:test";
import {
  curseforgeRelationsForLoader,
  dependencyConfigForTests,
  modrinthDependenciesForLoaders,
} from "./publish-dependencies.js";

const DEPENDENCIES = dependencyConfigForTests();

test("modrinthDependenciesForLoaders adds Fabric API for fabric", () => {
  assert.deepEqual(modrinthDependenciesForLoaders(["fabric"], DEPENDENCIES), [
    { project_id: "P7dR8mSH", dependency_type: "required" },
  ]);
});

test("modrinthDependenciesForLoaders is empty for other loaders", () => {
  assert.deepEqual(modrinthDependenciesForLoaders(["forge"], DEPENDENCIES), []);
  assert.deepEqual(modrinthDependenciesForLoaders(["neoforge"], DEPENDENCIES), []);
});

test("curseforgeRelationsForLoader adds Fabric API for fabric", () => {
  assert.deepEqual(curseforgeRelationsForLoader("fabric", DEPENDENCIES), {
    projects: [{ slug: "fabric-api", type: "requiredDependency" }],
  });
});

test("curseforgeRelationsForLoader is undefined for other loaders", () => {
  assert.equal(curseforgeRelationsForLoader("forge", DEPENDENCIES), undefined);
  assert.equal(curseforgeRelationsForLoader("neoforge", DEPENDENCIES), undefined);
});

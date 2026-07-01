import { readFile } from "node:fs/promises";
import path from "node:path";

// Markers that indicate the beginning and end of the mod page description in the README.md file
const MOD_PAGE_DESCRIPTION_BEGIN_MARKER = "BEGIN MOD PAGE DESCRIPTION";
const MOD_PAGE_DESCRIPTION_END_MARKER = "END MOD PAGE DESCRIPTION";

// Extract the mod page description from the README.md file
export async function getModPageDescription(repoRoot: string): Promise<string> {
  // Get the content of the README.md file and split it into lines
  const content = await readFile(path.join(repoRoot, "README.md"), "utf-8");
  const lines = content.split(/\r?\n/);

  const modPageDescription: string[] = [];
  // Flag to indicate if the current line is part of the mod page description
  let isModPageDescription = false;

  for (const line of lines) {
    if (line.includes(MOD_PAGE_DESCRIPTION_BEGIN_MARKER)) {
      isModPageDescription = true;
      continue;
    } else if (line.includes(MOD_PAGE_DESCRIPTION_END_MARKER)) {
      isModPageDescription = false;
      continue;
    }

    if (isModPageDescription) {
      modPageDescription.push(line);
    }
  }

  // Join the mod page description lines into a single string
  return modPageDescription.join("\n").trim();
}

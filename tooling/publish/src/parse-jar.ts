export interface ParsedJar {
  fileName: string;
  modId: string;
  mcTarget: string;
  modVersion: string;
  loader: string;
}

/**
 * Mod version segment in collected jar names (from gradle.properties).
 * Dotted semver; optional prerelease/build suffix joined with dashes.
 */
const MOD_VERSION_PATTERN = /^\d+\.\d+(?:\.\d+)?(?:[-+][\w.-]+)?$/;

function detectLoaderSuffix(base: string, knownLoaders: ReadonlySet<string>): string | undefined {
  for (const loader of [...knownLoaders].sort((a, b) => b.length - a.length)) {
    if (base.endsWith(`-${loader}`)) {
      return loader;
    }
  }
  return undefined;
}

/**
 * Matches collectAllJars naming: {modId}-{mcTarget}-{modVersion}-{loader}.jar
 *
 * mcTarget may contain dashes (e.g. 1.20.0-1.20.1). modVersion is anchored from the
 * right via semver shape, not by splitting the whole filename on dashes.
 */
export function parseJarFileName(
  fileName: string,
  expectedModId?: string,
  knownLoaders?: ReadonlySet<string>,
): ParsedJar {
  if (!fileName.endsWith(".jar")) {
    throw new Error(`Not a jar file: ${fileName}`);
  }
  if (
    fileName.endsWith("-sources.jar") ||
    fileName.endsWith("-javadoc.jar") ||
    fileName.endsWith("-dev.jar")
  ) {
    throw new Error(`Excluded artifact type: ${fileName}`);
  }

  if (!knownLoaders?.size) {
    throw new Error("knownLoaders is required to parse jar filenames");
  }

  const base = fileName.slice(0, -4);
  const loader = detectLoaderSuffix(base, knownLoaders);
  if (!loader) {
    throw new Error(
      `No known loader suffix in ${fileName}. Known loaders: ${[...knownLoaders].sort().join(", ")}`,
    );
  }

  const beforeLoader = base.slice(0, -(loader.length + 1));
  if (!beforeLoader) {
    throw new Error(`Missing mod id and version segments in ${fileName}`);
  }

  let modId: string;
  let afterModId: string;

  if (expectedModId) {
    const prefix = `${expectedModId}-`;
    if (!beforeLoader.startsWith(prefix)) {
      throw new Error(`Expected mod id "${expectedModId}" prefix in ${fileName}`);
    }
    modId = expectedModId;
    afterModId = beforeLoader.slice(prefix.length);
  } else {
    const firstDash = beforeLoader.indexOf("-");
    if (firstDash <= 0) {
      throw new Error(`Missing mc target and mod version in ${fileName}`);
    }
    modId = beforeLoader.slice(0, firstDash);
    afterModId = beforeLoader.slice(firstDash + 1);
  }

  const segments = afterModId.split("-");
  let modVersion: string | undefined;
  let mcTarget: string | undefined;

  for (let versionSegments = 1; versionSegments < segments.length; versionSegments++) {
    const candidate = segments.slice(-versionSegments).join("-");
    if (!MOD_VERSION_PATTERN.test(candidate)) {
      continue;
    }
    modVersion = candidate;
    mcTarget = segments.slice(0, -versionSegments).join("-");
    break;
  }

  if (!modVersion || !mcTarget) {
    throw new Error(`Could not split mc target and mod version in ${fileName}`);
  }

  return {
    fileName,
    modId,
    mcTarget,
    modVersion,
    loader,
  };
}

/** Unique Modrinth/CurseForge version id per artifact (Modrinth max 32 chars). */
export function artifactVersionNumber(parsed: ParsedJar): string {
  const raw = `${parsed.modVersion}+${parsed.mcTarget}-${parsed.loader}`;
  if (raw.length <= 32) {
    return raw;
  }
  for (let loaderChars = 4; loaderChars >= 1; loaderChars--) {
    const trimmed = `${parsed.modVersion}+${parsed.mcTarget}-${parsed.loader.slice(0, loaderChars)}`;
    if (trimmed.length <= 32) {
      return trimmed;
    }
  }
  throw new Error(`Version number too long for Modrinth (> 32 chars): ${raw}`);
}

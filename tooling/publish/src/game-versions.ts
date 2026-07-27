/** Internal compile-group labels like 1.10.0 often map to platform tags like 1.10. */
const MAJOR_MINOR_ZERO_PATCH = /^(\d+\.\d+)\.0$/;
/** Any patch form 1.10.2 > major.minor 1.10 (CurseForge Bukkit tags are often unpatched). */
const MAJOR_MINOR_ANY_PATCH = /^(\d+\.\d+)\.\d+/;

export interface ResolveSupportedGameVersionsOptions {
  /**
   * When exact and `.0` mapping miss, try stripping any patch (1.13.2 > 1.13).
   * Needed for CurseForge Bukkit, which often lists only major.minor for older lines.
   */
  fallbackToMajorMinor?: boolean;
}

/**
 * Map compile-group supportedVersions to names a platform lists.
 * Exact matches win, otherwise a `.0` patch is tried as major.minor (e.g. 1.10.0 > 1.10).
 */
export function resolveSupportedGameVersions(
  supportedVersions: string[],
  validVersions: ReadonlySet<string>,
  options: ResolveSupportedGameVersionsOptions = {},
): string[] {
  const resolved = new Set<string>();

  for (const version of supportedVersions) {
    if (validVersions.has(version)) {
      resolved.add(version);
      continue;
    }

    const majorMinorFromZero = MAJOR_MINOR_ZERO_PATCH.exec(version)?.[1];
    if (majorMinorFromZero && validVersions.has(majorMinorFromZero)) {
      resolved.add(majorMinorFromZero);
      continue;
    }

    if (options.fallbackToMajorMinor) {
      const majorMinor = MAJOR_MINOR_ANY_PATCH.exec(version)?.[1];
      if (majorMinor && validVersions.has(majorMinor)) {
        resolved.add(majorMinor);
      }
    }
  }

  return [...resolved].sort();
}

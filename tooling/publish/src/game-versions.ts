/** Internal compile-group labels like 1.10.0 often map to platform tags like 1.10. */
const MAJOR_MINOR_ZERO_PATCH = /^(\d+\.\d+)\.0$/;

/**
 * Map compile-group supportedVersions to names a platform lists.
 * Exact matches win, otherwise a `.0` patch is tried as major.minor (e.g. 1.10.0 > 1.10).
 */
export function resolveSupportedGameVersions(
  supportedVersions: string[],
  validVersions: ReadonlySet<string>,
): string[] {
  const resolved = new Set<string>();

  for (const version of supportedVersions) {
    if (validVersions.has(version)) {
      resolved.add(version);
      continue;
    }

    const majorMinor = MAJOR_MINOR_ZERO_PATCH.exec(version)?.[1];
    if (majorMinor && validVersions.has(majorMinor)) {
      resolved.add(majorMinor);
    }
  }

  return [...resolved].sort();
}

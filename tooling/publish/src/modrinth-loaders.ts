import type { PublishPlatformConfig } from "./publish-config.js";

const MODRINTH_V3_API = "https://api.modrinth.com/v3";

interface ModrinthLoaderTag {
  name: string;
  supported_fields: string[];
}

export async function fetchModrinthLoaderFields(userAgent: string): Promise<Map<string, Set<string>>> {
  const response = await fetch(`${MODRINTH_V3_API}/tag/loader`, {
    headers: { "User-Agent": userAgent },
  });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`Modrinth loader tags fetch failed (${response.status}): ${body}`);
  }

  const loaders = (await response.json()) as ModrinthLoaderTag[];
  const fieldsByLoader = new Map<string, Set<string>>();
  for (const loader of loaders) {
    fieldsByLoader.set(loader.name.toLowerCase(), new Set(loader.supported_fields));
  }
  return fieldsByLoader;
}

export function modrinthEnvironmentForLoaders(
  loaders: string[],
  environment: PublishPlatformConfig["modrinth"]["environment"],
  fieldsByLoader: ReadonlyMap<string, ReadonlySet<string>>,
): PublishPlatformConfig["modrinth"]["environment"] | undefined {
  const includeEnvironment = loaders.some((loader) =>
    fieldsByLoader.get(loader.toLowerCase())?.has("environment"),
  );
  return includeEnvironment ? environment : undefined;
}

export class ModrinthLoaderResolver {
  private fieldsByLoader: Map<string, Set<string>> | null = null;

  constructor(private readonly userAgent: string) {}

  async load(): Promise<void> {
    this.fieldsByLoader = await fetchModrinthLoaderFields(this.userAgent);
  }

  environmentForLoaders(
    loaders: string[],
    environment: PublishPlatformConfig["modrinth"]["environment"],
  ): PublishPlatformConfig["modrinth"]["environment"] | undefined {
    if (!this.fieldsByLoader) {
      throw new Error("ModrinthLoaderResolver.load() must be called first");
    }
    return modrinthEnvironmentForLoaders(loaders, environment, this.fieldsByLoader);
  }
}

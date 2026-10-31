import type { VersionJson } from './version-json'

/**
 * Mod loader metadata for Fabric and Quilt.
 *
 * Both publish the same two things: a list of loader versions, and a complete
 * version json for a game version plus a loader version. That profile json is
 * what makes this cheap — it declares `inheritsFrom` the vanilla version and
 * carries the loader's libraries itself, so "installing a loader" is writing
 * one json file and downloading the libraries it lists. Nothing has to be
 * compiled or run, unlike Forge.
 *
 * Every URL and every field name lives here, so the parsing rules can be
 * checked without a network.
 */

export type LoaderKind = 'fabric' | 'quilt'

export interface LoaderVersion {
  version: string
  stable: boolean
}

export const FABRIC_META = 'https://meta.fabricmc.net/v2'
export const QUILT_META = 'https://meta.quiltmc.org/v3'

export function loaderMetaHost(kind: LoaderKind): string {
  return kind === 'fabric' ? FABRIC_META : QUILT_META
}

export function loaderListUrl(kind: LoaderKind): string {
  return `${loaderMetaHost(kind)}/versions/loader`
}

export function loaderGameListUrl(kind: LoaderKind): string {
  return `${loaderMetaHost(kind)}/versions/game`
}

export function loaderProfileUrl(kind: LoaderKind, gameVersion: string, loaderVersion: string): string {
  return `${loaderMetaHost(kind)}/versions/loader/${encodeURIComponent(gameVersion)}/${encodeURIComponent(loaderVersion)}/profile/json`
}

/**
 * Reads a loader list.
 *
 * Fabric labels its releases with a `stable` flag; Quilt publishes no such
 * field, so a plain `x.y.z` version is treated as stable and anything with a
 * pre-release suffix is not.
 */
export function parseLoaderVersions(payload: unknown): LoaderVersion[] {
  if (!Array.isArray(payload)) {
    throw new Error('Loader list is not an array; the metadata endpoint may have changed.')
  }
  const versions: LoaderVersion[] = []
  for (const entry of payload) {
    if (!entry || typeof entry !== 'object') continue
    const record = entry as { version?: unknown; stable?: unknown }
    if (typeof record.version !== 'string') continue
    const stable =
      typeof record.stable === 'boolean' ? record.stable : /^\d+\.\d+\.\d+$/.test(record.version)
    versions.push({ version: record.version, stable })
  }
  if (versions.length === 0) throw new Error('Loader list contained no usable versions.')
  return versions
}

/**
 * Reads a loader profile json and checks the parts the launcher depends on.
 *
 * A profile that does not inherit the version it was requested for would
 * produce an installation that cannot launch, and finding that out at install
 * time is much better than finding it out when the play button fails.
 */
export function parseLoaderProfile(
  payload: unknown,
  expected: { kind: LoaderKind; gameVersion: string; loaderVersion: string }
): VersionJson {
  if (!payload || typeof payload !== 'object') {
    throw new Error('Loader profile is not an object; the metadata endpoint may have changed.')
  }
  const json = payload as VersionJson
  const id = typeof json.id === 'string' && json.id !== ''
    ? json.id
    : `${expected.kind}-loader-${expected.loaderVersion}-${expected.gameVersion}`

  if (json.inheritsFrom !== expected.gameVersion) {
    throw new Error(
      `Loader profile for ${expected.gameVersion} inherits from ${json.inheritsFrom ?? 'nothing'}, ` +
        `which is not the version it was requested for.`
    )
  }
  if (!json.mainClass) {
    throw new Error(`Loader profile ${id} declares no main class.`)
  }
  if (!Array.isArray(json.libraries) || json.libraries.length === 0) {
    throw new Error(`Loader profile ${id} lists no libraries.`)
  }

  return { ...json, id }
}

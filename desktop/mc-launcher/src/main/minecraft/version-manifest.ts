/**
 * Mojang's version manifest: the list of every game version that exists, each
 * with the URL of its own version json.
 *
 * The launcher never guesses a URL for a version json — it asks for it here.
 */

export const VERSION_MANIFEST_URL = 'https://launchermeta.mojang.com/mc/game/version_manifest_v2.json'

export interface ManifestVersion {
  id: string
  /** 'release', 'snapshot', 'old_beta' or 'old_alpha'. */
  type: string
  url: string
  releaseTime: string
}

export interface VersionManifest {
  latest: { release: string | null; snapshot: string | null }
  versions: ManifestVersion[]
}

export function parseVersionManifest(payload: unknown): VersionManifest {
  if (!payload || typeof payload !== 'object') {
    throw new Error('Version manifest is not an object; the endpoint may have changed.')
  }
  const record = payload as { latest?: unknown; versions?: unknown }
  if (!Array.isArray(record.versions)) {
    throw new Error('Version manifest lists no versions; the endpoint may have changed.')
  }

  const versions: ManifestVersion[] = []
  for (const entry of record.versions) {
    if (!entry || typeof entry !== 'object') continue
    const item = entry as { id?: unknown; type?: unknown; url?: unknown; releaseTime?: unknown }
    if (typeof item.id !== 'string' || typeof item.url !== 'string') continue
    versions.push({
      id: item.id,
      type: typeof item.type === 'string' ? item.type : 'release',
      url: item.url,
      releaseTime: typeof item.releaseTime === 'string' ? item.releaseTime : ''
    })
  }
  if (versions.length === 0) throw new Error('Version manifest contained no usable versions.')

  const latest = (record.latest ?? {}) as { release?: unknown; snapshot?: unknown }
  return {
    latest: {
      release: typeof latest.release === 'string' ? latest.release : null,
      snapshot: typeof latest.snapshot === 'string' ? latest.snapshot : null
    },
    versions
  }
}

export function findManifestVersion(
  manifest: VersionManifest,
  id: string
): ManifestVersion | undefined {
  return manifest.versions.find((version) => version.id === id)
}

/**
 * The versions worth showing first: releases by default, newest first (the
 * manifest is already ordered that way, so this keeps that order).
 */
export function filterManifestVersions(
  manifest: VersionManifest,
  options: { type?: 'release' | 'snapshot' | 'all'; search?: string; limit?: number } = {}
): ManifestVersion[] {
  const type = options.type ?? 'release'
  const search = (options.search ?? '').trim().toLowerCase()
  const filtered = manifest.versions.filter((version) => {
    if (type !== 'all' && version.type !== type) return false
    if (search && !version.id.toLowerCase().includes(search)) return false
    return true
  })
  return options.limit ? filtered.slice(0, options.limit) : filtered
}

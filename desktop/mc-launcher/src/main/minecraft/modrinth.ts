import type {
  InstallLoader,
  ModrinthDependency,
  ModrinthFile,
  ModrinthHit,
  ModrinthProject,
  ModrinthSearch,
  ModrinthVersion,
  ResourceKind
} from '@shared/types'

/**
 * Modrinth's public API, read without a key.
 *
 * Modrinth asks every client to send a User-Agent that names it, and answers
 * with a project's versions including the URL, size and sha1/sha512 of each
 * file. That is enough to install a mod or a pack properly: the download goes
 * through the same verified, resumable downloader the game files use, so a mod
 * is checked against the publisher's hash rather than trusted because it arrived.
 *
 * Everything here is reading and deciding; the service does the fetching and
 * writing, which is what keeps this module checkable without a network.
 */

export const MODRINTH_API = 'https://api.modrinth.com/v2'

/** Modrinth reads this to tell one launcher's traffic from another's. */
export function userAgent(launcherName: string, version: string): string {
  return `${launcherName}/${version} (github.com/firefly-sylestia/Tool-Hubs)`
}

/**
 * Which folder a project type's files belong in. A modpack is not a resource —
 * it is an instance — and a plugin belongs on a server, so both are refused.
 */
export function resourceKindForProjectType(projectType: string): ResourceKind | null {
  if (projectType === 'mod') return 'mod'
  if (projectType === 'resourcepack') return 'resourcepack'
  if (projectType === 'shader') return 'shaderpack'
  return null
}

/**
 * The loader a project must list to work in an instance. Vanilla has no loader
 * of its own, and Quilt runs Fabric mods as well as its own.
 */
export function acceptedLoaders(loader: InstallLoader | null): string[] {
  if (loader === null || loader === 'vanilla') return []
  if (loader === 'quilt') return ['quilt', 'fabric']
  return [loader]
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : null
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []
}

function asNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

function parseFile(value: unknown): ModrinthFile | null {
  const file = asRecord(value)
  if (!file) return null
  const url = asString(file.url)
  const filename = asString(file.filename)
  if (!url || !filename) return null
  const hashes = asRecord(file.hashes) ?? {}
  return {
    url,
    filename,
    primary: file.primary === true,
    size: asNumber(file.fileSize),
    sha1: asString(hashes.sha1) ?? undefined,
    sha512: asString(hashes.sha512) ?? undefined
  }
}

/**
 * A search response. A malformed entry is skipped rather than failing the whole
 * page: one bad project should not hide the other nineteen.
 */
export function parseSearchResponse(payload: unknown): ModrinthSearch {
  const record = asRecord(payload)
  if (!record) throw new Error('Modrinth search returned something that is not an object.')
  const hits: ModrinthHit[] = []
  for (const entry of Array.isArray(record.hits) ? record.hits : []) {
    const hit = asRecord(entry)
    if (!hit) continue
    const projectId = asString(hit.project_id)
    const title = asString(hit.title)
    if (!projectId || !title) continue
    hits.push({
      projectId,
      slug: asString(hit.slug) ?? projectId,
      title,
      description: asString(hit.description) ?? '',
      projectType: asString(hit.project_type) ?? 'mod',
      downloads: asNumber(hit.downloads),
      follows: asNumber(hit.follows),
      iconUrl: asString(hit.icon_url),
      gameVersions: asStringArray(hit.versions),
      loaders: asStringArray(hit.categories).filter((category) =>
        ['fabric', 'quilt', 'forge', 'neoforge', 'iris', 'optifine', 'vanilla'].includes(category)
      ),
      categories: asStringArray(hit.categories),
      latestVersion: asString(hit.latest_version)
    })
  }
  return {
    hits,
    totalHits: asNumber(record.total_hits),
    offset: asNumber(record.offset),
    limit: asNumber(record.limit)
  }
}

/** The single-version endpoint returns one object, not a list of one. */
export function parseVersion(payload: unknown): ModrinthVersion | null {
  return parseVersionList([payload])[0] ?? null
}

export function parseVersionList(payload: unknown): ModrinthVersion[] {
  const entries = Array.isArray(payload) ? payload : []
  const versions: ModrinthVersion[] = []
  for (const entry of entries) {
    const version = asRecord(entry)
    if (!version) continue
    const id = asString(version.id)
    const projectId = asString(version.project_id)
    if (!id || !projectId) continue
    const files = (Array.isArray(version.files) ? version.files : [])
      .map(parseFile)
      .filter((file): file is ModrinthFile => file !== null)
    const dependencies: ModrinthDependency[] = []
    for (const raw of Array.isArray(version.dependencies) ? version.dependencies : []) {
      const dependency = asRecord(raw)
      if (!dependency) continue
      const type = asString(dependency.dependency_type)
      dependencies.push({
        projectId: asString(dependency.project_id),
        versionId: asString(dependency.version_id),
        dependencyType:
          type === 'required' || type === 'optional' || type === 'embedded' || type === 'incompatible'
            ? type
            : 'optional'
      })
    }
    versions.push({
      id,
      projectId,
      name: asString(version.name) ?? id,
      versionNumber: asString(version.version_number) ?? id,
      versionType: asString(version.version_type) ?? 'release',
      gameVersions: asStringArray(version.game_versions),
      loaders: asStringArray(version.loaders),
      files,
      dependencies,
      datePublished: asString(version.date_published) ?? ''
    })
  }
  return versions
}

/** The file to download: the one the publisher marked primary, else the first. */
export function primaryFile(version: ModrinthVersion): ModrinthFile | null {
  return version.files.find((file) => file.primary) ?? version.files[0] ?? null
}

/** Required dependencies that are not already in the instance. */
export function requiredDependencies(
  version: ModrinthVersion,
  options: { installedProjectIds: string[]; seenProjectIds?: string[] } = { installedProjectIds: [] }
): ModrinthDependency[] {
  const have = new Set([
    ...options.installedProjectIds.map((id) => id.toLowerCase()),
    ...(options.seenProjectIds ?? []).map((id) => id.toLowerCase())
  ])
  return version.dependencies.filter(
    (dependency) =>
      dependency.dependencyType === 'required' &&
      dependency.projectId !== null &&
      !have.has(dependency.projectId.toLowerCase())
  )
}

function versionSegments(version: string): number[] {
  return version
    .split(/[.\-+]/)
    .map((part) => Number.parseInt(part, 10))
    .filter((part) => Number.isFinite(part))
}

/** Numeric-segment comparison, which is what Minecraft versions need. */
export function compareVersions(left: string, right: string): number {
  const a = versionSegments(left)
  const b = versionSegments(right)
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    const difference = (a[index] ?? 0) - (b[index] ?? 0)
    if (difference !== 0) return difference
  }
  return 0
}

/**
 * Whether a version satisfies a range, or `null` when the range uses something
 * this does not understand.
 *
 * `null` matters: a warning that is wrong is worse than no warning, so an
 * unreadable range is reported as unknown and left alone. The forms handled
 * here are the ones Minecraft mods actually publish — Fabric's
 * `>=1.20 <1.21`, a plain `1.20.1`, a wildcard `1.20.x`, Maven's
 * `[1.20,1.20.4)` that Forge metadata uses, and `~`/`^` prefixes.
 */
export function matchesVersionRange(range: string, version: string): boolean | null {
  const trimmed = range.trim()
  if (trimmed === '' || trimmed === '*') return true

  // Maven ranges: `[1.20,1.20.4)`, `[1.20,)`, `(,1.21]`.
  if (trimmed.startsWith('[') || trimmed.startsWith('(')) {
    const match = /^([[(])\s*([^,\]]*)\s*,\s*([^)\]]*)\s*([)\]])$/.exec(trimmed)
    if (!match) return null
    const [, open, low, high, close] = match
    if (low) {
      const comparison = compareVersions(version, low)
      if (open === '[' ? comparison < 0 : comparison <= 0) return false
    }
    if (high) {
      const comparison = compareVersions(version, high)
      if (close === ']' ? comparison > 0 : comparison >= 0) return false
    }
    return true
  }

  // Space or comma separated comparators are all ANDed, which is how Fabric
  // spells an upper and lower bound at once.
  const parts = trimmed.split(/\s*,\s*|\s+/).filter((part) => part !== '')
  for (const part of parts) {
    const result = satisfiesComparator(part, version)
    if (result === null) return null
    if (!result) return false
  }
  return true
}

function satisfiesComparator(comparator: string, version: string): boolean | null {
  const match = /^(>=|<=|>|<|=|~|\^)?\s*v?(.+)$/.exec(comparator)
  if (!match) return null
  const [, operator = '', bound] = match
  // A bound with no digit at all is not a version — a placeholder, a mod id,
  // something the publisher pasted in. Comparing it against 0.0.0 would answer
  // "no" for a mod that is probably fine, so it is reported as unknown.
  if (!/\d/.test(bound)) return null
  const wildcard = /[xX*]/.test(bound)

  if (wildcard) {
    // `1.20.x` and `1.20.*` mean "1.20 and its patches", which is a prefix match.
    const prefix = bound.replace(/[.\-]?[xX*].*$/, '')
    const equal = version === prefix || version.startsWith(`${prefix}.`)
    return operator === '>' || operator === '>=' ? (operator === '>=' ? equal : !equal) : equal
  }

  const comparison = compareVersions(version, bound)
  switch (operator) {
    case '>':
      return comparison > 0
    case '>=':
      return comparison >= 0
    case '<':
      return comparison < 0
    case '<=':
      return comparison <= 0
    case '~':
    case '^': {
      // `~1.20.1` is "that minor series"; a launcher only has to be close, and
      // being generous here never hides a real mismatch.
      const segments = versionSegments(bound)
      const major = segments[0] ?? 0
      const minor = segments[1] ?? 0
      const target = versionSegments(version)
      if ((target[0] ?? 0) !== major) return false
      if (operator === '~' && (target[1] ?? 0) !== minor) return false
      return comparison >= 0
    }
    case '=':
    default:
      return comparison === 0
  }
}

/**
 * The version to install: newest release that matches, then beta, then alpha —
 * a beta is better than failing to find anything, and it is labelled as one.
 */
export function bestVersion(
  versions: ModrinthVersion[],
  input: { gameVersion?: string | null; loader?: InstallLoader | null }
): ModrinthVersion | null {
  const loaders = acceptedLoaders(input.loader ?? null)
  const matches = versions.filter((version) => {
    if (input.gameVersion && !version.gameVersions.includes(input.gameVersion)) return false
    if (loaders.length > 0 && !version.loaders.some((loader) => loaders.includes(loader))) return false
    return true
  })
  if (matches.length === 0) return null

  const rank: Record<string, number> = { release: 0, beta: 1, alpha: 2 }
  return [...matches].sort((left, right) => {
    const byType = (rank[left.versionType] ?? 3) - (rank[right.versionType] ?? 3)
    if (byType !== 0) return byType
    return right.datePublished.localeCompare(left.datePublished)
  })[0]
}

/** `[["project_type:mod"],["versions:1.21.4"],["categories:fabric"]]`. */
export function searchFacets(input: { kind: ResourceKind; gameVersion?: string | null; loader?: InstallLoader | null }): string {
  const facets: string[][] = []
  facets.push([`project_type:${projectTypeForKind(input.kind)}`])
  if (input.gameVersion) facets.push([`versions:${input.gameVersion}`])
  const loaders = acceptedLoaders(input.loader ?? null)
  if (loaders.length > 0 && input.kind === 'mod') facets.push(loaders.map((loader) => `categories:${loader}`))
  if (input.kind === 'shaderpack') facets.push(['categories:iris', 'categories:optifine', 'categories:vanilla'])
  return JSON.stringify(facets)
}

export function projectTypeForKind(kind: ResourceKind): string {
  if (kind === 'mod') return 'mod'
  if (kind === 'resourcepack') return 'resourcepack'
  return 'shader'
}

/** The query string for a project's versions, filtered the way the API wants. */
export function versionsQuery(input: { gameVersion?: string | null; loader?: InstallLoader | null }): string {
  const params = new URLSearchParams()
  if (input.gameVersion) params.set('game_versions', JSON.stringify([input.gameVersion]))
  const loaders = acceptedLoaders(input.loader ?? null)
  if (loaders.length > 0) params.set('loaders', JSON.stringify(loaders))
  const query = params.toString()
  return query === '' ? '' : `?${query}`
}

export function searchUrl(input: {
  query: string
  kind: ResourceKind
  gameVersion?: string | null
  loader?: InstallLoader | null
  limit?: number
  offset?: number
}): string {
  const params = new URLSearchParams()
  params.set('query', input.query)
  params.set('facets', searchFacets(input))
  params.set('limit', String(input.limit ?? 20))
  params.set('offset', String(input.offset ?? 0))
  return `${MODRINTH_API}/search?${params.toString()}`
}

export function projectVersionsUrl(input: {
  projectIdOrSlug: string
  gameVersion?: string | null
  loader?: InstallLoader | null
}): string {
  // `/project/<id>/version` is the list endpoint; `/versions` does not exist and
  // answers 404, which is how this was found rather than assumed.
  return `${MODRINTH_API}/project/${encodeURIComponent(input.projectIdOrSlug)}/version${versionsQuery(input)}`
}

export function versionUrl(versionId: string): string {
  return `${MODRINTH_API}/version/${encodeURIComponent(versionId)}`
}

/** `?ids=["a","b"]` for the project lookup used to name dependencies. */
export function projectsByIdsUrl(projectIds: string[]): string {
  const params = new URLSearchParams()
  params.set('ids', JSON.stringify(projectIds))
  return `${MODRINTH_API}/projects?${params.toString()}`
}

export function parseProjectList(payload: unknown): ModrinthProject[] {
  const entries = Array.isArray(payload) ? payload : []
  const projects: ModrinthProject[] = []
  for (const entry of entries) {
    const project = asRecord(entry)
    if (!project) continue
    const projectId = asString(project.id)
    if (!projectId) continue
    projects.push({
      projectId,
      slug: asString(project.slug) ?? projectId,
      title: asString(project.title) ?? projectId,
      projectType: asString(project.project_type) ?? 'mod',
      iconUrl: asString(project.icon_url)
    })
  }
  return projects
}

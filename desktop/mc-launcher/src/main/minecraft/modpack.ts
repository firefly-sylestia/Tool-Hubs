import type { InstallLoader, ModpackSummary } from '@shared/types'

/**
 * Modpack archives, in the two formats that exist.
 *
 * Modrinth `.mrpack` is a zip with `modrinth.index.json`: it names the game
 * version, the loader and every file with a URL and hashes, so a launcher can
 * install the whole thing itself. A CurseForge pack is a zip with
 * `manifest.json` that identifies its files by project and file id and gives no
 * URLs at all — downloading those needs CurseForge's API, which is keyed and
 * not ours to call — so that format is imported for its overrides and reported
 * honestly for the rest.
 *
 * All of the reading and deciding happens here, without touching the disk.
 */

export const MODRINTH_INDEX_FILE = 'modrinth.index.json'
export const CURSEFORGE_MANIFEST_FILE = 'manifest.json'

/** Folders that are part of the instance rather than metadata. */
export const INSTANCE_FOLDERS = ['mods', 'resourcepacks', 'shaderpacks', 'config', 'defaultconfigs', 'scripts']

export interface ModpackFile {
  /** Path inside the instance, as the pack spells it. */
  path: string
  url: string
  sha1?: string
  sha512?: string
  size?: number
}

export interface ModpackPlan {
  summary: ModpackSummary
  files: ModpackFile[]
  /** Entry prefixes to extract into the instance, with the prefix stripped. */
  prefixes: string[]
  /** Top-level folders to extract as they are. */
  folders: string[]
  /** Archive entries that are metadata and must not be written. */
  skip: string[]
  /** CurseForge project:file pairs the launcher will not fetch. */
  unresolved: string[]
}

export function detectModpackFormat(entryNames: string[]): 'modrinth' | 'curseforge' | null {
  if (entryNames.includes(MODRINTH_INDEX_FILE)) return 'modrinth'
  if (entryNames.includes(CURSEFORGE_MANIFEST_FILE)) return 'curseforge'
  return null
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : null
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

/** Splits a loader id such as `fabric-0.15.0` or `neoforge-20.4.1`. */
export function parseLoaderId(id: string): { name: string; version: string | null } {
  const match = /^([a-z]+)-(\d.*)$/i.exec(id.trim())
  if (!match) return { name: id.trim().toLowerCase(), version: null }
  return { name: match[1].toLowerCase(), version: match[2] }
}

const SUPPORTED_LOADERS: InstallLoader[] = ['fabric', 'quilt']

export function unsupportedLoaderMessage(name: string): string {
  return (
    `This pack needs ${name}, and this launcher installs vanilla, Fabric and Quilt. ` +
    `Forge and NeoForge publish installers that have to be run themselves, so install the ` +
    `loader with its own installer and import the pack's folder by hand.`
  )
}

/**
 * Reads a Modrinth index.
 *
 * `env.client: "unsupported"` files are the ones the pack excludes from the
 * client, and downloading them would be actively wrong.
 */
export function parseModrinthIndex(payload: unknown): ModpackPlan {
  const record = asRecord(payload)
  if (!record) throw new Error(`${MODRINTH_INDEX_FILE} is not an object.`)

  const dependencies = asRecord(record.dependencies) ?? {}
  const gameVersion = asString(dependencies.minecraft)
  const loaderKey = Object.keys(dependencies).find((key) => key.endsWith('-loader'))
  let loader: InstallLoader | null = null
  let loaderVersion: string | null = null
  if (loaderKey) {
    const name = loaderKey.replace(/-loader$/, '')
    if (!SUPPORTED_LOADERS.includes(name as InstallLoader)) {
      throw new Error(unsupportedLoaderMessage(name))
    }
    loader = name as InstallLoader
    loaderVersion = asString(dependencies[loaderKey])
  }

  const files: ModpackFile[] = []
  const rawFiles = Array.isArray(record.files) ? record.files : []
  for (const entry of rawFiles) {
    const file = asRecord(entry)
    if (!file) continue
    const path = asString(file.path)
    const downloads = Array.isArray(file.downloads) ? file.downloads : []
    const url = downloads.map(asString).find((candidate) => candidate !== null) ?? null
    if (!path) continue
    const env = asRecord(file.env)
    if (asString(env?.client) === 'unsupported') continue
    if (!url) continue
    const hashes = asRecord(file.hashes) ?? {}
    files.push({
      path,
      url,
      sha1: asString(hashes.sha1) ?? undefined,
      sha512: asString(hashes.sha512) ?? undefined,
      size: typeof file.fileSize === 'number' ? file.fileSize : undefined
    })
  }

  const summary: ModpackSummary = {
    format: 'modrinth',
    name: asString(record.name) ?? 'Imported modpack',
    version: asString(record.versionId),
    gameVersion,
    loader,
    loaderVersion,
    downloadable: files.length,
    bundled: 0,
    unresolved: []
  }

  return {
    summary,
    files,
    prefixes: ['overrides/', 'client-overrides/'],
    folders: [],
    skip: [MODRINTH_INDEX_FILE],
    unresolved: []
  }
}

/**
 * Reads a CurseForge manifest. Nothing in it carries a URL, so every file it
 * names is reported as needing a source this launcher does not use rather than
 * being silently skipped.
 */
export function parseCurseForgeManifest(payload: unknown): ModpackPlan {
  const record = asRecord(payload)
  if (!record) throw new Error(`${CURSEFORGE_MANIFEST_FILE} is not an object.`)

  const minecraft = asRecord(record.minecraft) ?? {}
  const gameVersion = asString(minecraft.version)
  const modLoaders = Array.isArray(minecraft.modLoaders) ? minecraft.modLoaders : []
  const primary = modLoaders
    .map((entry) => asRecord(entry))
    .find((entry) => entry?.primary === true) ?? asRecord(modLoaders[0])

  let loader: InstallLoader | null = null
  let loaderVersion: string | null = null
  const loaderId = asString(primary?.id)
  if (loaderId) {
    const parsed = parseLoaderId(loaderId)
    if (parsed.name !== 'vanilla' && parsed.name !== 'minecraft') {
      if (!SUPPORTED_LOADERS.includes(parsed.name as InstallLoader)) {
        throw new Error(unsupportedLoaderMessage(parsed.name))
      }
      loader = parsed.name as InstallLoader
      loaderVersion = parsed.version
    }
  }

  const unresolved: string[] = []
  const rawFiles = Array.isArray(record.files) ? record.files : []
  for (const entry of rawFiles) {
    const file = asRecord(entry)
    if (!file) continue
    if (file.required === false) continue
    const project = file.projectID ?? file.projectId
    const fileId = file.fileID ?? file.fileId
    if (project === undefined || fileId === undefined) continue
    unresolved.push(`${project}:${fileId}`)
  }

  const overrides = asString(record.overrides) ?? 'overrides'

  const summary: ModpackSummary = {
    format: 'curseforge',
    name: asString(record.name) ?? 'Imported modpack',
    version: asString(record.version),
    gameVersion,
    loader,
    loaderVersion,
    downloadable: 0,
    bundled: 0,
    unresolved
  }

  return {
    summary,
    files: [],
    prefixes: [`${overrides}/`],
    folders: [...INSTANCE_FOLDERS],
    skip: [CURSEFORGE_MANIFEST_FILE, 'modrinth.index.json'],
    unresolved
  }
}

/** Which archive entries belong inside the instance, and under what name. */
export function instancePathFor(entryName: string, plan: ModpackPlan): string | null {
  if (plan.skip.includes(entryName)) return null
  if (entryName.endsWith('/')) return null
  for (const prefix of plan.prefixes) {
    if (entryName.startsWith(prefix)) return entryName.slice(prefix.length)
  }
  for (const folder of plan.folders) {
    if (entryName.startsWith(`${folder}/`)) return entryName
  }
  return null
}

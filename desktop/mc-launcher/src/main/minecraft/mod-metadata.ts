import { readFileSync, statSync } from 'node:fs'
import type { InstallLoader } from '@shared/types'
import { listZipEntries, readZipEntry } from './zip'

/**
 * What a mod's jar says about itself.
 *
 * A folder of jars tells you nothing: `sodium-fabric-mc1.21.4-0.6.13.jar` is a
 * file name someone chose, while the jar's own metadata is what the loader
 * reads. Reading it turns "a jar is in here" into "Sodium 0.6.13, for 1.21.4,
 * Fabric" — and it is the difference between a wrong-loader mod being flagged
 * before a launch fails and being hunted for afterwards.
 *
 * Four metadata formats exist in the wild, one per loader family, plus the
 * legacy `mcmod.info` that old Forge mods carry. Everything is read from the
 * jar's bytes, so nothing is executed and nothing is extracted.
 */

/** The largest jar worth reading metadata out of, so a huge file is not slurped. */
const METADATA_SCAN_LIMIT = 16 * 1024 * 1024

/** Loader family, as the metadata itself declares it. */
export type ModLoaderFamily = 'fabric' | 'quilt' | 'forge' | 'neoforge'

export interface ModMetadata {
  loader: ModLoaderFamily
  /** modId, when the file declares one. */
  id: string | null
  name: string | null
  version: string | null
  /** The game version or range the metadata asks for, exactly as written. */
  requires: string | null
  authors: string[]
  /** True for `mcmod.info`, which predates Forge's toml metadata. */
  legacy: boolean
}

const METADATA_FILES: { name: string; loader: ModLoaderFamily; legacy?: boolean }[] = [
  { name: 'fabric.mod.json', loader: 'fabric' },
  { name: 'quilt.mod.json', loader: 'quilt' },
  { name: 'META-INF/neoforge.mods.toml', loader: 'neoforge' },
  { name: 'META-INF/mods.toml', loader: 'forge' },
  { name: 'mcmod.info', loader: 'forge', legacy: true }
]

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : null
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null
}

function asStringList(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((entry): entry is string => typeof entry === 'string')
  const single = asString(value)
  return single === null ? [] : [single]
}

function unquote(raw: string): string {
  const quoted = /^"(.*)"$/.exec(raw) ?? /^'(.*)'$/.exec(raw)
  const value = quoted ? quoted[1] : raw.split('#')[0]
  return value.trim()
}

/**
 * Every `key = value` in a toml file, first occurrence winning.
 *
 * The metadata a launcher cares about is flat and quoted, and a real toml
 * parser would be a dependency for five keys. Anything this cannot read comes
 * back absent rather than guessed at, which is why a version is never taken
 * from the file name.
 */
export function readTomlValues(contents: string): Map<string, string> {
  const values = new Map<string, string>()
  for (const line of contents.split(/\r?\n/)) {
    const match = /^\s*([A-Za-z0-9_.]+)\s*=\s*(.+?)\s*$/.exec(line)
    if (!match || values.has(match[1])) continue
    const value = unquote(match[2])
    if (value !== '') values.set(match[1], value)
  }
  return values
}

/**
 * The Minecraft requirement out of a Forge or NeoForge `[[dependencies.x]]`
 * table. Those are list tables, so the block is scanned for the one whose
 * `modId` is `minecraft` and its `versionRange` is read from there.
 */
export function forgeMinecraftRange(contents: string): string | null {
  const pattern = /\[\[dependencies[^\]]*\]\]([\s\S]*?)(?=\n\s*\[\[|\s*$)/g
  let match = pattern.exec(contents)
  while (match) {
    const values = readTomlValues(match[1])
    if (values.get('modId') === 'minecraft') return values.get('versionRange') ?? null
    match = pattern.exec(contents)
  }
  return null
}

function parseForgeMetadata(contents: string, loader: 'forge' | 'neoforge'): ModMetadata {
  const values = readTomlValues(contents)
  const authors = values.get('authors')
  return {
    loader,
    id: values.get('modId') ?? null,
    name: values.get('displayName') ?? values.get('modId') ?? null,
    version: values.get('version') ?? null,
    requires: forgeMinecraftRange(contents),
    authors: authors ? authors.split(',').map((author) => author.trim()).filter(Boolean) : [],
    legacy: false
  }
}

function parseLegacyForgeMetadata(payload: unknown): ModMetadata | null {
  const first = asRecord((Array.isArray(payload) ? payload : [payload])[0])
  if (!first) return null
  return {
    loader: 'forge',
    id: asString(first.modid) ?? asString(first.modId),
    name: asString(first.name) ?? asString(first.modid),
    version: asString(first.version),
    requires: asString(first.mcversion),
    authors: asStringList(first.authorList ?? first.authors),
    legacy: true
  }
}

function parseFabricMetadata(payload: unknown): ModMetadata | null {
  const record = asRecord(payload)
  if (!record) return null
  const depends = asRecord(record.depends) ?? {}
  return {
    loader: 'fabric',
    id: asString(record.id),
    name: asString(record.name) ?? asString(record.id),
    version: asString(record.version),
    requires: asString(depends.minecraft),
    authors: asStringList(record.authors),
    legacy: false
  }
}

function parseQuiltMetadata(payload: unknown): ModMetadata | null {
  const record = asRecord(payload)
  if (!record) return null
  const loader = asRecord(record.quilt_loader) ?? {}
  const metadata = asRecord(loader.metadata) ?? {}
  const minecraft = (asRecord(loader.depends) ?? {}).minecraft
  const requires = Array.isArray(minecraft)
    ? asString(asRecord(minecraft[0])?.version)
    : asString(minecraft)
  return {
    loader: 'quilt',
    id: asString(loader.id),
    name: asString(metadata.name) ?? asString(loader.id),
    version: asString(metadata.version) ?? asString(loader.version),
    requires,
    authors: asStringList(metadata.contributors),
    legacy: false
  }
}

/**
 * Reads the metadata out of a jar already in memory.
 *
 * A jar that is not a mod — a resource pack, an empty file, a half-downloaded
 * one — returns null, and that is a normal answer rather than an error.
 */
export function readModMetadata(buffer: Buffer): ModMetadata | null {
  let entries
  try {
    entries = listZipEntries(buffer)
  } catch {
    return null
  }

  for (const candidate of METADATA_FILES) {
    const entry = entries.find((item) => item.name === candidate.name)
    if (!entry) continue
    try {
      const contents = readZipEntry(buffer, entry).toString('utf8')
      if (candidate.name.endsWith('.toml')) {
        return parseForgeMetadata(contents, candidate.loader as 'forge' | 'neoforge')
      }
      const payload: unknown = JSON.parse(contents)
      if (candidate.legacy) return parseLegacyForgeMetadata(payload)
      if (candidate.loader === 'quilt') return parseQuiltMetadata(payload)
      return parseFabricMetadata(payload)
    } catch {
      // A metadata file that cannot be read is the same as none. The jar is
      // still listed, because the file is in the folder whatever it says.
      return null
    }
  }
  return null
}

/** Reads one jar from disk, without loading more of it than it needs. */
export function readModMetadataFromFile(path: string): ModMetadata | null {
  try {
    const size = statSync(path).size
    if (size === 0 || size > METADATA_SCAN_LIMIT) return null
    return readModMetadata(readFileSync(path))
  } catch {
    return null
  }
}

/**
 * What is worth saying about a mod before it is launched.
 *
 * Only things that are actually wrong produce a warning, and a version range
 * this cannot read produces none: a wrong warning about someone's mod is worse
 * than silence. A mod whose loader is not installable here is flagged by name,
 * because that is the case where the game will fail for a reason no log line
 * explains.
 */
export function modWarnings(
  metadata: ModMetadata,
  target: { versionId: string | null; loader: InstallLoader | null; rangeMatcher: (range: string, version: string) => boolean | null }
): string[] {
  const warnings: string[] = []

  if (metadata.loader === 'forge' || metadata.loader === 'neoforge') {
    warnings.push(
      metadata.legacy
        ? 'This is a legacy Forge mod, which a Fabric or Quilt instance cannot load.'
        : `This is a ${metadata.loader === 'neoforge' ? 'NeoForge' : 'Forge'} mod, which a Fabric or Quilt instance cannot load.`
    )
  } else if (target.loader === 'fabric' && metadata.loader === 'quilt') {
    warnings.push('This is a Quilt mod, and the instance runs Fabric.')
  }

  if (metadata.requires && target.versionId) {
    const matches = target.rangeMatcher(metadata.requires, target.versionId)
    if (matches === false) {
      warnings.push(`It asks for Minecraft ${metadata.requires}, and this instance is ${target.versionId}.`)
    }
  }

  return warnings
}

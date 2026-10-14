import { join } from 'node:path'
import type { RuleContext } from './rules'
import { rulesAllow } from './rules'
import type { Library, LibraryArtifact } from './version-json'

/** The default repository, used when a library declares no download of its own. */
export const LIBRARIES_HOST = 'https://libraries.minecraft.net'

export interface ClasspathEntry {
  name: string
  /** Path relative to the libraries directory. */
  relativePath: string
  native: boolean
  extractExclude: string[]
  /** Where to fetch it from. Absent only when the version json omits both a download and a base url. */
  url?: string
  sha1?: string
  size?: number
}

function artifactUrl(library: Library, relativePath: string): string | undefined {
  const base = (library.url ?? LIBRARIES_HOST).replace(/\/+$/, '')
  return `${base}/${relativePath}`
}

export interface ResolvedClasspath {
  classpath: ClasspathEntry[]
  natives: ClasspathEntry[]
}

/** `group:artifact:version[:classifier][@ext]` -> `group/artifact/version/file`. */
export function mavenPath(name: string): string {
  const [coordinates, ext = 'jar'] = name.split('@')
  const parts = coordinates.split(':')
  const group = parts[0]
  const artifact = parts[1]
  const version = parts[2]
  const classifier = parts[3]
  const file = `${artifact}-${version}${classifier ? `-${classifier}` : ''}.${ext}`
  return `${group.replace(/\./g, '/')}/${artifact}/${version}/${file}`
}

function nativeClassifier(library: Library, ctx: RuleContext): string | null {
  const declared = library.natives?.[ctx.os]
  if (!declared) return null
  return declared.replace('${arch}', ctx.arch === 'x86' ? '32' : '64')
}

interface LibraryRecord {
  name: string
  artifact: LibraryArtifact | null
  /** Classifier name (e.g. `natives-linux`) to the artifact it names, if any. */
  natives: Map<string, LibraryArtifact | null>
  extractExclude: string[]
  url?: string
}

/**
 * Resolves a version's libraries for one platform.
 *
 * Rules are evaluated before anything is deduplicated, and repeated entries for
 * the same coordinate are merged rather than dropped. Both matter for real
 * version jsons: 1.16.5 lists `org.lwjgl:lwjgl-glfw:3.2.2` twice, the first
 * entry without a `natives` field and the second with one, so taking the first
 * entry and moving on loses the native library the game needs to start — the
 * game then fails at runtime with an unloadable native, which is a confusing
 * way to learn that a download was skipped.
 */
export function resolveLibraries(libraries: Library[], ctx: RuleContext): ResolvedClasspath {
  const order: string[] = []
  const records = new Map<string, LibraryRecord>()

  for (const library of libraries) {
    // An entry that does not apply on this platform must not shadow a later one
    // that does.
    if (!rulesAllow(library.rules, ctx)) continue

    let record = records.get(library.name)
    if (!record) {
      record = {
        name: library.name,
        artifact: null,
        natives: new Map(),
        extractExclude: [],
        url: library.url
      }
      records.set(library.name, record)
      order.push(library.name)
    }
    if (!record.artifact && library.downloads?.artifact) record.artifact = library.downloads.artifact
    if (record.extractExclude.length === 0 && library.extract?.exclude) {
      record.extractExclude = library.extract.exclude
    }
    if (!record.url && library.url) record.url = library.url

    const classifier = nativeClassifier(library, ctx)
    if (classifier && !record.natives.has(classifier)) {
      record.natives.set(classifier, library.downloads?.classifiers?.[classifier] ?? null)
    }
  }

  const classpath: ClasspathEntry[] = []
  const natives: ClasspathEntry[] = []

  for (const name of order) {
    const record = records.get(name)!
    const asLibrary: Library = { name: record.name, url: record.url }

    for (const [classifier, classified] of record.natives) {
      const relativePath = classified?.path ?? mavenPath(`${record.name}:${classifier}`)
      natives.push({
        name: `${record.name}:${classifier}`,
        relativePath,
        native: true,
        extractExclude: record.extractExclude,
        url: classified?.url ?? artifactUrl(asLibrary, relativePath),
        sha1: classified?.sha1,
        size: classified?.size
      })
    }

    // A library that declares natives and nothing else contributes no classpath
    // entry; one that declares neither is still fetched from its own repository
    // by maven path, which is how Forge-era version jsons spell their libraries.
    if (!record.artifact && record.natives.size > 0) continue
    const relativePath = record.artifact?.path ?? mavenPath(record.name)
    classpath.push({
      name: record.name,
      relativePath,
      native: false,
      extractExclude: record.extractExclude,
      url: record.artifact?.url ?? artifactUrl(asLibrary, relativePath),
      sha1: record.artifact?.sha1,
      size: record.artifact?.size
    })
  }

  return { classpath, natives }
}

export function classpathString(entries: ClasspathEntry[], librariesDir: string, jar: string | null, separator: string): string {
  const parts = [...(jar ? [jar] : []), ...entries.map((entry) => join(librariesDir, entry.relativePath))]
  return parts.join(separator)
}

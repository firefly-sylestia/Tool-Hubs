import { join } from 'node:path'
import type { RuleContext } from './rules'
import { rulesAllow } from './rules'
import type { Library } from './version-json'

export interface ClasspathEntry {
  name: string
  /** Path relative to the libraries directory. */
  relativePath: string
  native: boolean
  extractExclude: string[]
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

export function resolveLibraries(libraries: Library[], ctx: RuleContext): ResolvedClasspath {
  const seen = new Set<string>()
  const classpath: ClasspathEntry[] = []
  const natives: ClasspathEntry[] = []

  for (const library of libraries) {
    if (seen.has(library.name)) continue
    seen.add(library.name)
    if (!rulesAllow(library.rules, ctx)) continue

    const extractExclude = library.extract?.exclude ?? []
    const classifier = nativeClassifier(library, ctx)

    if (classifier) {
      const classified = library.downloads?.classifiers?.[classifier]
      natives.push({
        name: `${library.name}:${classifier}`,
        relativePath: classified?.path ?? mavenPath(`${library.name}:${classifier}`),
        native: true,
        extractExclude
      })
      // A library that declares natives as its only artifact contributes no
      // classpath entry.
      if (!library.downloads?.artifact) continue
    }

    classpath.push({
      name: library.name,
      relativePath: library.downloads?.artifact?.path ?? mavenPath(library.name),
      native: false,
      extractExclude
    })
  }

  return { classpath, natives }
}

export function classpathString(entries: ClasspathEntry[], librariesDir: string, jar: string | null, separator: string): string {
  const parts = [...(jar ? [jar] : []), ...entries.map((entry) => join(librariesDir, entry.relativePath))]
  return parts.join(separator)
}

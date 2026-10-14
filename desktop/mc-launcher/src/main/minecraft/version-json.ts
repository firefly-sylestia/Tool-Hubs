import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import type { Rule } from './rules'

export interface ConditionalArgument {
  rules?: Rule[]
  value: string | string[]
}

export type VersionArgument = string | ConditionalArgument

export interface LibraryArtifact {
  path: string
  url?: string
  sha1?: string
  size?: number
}

export interface Library {
  name: string
  downloads?: {
    artifact?: LibraryArtifact
    classifiers?: Record<string, LibraryArtifact>
  }
  natives?: Record<string, string>
  extract?: { exclude?: string[] }
  rules?: Rule[]
  url?: string
}

export interface VersionJson {
  id: string
  inheritsFrom?: string
  type?: string
  mainClass?: string
  assets?: string
  assetIndex?: { id?: string; url?: string; sha1?: string; size?: number; totalSize?: number }
  downloads?: { client?: LibraryArtifact; server?: LibraryArtifact }
  javaVersion?: { majorVersion?: number; component?: string }
  minecraftArguments?: string
  arguments?: { game?: VersionArgument[]; jvm?: VersionArgument[] }
  libraries?: Library[]
}

/** A version json merged with everything it inherits from. */
export interface MergedVersion {
  id: string
  mainClass: string | null
  assetsIndex: string | null
  javaMajorVersion: number | null
  gameArguments: VersionArgument[]
  jvmArguments: VersionArgument[]
  minecraftArguments: string | null
  libraries: Library[]
  /** Absolute path to the client jar this version should run. */
  clientJar: string | null
}

export interface ResolvedVersion {
  id: string
  /** Directory holding this version: <gameDir>/versions/<id>. */
  dir: string
  jsonPath: string
  /** Child first, then each ancestor. */
  chain: VersionJson[]
  merged: MergedVersion
}

export function versionDir(gameDir: string, id: string): string {
  return join(gameDir, 'versions', id)
}

export function readVersionJson(path: string): VersionJson {
  let raw: string
  try {
    raw = readFileSync(path, 'utf8')
  } catch (error) {
    throw new Error(`Cannot read version json at ${path}: ${(error as Error).message}`)
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch (error) {
    throw new Error(`Version json at ${path} is not valid JSON: ${(error as Error).message}`)
  }
  if (!parsed || typeof parsed !== 'object') {
    throw new Error(`Version json at ${path} is not an object`)
  }
  return parsed as VersionJson
}

/**
 * Walks `inheritsFrom` from the requested version up to the base version.
 *
 * Merge precedence is child-over-parent for scalar fields. Libraries are
 * concatenated child-first, so a duplicate coordinate resolves to the child's
 * artifact. Argument lists are concatenated parent-first: the base version
 * contributes the placeholders the game needs, and a mod loader's extra
 * arguments follow.
 */
export function resolveVersionChain(gameDir: string, id: string): ResolvedVersion {
  const chain: VersionJson[] = []
  const seen = new Set<string>()
  let currentId: string | undefined = id
  let firstDir = ''

  while (currentId) {
    if (seen.has(currentId)) {
      throw new Error(`Version ${id} has a circular inheritsFrom chain at ${currentId}`)
    }
    seen.add(currentId)
    const dir = versionDir(gameDir, currentId)
    const jsonPath = join(dir, `${currentId}.json`)
    if (!existsSync(jsonPath)) {
      if (chain.length === 0) {
        throw new Error(
          `Version ${currentId} is not installed: expected ${jsonPath}. ` +
            `Install it from the Installed versions page, or with the official launcher, then rescan.`
        )
      }
      throw new Error(
        `Version ${id} inherits from ${currentId}, which is not installed: expected ${jsonPath}.`
      )
    }
    const json = readVersionJson(jsonPath)
    if (chain.length === 0) firstDir = dir
    chain.push(json)
    currentId = json.inheritsFrom
  }

  const child = chain[0]
  const parent = chain[1]

  const merged: MergedVersion = {
    id: child.id,
    mainClass: child.mainClass ?? parent?.mainClass ?? null,
    assetsIndex: child.assetIndex?.id ?? child.assets ?? parent?.assetIndex?.id ?? parent?.assets ?? null,
    javaMajorVersion: child.javaVersion?.majorVersion ?? parent?.javaVersion?.majorVersion ?? null,
    gameArguments: [...(parent?.arguments?.game ?? []), ...(child.arguments?.game ?? [])],
    jvmArguments: [...(parent?.arguments?.jvm ?? []), ...(child.arguments?.jvm ?? [])],
    minecraftArguments: child.minecraftArguments ?? parent?.minecraftArguments ?? null,
    libraries: [...(child.libraries ?? []), ...(parent?.libraries ?? [])],
    clientJar: resolveClientJar(gameDir, chain)
  }

  return { id, dir: firstDir, jsonPath: join(firstDir, `${id}.json`), chain, merged }
}

/**
 * The client jar to run: this version's own jar when it ships one (mod loaders
 * do), otherwise the first ancestor that has one.
 */
function resolveClientJar(gameDir: string, chain: VersionJson[]): string | null {
  for (const version of chain) {
    const candidate = join(versionDir(gameDir, version.id), `${version.id}.jar`)
    if (existsSync(candidate)) return candidate
  }
  return null
}

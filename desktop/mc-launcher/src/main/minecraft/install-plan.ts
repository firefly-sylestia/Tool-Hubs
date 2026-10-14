import { join } from 'node:path'
import { resolveLibraries } from './classpath'
import type { RuleContext } from './rules'
import type { VersionJson } from './version-json'
import { versionDir } from './version-json'

/**
 * Turns a version (plus everything it inherits from) into the exact list of
 * files an installation needs.
 *
 * This is deliberately pure: it takes the parsed version jsons and the asset
 * index, and returns URLs and destinations. The installer does the fetching, so
 * the interesting part — which files a version needs, where they go and what
 * they must hash to — can be checked without a network.
 */

export const RESOURCES_HOST = 'https://resources.download.minecraft.net'

export interface PlannedFile {
  url: string
  dest: string
  sha1?: string
  size?: number
  label: string
}

export interface NativeJar {
  path: string
  url: string
  sha1?: string
  size?: number
  exclude: string[]
  label: string
}

export interface VersionFilePlan {
  files: PlannedFile[]
  nativeJars: NativeJar[]
}

export interface AssetIndexJson {
  objects: Record<string, { hash: string; size: number }>
}

/** `<gameDir>/versions/<id>/<id>-natives`, which is where the launch plan looks. */
export function nativesDirFor(gameDir: string, versionId: string): string {
  return join(versionDir(gameDir, versionId), `${versionId}-natives`)
}

/** The client jar a version ships, or null when it only inherits one. */
export function clientJarFor(gameDir: string, version: VersionJson): PlannedFile | null {
  const client = version.downloads?.client
  if (!client?.url) return null
  return {
    url: client.url,
    dest: join(versionDir(gameDir, version.id), `${version.id}.jar`),
    sha1: client.sha1,
    size: client.size,
    label: `${version.id} client jar`
  }
}

/**
 * Files for one version json: the client jar it ships and every library its
 * rules allow on this platform. A library with no `downloads.artifact` is still
 * planned, from its repository and its maven path, which is how Forge-era
 * version jsons declare their libraries.
 */
export function planVersionFiles(gameDir: string, version: VersionJson, ctx: RuleContext): VersionFilePlan {
  const files: PlannedFile[] = []
  const nativeJars: NativeJar[] = []

  const client = clientJarFor(gameDir, version)
  if (client) files.push(client)

  const { classpath, natives } = resolveLibraries(version.libraries ?? [], ctx)
  const librariesDir = join(gameDir, 'libraries')

  for (const entry of classpath) {
    if (!entry.url) continue
    files.push({
      url: entry.url,
      dest: join(librariesDir, entry.relativePath),
      sha1: entry.sha1,
      size: entry.size,
      label: entry.name
    })
  }

  for (const entry of natives) {
    if (!entry.url) continue
    nativeJars.push({
      path: join(librariesDir, entry.relativePath),
      url: entry.url,
      sha1: entry.sha1,
      size: entry.size,
      exclude: entry.extractExclude,
      label: entry.name
    })
  }

  return { files, nativeJars }
}

/**
 * Every asset object in an index, addressed by its own hash.
 *
 * Minecraft stores assets content-addressed under `assets/objects/<first two
 * hex digits>/<hash>`, which is why one index entry is all the information a
 * download needs: the hash is the path, the file name and the checksum.
 */
export function planAssetObjects(gameDir: string, index: AssetIndexJson): PlannedFile[] {
  const files: PlannedFile[] = []
  for (const [name, object] of Object.entries(index.objects ?? {})) {
    if (!object?.hash) continue
    const prefix = object.hash.slice(0, 2)
    files.push({
      url: `${RESOURCES_HOST}/${prefix}/${object.hash}`,
      dest: join(gameDir, 'assets', 'objects', prefix, object.hash),
      sha1: object.hash,
      size: object.size,
      label: `asset ${name}`
    })
  }
  return files
}

/**
 * Concatenates plans child-first and keeps the first entry for a destination.
 *
 * A loader repeats libraries its base version already lists, and the child's
 * copy is the one the game will load, so a later duplicate has to lose.
 */
export function mergePlans(plans: VersionFilePlan[]): VersionFilePlan {
  const files: PlannedFile[] = []
  const nativeJars: NativeJar[] = []
  const seenFiles = new Set<string>()
  const seenJars = new Set<string>()

  for (const plan of plans) {
    for (const file of plan.files) {
      if (seenFiles.has(file.dest)) continue
      seenFiles.add(file.dest)
      files.push(file)
    }
    for (const jar of plan.nativeJars) {
      if (seenJars.has(jar.path)) continue
      seenJars.add(jar.path)
      nativeJars.push(jar)
    }
  }

  return { files, nativeJars }
}

/**
 * True when every file of the plan is already on disk with the right hash, so
 * an install that has nothing to do says so instead of re-reading 3000 assets.
 */
export function missingFrom(files: PlannedFile[], exists: (dest: string) => boolean): PlannedFile[] {
  return files.filter((file) => !exists(file.dest))
}

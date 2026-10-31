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
 *
 * A hash is planned once however many names point at it, because this is where
 * the addressing pays off and where it bit: real indexes do list the same
 * content twice — 1.12.2 has both `icons/icon_16x16.png` and
 * `minecraft/icons/icon_16x16.png` for one hash, and 1.20.1 has a sound shared
 * by two mobs — so the plan held the same destination twice and two downloads
 * raced for one file, one of them resuming a `.part` its neighbour had already
 * finished and being answered with HTTP 416. Found by installing a real version
 * and starting it, not by reading the index.
 */
export function planAssetObjects(gameDir: string, index: AssetIndexJson): PlannedFile[] {
  const files: PlannedFile[] = []
  const seen = new Set<string>()
  for (const [name, object] of Object.entries(index.objects ?? {})) {
    if (!object?.hash) continue
    if (seen.has(object.hash)) continue
    seen.add(object.hash)
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
 * Everything one version chain needs, as a single plan.
 *
 * A chain rather than one json, because that is what a launch resolves: the
 * loader's json first, then the game version it inherits from. Both the install
 * and the check before a launch go through here, so "what a version needs" has
 * exactly one answer — a launch that repaired an installation against a
 * different list than the installer wrote would be a slow, confusing bug.
 *
 * The asset index is passed in rather than fetched: the caller already has it
 * (or has decided not to download the 500 MB of objects it describes), and an
 * index that is on disk is one fewer request before a launch.
 */
export function planInstallation(input: {
  gameDir: string
  /** Child first, as `resolveVersionChain` returns it. */
  chain: VersionJson[]
  ctx: RuleContext
  assetIndex?: AssetIndexJson | null
}): { files: PlannedFile[]; nativeJars: NativeJar[]; assetIndex: VersionJson['assetIndex'] & { id: string } | null } {
  const merged = mergePlans(input.chain.map((version) => planVersionFiles(input.gameDir, version, input.ctx)))
  const files: PlannedFile[] = [...merged.files]

  // The game version declares the asset index; a loader profile inheriting from
  // it does not, so the first entry that names one is the one that owns it.
  const owner = input.chain.find((version) => version.assetIndex?.url) ?? null
  const declared = owner?.assetIndex ?? null
  const indexId = declared?.id ?? owner?.assets ?? null
  const assetIndex = declared?.url && indexId ? { ...declared, id: indexId } : null

  if (assetIndex) {
    files.push({
      url: assetIndex.url!,
      dest: join(input.gameDir, 'assets', 'indexes', `${assetIndex.id}.json`),
      sha1: assetIndex.sha1,
      size: assetIndex.size,
      label: `asset index ${assetIndex.id}`
    })
    if (input.assetIndex) files.push(...planAssetObjects(input.gameDir, input.assetIndex))
  }

  return { files, nativeJars: merged.nativeJars, assetIndex }
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

/**
 * The files a launch has to fetch before it can start, from what is on disk.
 *
 * A file counts as present when it is there and its size is what the manifest
 * declared. That is deliberately a size check and not a hash check: hashing
 * 3000 assets and a 40 MB client jar before every launch would take longer than
 * starting the game, and it is not needed either — an asset's file name *is* its
 * sha1, so its existence proves its contents, and a jar from a half-finished
 * download has the wrong size and is caught here. The hash is still what the
 * download itself is verified against.
 */
export function needsRefresh(
  files: PlannedFile[],
  sizeOf: (path: string) => number | null
): PlannedFile[] {
  return files.filter((file) => {
    const size = sizeOf(file.dest)
    if (size === null) return true
    if (typeof file.size === 'number' && file.size > 0 && size !== file.size) return true
    return false
  })
}

/**
 * Mojang's own Java runtimes.
 *
 * The official launcher does not need a JDK on the machine: it downloads the
 * runtime a version asks for into that game directory, and so can this. The
 * index names one manifest per platform and component, and the manifest lists
 * every file in the runtime with two copies of it — one LZMA-compressed, one
 * raw. The raw copy is the one used here: decompressing LZMA would mean a
 * dependency for a saving of about a third, and the download is verified against
 * the same sha1 either way.
 *
 * Everything below is reading and deciding, so it can be checked without a
 * network or a JVM.
 */

export const JAVA_RUNTIME_INDEX_URL =
  'https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json'

export interface JavaRuntimeVersionInfo {
  /** Mojang's own version of the component, e.g. `21.0.5.11.1`. */
  name: string
  released: string
  manifestUrl: string
  manifestSha1: string | null
  /** Whether Mojang has finished publishing it; an unavailable one is skipped. */
  available: boolean
}

/** The key this platform's runtimes live under in the index. */
export function runtimeOsKey(platform: NodeJS.Platform, arch: string): string | null {
  if (platform === 'win32') {
    if (arch === 'arm64') return 'windows-arm64'
    if (arch === 'ia32') return 'windows-x86'
    return 'windows-x64'
  }
  if (platform === 'darwin') return arch === 'arm64' ? 'mac-os-arm64' : 'mac-os'
  if (platform === 'linux') return arch === 'ia32' ? 'linux-i386' : 'linux'
  return null
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : null
}

function asString(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

/**
 * The manifest URL for one component on one platform.
 *
 * A component can have several builds, and the newest is the last one listed;
 * an entry Mojang has not finished publishing is skipped rather than used.
 */
export function findRuntimeManifest(
  payload: unknown,
  input: { os: string; component: string }
): JavaRuntimeVersionInfo | null {
  const index = asRecord(payload)
  const byOs = asRecord(index?.[input.os])
  const entries = byOs?.[input.component]
  if (!Array.isArray(entries)) return null

  const candidates: JavaRuntimeVersionInfo[] = []
  for (const raw of entries) {
    const entry = asRecord(raw)
    const manifest = asRecord(entry?.manifest)
    const url = asString(manifest?.url)
    if (!url) continue
    const version = asRecord(entry?.version)
    const availability = asRecord(entry?.availability)
    candidates.push({
      name: asString(version?.name) ?? input.component,
      released: asString(version?.released) ?? '',
      manifestUrl: url,
      manifestSha1: asString(manifest?.sha1),
      available: availability === null || availability.progress === undefined || availability.progress === 100
    })
  }
  if (candidates.length === 0) return null
  const usable = candidates.filter((candidate) => candidate.available)
  const pool = usable.length > 0 ? usable : candidates
  return pool.sort((left, right) => left.released.localeCompare(right.released))[pool.length - 1]
}

/** Components the index actually offers for this platform, newest first. */
export function listRuntimeComponents(payload: unknown, os: string): string[] {
  const byOs = asRecord(asRecord(payload)?.[os])
  return byOs ? Object.keys(byOs).sort() : []
}

export interface RuntimeFile {
  /** Path inside the runtime, e.g. `bin/java`. */
  path: string
  type: 'file' | 'directory' | 'link'
  executable: boolean
  /** Present for `file` entries; the raw, uncompressed copy. */
  url?: string
  sha1?: string
  size?: number
  /** For a `link`, what it points at. */
  target?: string
}

export interface RuntimeManifest {
  files: RuntimeFile[]
  /** Directory entries, which have to exist before the files land. */
  directories: string[]
  filesToDownload: RuntimeFile[]
  links: RuntimeFile[]
  /** Total bytes of the raw files, for progress and for telling the user. */
  bytes: number
}

/**
 * Reads a runtime manifest.
 *
 * A `file` entry with no raw download is dropped rather than guessed at: the
 * compressed copy cannot be used here, and a missing file in a runtime is a
 * runtime that will not start.
 */
export function parseRuntimeManifest(payload: unknown): RuntimeManifest {
  const record = asRecord(payload)
  const rawFiles = asRecord(record?.files)
  if (!rawFiles) throw new Error('The Java runtime manifest has no files list.')

  const files: RuntimeFile[] = []
  for (const [path, value] of Object.entries(rawFiles)) {
    const entry = asRecord(value)
    if (!entry) continue
    const type = entry.type === 'directory' ? 'directory' : entry.type === 'link' ? 'link' : 'file'
    const downloads = asRecord(entry.downloads) ?? {}
    const raw = asRecord(downloads.raw)
    files.push({
      path,
      type,
      executable: entry.executable === true,
      url: asString(raw?.url) ?? undefined,
      sha1: asString(raw?.sha1) ?? undefined,
      size: typeof raw?.size === 'number' ? raw.size : undefined,
      target: asString(entry.target) ?? undefined
    })
  }

  const filesToDownload = files.filter((file) => file.type === 'file' && file.url)
  return {
    files,
    directories: files.filter((file) => file.type === 'directory').map((file) => file.path),
    filesToDownload,
    links: files.filter((file) => file.type === 'link'),
    bytes: filesToDownload.reduce((sum, file) => sum + (file.size ?? 0), 0)
  }
}

/** Where a component's runtime lives inside a game directory. */
export function runtimeDirFor(input: {
  instanceDir: string
  component: string
  platformDir: string
}): string {
  return `${input.instanceDir.replace(/[\\/]+$/, '')}/runtime/${input.component}/${input.platformDir}/${input.component}`
}

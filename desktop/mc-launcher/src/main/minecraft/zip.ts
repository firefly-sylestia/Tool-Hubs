import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, normalize, sep } from 'node:path'
import { inflateRawSync } from 'node:zlib'

/**
 * A small zip reader.
 *
 * Native libraries ship as jars, and the game will not start until they have
 * been unpacked into a natives directory, so the installer has to read zips. A
 * dependency would be one more thing to rebuild for three platforms; the format
 * we need — central directory plus stored or deflated entries — is small enough
 * to read directly.
 *
 * Repacking is deliberately not supported: nothing here writes a zip.
 */

export interface ZipEntry {
  name: string
  /** 0 = stored, 8 = deflated. */
  method: number
  compressedSize: number
  uncompressedSize: number
  localHeaderOffset: number
}

const EOCD_SIGNATURE = 0x06054b50
const CENTRAL_SIGNATURE = 0x02014b50
const LOCAL_SIGNATURE = 0x04034b50

/** The trailing comment is at most 64 KiB, so the record is in the last 64 KiB + 22 bytes. */
function findEndOfCentralDirectory(buffer: Buffer): number {
  const earliest = Math.max(0, buffer.length - (0xffff + 22))
  for (let offset = buffer.length - 22; offset >= earliest; offset -= 1) {
    if (buffer.readUInt32LE(offset) === EOCD_SIGNATURE) return offset
  }
  throw new Error(
    'Not a complete zip file: no end-of-central-directory record found. ' +
      'A partially downloaded archive looks exactly like this.'
  )
}

export function listZipEntries(buffer: Buffer): ZipEntry[] {
  const eocd = findEndOfCentralDirectory(buffer)
  const count = buffer.readUInt16LE(eocd + 10)
  let offset = buffer.readUInt32LE(eocd + 16)

  const entries: ZipEntry[] = []
  for (let index = 0; index < count; index += 1) {
    if (offset + 46 > buffer.length) {
      throw new Error(`Zip central directory is truncated at entry ${index}`)
    }
    if (buffer.readUInt32LE(offset) !== CENTRAL_SIGNATURE) {
      throw new Error(`Zip central directory is corrupt at entry ${index}`)
    }
    const method = buffer.readUInt16LE(offset + 10)
    const compressedSize = buffer.readUInt32LE(offset + 20)
    const uncompressedSize = buffer.readUInt32LE(offset + 24)
    const nameLength = buffer.readUInt16LE(offset + 28)
    const extraLength = buffer.readUInt16LE(offset + 30)
    const commentLength = buffer.readUInt16LE(offset + 32)
    const localHeaderOffset = buffer.readUInt32LE(offset + 42)
    const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLength)
    entries.push({ name, method, compressedSize, uncompressedSize, localHeaderOffset })
    offset += 46 + nameLength + extraLength + commentLength
  }
  return entries
}

/**
 * Reads one entry's bytes.
 *
 * The data offset comes from the *local* header rather than the central
 * directory, because the two are allowed to carry different extra fields —
 * reading through the central directory's length is a classic way to get
 * garbage for the first few bytes of a file.
 */
export function readZipEntry(buffer: Buffer, entry: ZipEntry): Buffer {
  const start = entry.localHeaderOffset
  if (buffer.readUInt32LE(start) !== LOCAL_SIGNATURE) {
    throw new Error(`Zip entry ${entry.name} has no local header`)
  }
  const nameLength = buffer.readUInt16LE(start + 26)
  const extraLength = buffer.readUInt16LE(start + 28)
  const dataStart = start + 30 + nameLength + extraLength
  const data = buffer.subarray(dataStart, dataStart + entry.compressedSize)

  if (entry.method === 0) return Buffer.from(data)
  if (entry.method === 8) {
    const inflated = inflateRawSync(data)
    if (inflated.length !== entry.uncompressedSize) {
      throw new Error(
        `Zip entry ${entry.name} inflated to ${inflated.length} bytes, expected ${entry.uncompressedSize}`
      )
    }
    return inflated
  }
  throw new Error(`Zip entry ${entry.name} uses unsupported compression method ${entry.method}`)
}

export interface ExtractOptions {
  /** Entry name prefixes to skip, the way `extract.exclude` in a version json spells them. */
  exclude?: string[]
  /** Only entries this accepts are written; everything else is ignored. */
  filter?: (name: string) => boolean
  /** Called for each file actually written, with its path relative to `destDir`. */
  onEntry?: (name: string, size: number) => void
}

/**
 * Writes every file entry under `destDir`, skipping directory entries and
 * anything under an excluded prefix.
 *
 * Entry names are resolved and then checked, so a crafted archive cannot escape
 * the destination with `..` or an absolute path.
 */
export function extractZip(buffer: Buffer, destDir: string, options: ExtractOptions = {}): number {
  const exclude = options.exclude ?? []
  let written = 0

  for (const entry of listZipEntries(buffer)) {
    if (entry.name.endsWith('/')) continue
    if (exclude.some((prefix) => entry.name.startsWith(prefix))) continue
    if (options.filter && !options.filter(entry.name)) continue

    const target = normalize(join(destDir, entry.name))
    const root = normalize(destDir)
    if (target !== root && !target.startsWith(root + sep)) {
      throw new Error(`Zip entry ${entry.name} would be written outside ${destDir}`)
    }

    const contents = readZipEntry(buffer, entry)
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(target, contents)
    written += 1
    options.onEntry?.(entry.name, contents.length)
  }

  return written
}

/**
 * True when the entry name looks like a native library for this platform.
 *
 * Native jars carry the same shared objects for every platform, so unpacking
 * all of them would litter the natives directory with libraries the JVM cannot
 * load.
 */
export function isNativeLibraryEntry(name: string, os: 'windows' | 'linux' | 'osx'): boolean {
  const lower = name.toLowerCase()
  if (lower.endsWith('/')) return false
  if (os === 'windows') return lower.endsWith('.dll')
  if (os === 'osx') return lower.endsWith('.dylib')
  return lower.endsWith('.so')
}

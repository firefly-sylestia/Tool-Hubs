import { createHash } from 'node:crypto'
import { createReadStream, createWriteStream, statSync } from 'node:fs'
import { crc32, deflateRawSync } from 'node:zlib'

/**
 * A small zip writer.
 *
 * The reader next door was written for native jars; this one exists because two
 * things have to be *produced*: a world, or a whole `saves/` folder, exported to
 * a file someone can send to a friend, and an instance exported as a `.mrpack`,
 * which is a zip with an index in it. The format is the same handful of records
 * the reader walks — local header, data, central directory — and a dependency
 * would be one more thing to rebuild for three platforms.
 *
 * Two decisions come from what is being zipped. A file that is handed over as a
 * path is streamed straight into the archive rather than read into memory, so
 * backing up a two-gigabyte world does not need two gigabytes of RAM. And a
 * streamed entry is *stored* rather than deflated: region files are compressed
 * already, so deflating them would cost minutes and save nothing, while the
 * small things that do compress — an index json — are passed in as bytes and
 * deflated. Sizes and checksums are measured here, from the data itself, so an
 * entry can never claim something about its contents that is not true.
 */

export interface ZipEntrySource {
  /** The path inside the archive. Forward slashes, never a leading one. */
  name: string
  /** Bytes that are already in hand. Deflated, because it is worth it. */
  contents?: Buffer
  /** A file to stream in. Stored, because it is not. */
  path?: string
}

export interface ZipWriteResult {
  bytes: number
  files: number
}

const LOCAL_SIGNATURE = 0x04034b50
const CENTRAL_SIGNATURE = 0x02014b50
const EOCD_SIGNATURE = 0x06054b50

/** Zip's own epoch: MS-DOS timestamps cannot go before 1980. */
const DOS_EPOCH = Date.UTC(1980, 0, 1)

function dosDateTime(date: Date): { time: number; date: number } {
  const at = date.getTime() < DOS_EPOCH ? new Date(DOS_EPOCH) : date
  const time = (at.getHours() << 11) | (at.getMinutes() << 5) | Math.floor(at.getSeconds() / 2)
  const day = ((at.getFullYear() - 1980) << 9) | ((at.getMonth() + 1) << 5) | at.getDate()
  return { time, date: day }
}

/** Leading slashes and backslashes would let a write escape on read; neither is kept. */
function normalizeEntryName(name: string): string {
  return name.replace(/\\/g, '/').replace(/^\/+/, '')
}

interface Written {
  name: Buffer
  method: number
  checksum: number
  compressed: number
  uncompressed: number
  offset: number
}

/**
 * Writes the entries to a zip file, in the order given.
 *
 * Order is preserved rather than sorted: a `.mrpack` is easier to trust when
 * `modrinth.index.json` sits where every other writer puts it, and a world
 * export reads better with the world's own folder at the top.
 */
export async function writeZip(
  dest: string,
  entries: ZipEntrySource[],
  options: { at?: Date } = {}
): Promise<ZipWriteResult> {
  const at = options.at ?? new Date()
  const { time, date } = dosDateTime(at)
  const stream = createWriteStream(dest)
  const written: Written[] = []
  let offset = 0

  const write = async (chunk: Buffer): Promise<void> => {
    if (!stream.write(chunk)) {
      await new Promise<void>((resolve) => stream.once('drain', resolve))
    }
  }

  const finish = async (): Promise<void> => {
    await new Promise<void>((resolve, reject) => {
      stream.end((error?: Error | null) => (error ? reject(error) : resolve()))
    })
  }

  try {
    for (const entry of entries) {
      const name = Buffer.from(normalizeEntryName(entry.name), 'utf8')
      const chunks: Buffer[] = []
      let checksum = 0
      let uncompressed = 0
      let method = 0

      let body: Buffer | null = null
      if (entry.contents) {
        uncompressed = entry.contents.length
        checksum = crc32(entry.contents)
        const deflated = deflateRawSync(entry.contents)
        if (deflated.length < entry.contents.length) {
          method = 8
          body = deflated
        } else {
          body = entry.contents
        }
      } else if (entry.path) {
        uncompressed = statSync(entry.path).size
        const reader = createReadStream(entry.path)
        for await (const chunk of reader) {
          const bytes = chunk as Buffer
          checksum = crc32(bytes, checksum)
          chunks.push(bytes)
        }
      } else {
        throw new Error(`Zip entry ${entry.name} has neither contents nor a path`)
      }

      const compressed = body ? body.length : uncompressed
      const local = Buffer.alloc(30)
      local.writeUInt32LE(LOCAL_SIGNATURE, 0)
      local.writeUInt16LE(20, 4) // version needed
      local.writeUInt16LE(0, 6) // flags
      local.writeUInt16LE(method, 8)
      local.writeUInt16LE(time, 10)
      local.writeUInt16LE(date, 12)
      local.writeUInt32LE(checksum, 14)
      local.writeUInt32LE(compressed, 18)
      local.writeUInt32LE(uncompressed, 22)
      local.writeUInt16LE(name.length, 26)
      local.writeUInt16LE(0, 28) // no extra field

      written.push({
        name,
        method,
        checksum,
        compressed,
        uncompressed,
        offset
      })

      await write(local)
      await write(name)
      if (body) {
        await write(body)
      } else {
        // Already-verified chunks, written as they were read.
        for (const chunk of chunks) await write(chunk)
      }
      offset += local.length + name.length + compressed
    }

    const central: Buffer[] = []
    let centralBytes = 0
    for (const entry of written) {
      const directory = Buffer.alloc(46)
      directory.writeUInt32LE(CENTRAL_SIGNATURE, 0)
      directory.writeUInt16LE(20, 4) // version made by
      directory.writeUInt16LE(20, 6) // version needed
      directory.writeUInt16LE(0, 8)
      directory.writeUInt16LE(entry.method, 10)
      directory.writeUInt16LE(time, 12)
      directory.writeUInt16LE(date, 14)
      directory.writeUInt32LE(entry.checksum, 16)
      directory.writeUInt32LE(entry.compressed, 20)
      directory.writeUInt32LE(entry.uncompressed, 24)
      directory.writeUInt16LE(entry.name.length, 28)
      directory.writeUInt16LE(0, 30) // extra
      directory.writeUInt16LE(0, 32) // comment
      directory.writeUInt16LE(0, 34) // disk
      directory.writeUInt16LE(0, 36) // internal attributes
      directory.writeUInt32LE(0, 38) // external attributes
      directory.writeUInt32LE(entry.offset, 42)
      central.push(directory, entry.name)
      centralBytes += directory.length + entry.name.length
    }

    const eocd = Buffer.alloc(22)
    eocd.writeUInt32LE(EOCD_SIGNATURE, 0)
    eocd.writeUInt16LE(0, 4)
    eocd.writeUInt16LE(0, 6)
    eocd.writeUInt16LE(written.length, 8)
    eocd.writeUInt16LE(written.length, 10)
    eocd.writeUInt32LE(centralBytes, 12)
    eocd.writeUInt32LE(offset, 16)
    eocd.writeUInt16LE(0, 20)

    for (const chunk of central) await write(chunk)
    await write(eocd)
    await finish()
  } catch (error) {
    stream.destroy()
    throw error
  }

  return { bytes: offset + entries.length * 46, files: entries.length }
}

/**
 * The sha1 and sha512 of a file's bytes, which is what a modpack index records
 * and what every other launcher checks an export against.
 */
export function hashesOf(contents: Buffer): { sha1: string; sha512: string } {
  return {
    sha1: createHash('sha1').update(contents).digest('hex'),
    sha512: createHash('sha512').update(contents).digest('hex')
  }
}

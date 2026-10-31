import { gunzipSync, gzipSync } from 'node:zlib'

/**
 * The little of Minecraft's NBT format that the launcher has to read and write.
 *
 * Two files need it. A world's `level.dat` says what the world is called, when
 * it was last played and which version wrote it, and it is gzipped NBT; the
 * game's own `servers.dat` is a list of servers in the same format, and editing
 * the list the game itself reads is the difference between a launcher that
 * manages servers and one that keeps a private list beside them.
 *
 * The whole format is here rather than the four tag types in use, because
 * parsing has to walk the tags it does not care about to reach the ones it
 * wants: a `level.dat` is a compound holding an int and a compound holding a
 * long, and a reader that guesses the layout reports the wrong world name. A
 * dependency would be the other option; this is about a screen of code and can
 * be checked without a game.
 */

/** A named tag value. `end` only ever appears as the element type of an empty list. */
export type NbtValue =
  | { tag: 'byte'; value: number }
  | { tag: 'short'; value: number }
  | { tag: 'int'; value: number }
  | { tag: 'long'; value: number }
  | { tag: 'float'; value: number }
  | { tag: 'double'; value: number }
  | { tag: 'byteArray'; value: number[] }
  | { tag: 'string'; value: string }
  | { tag: 'list'; element: string; items: NbtValue[] }
  | { tag: 'compound'; value: NbtCompound }
  | { tag: 'intArray'; value: number[] }
  | { tag: 'longArray'; value: number[] }

export type NbtCompound = Record<string, NbtValue>

export interface NbtDocument {
  /** The root tag's name; `level.dat` and `servers.dat` both use an empty one. */
  name: string
  root: NbtCompound
  /** True when the file was gzipped, so writing it back can keep that. */
  gzipped: boolean
}

const TAG_NAMES: Record<number, NbtValue['tag']> = {
  1: 'byte',
  2: 'short',
  3: 'int',
  4: 'long',
  5: 'float',
  6: 'double',
  7: 'byteArray',
  8: 'string',
  9: 'list',
  10: 'compound',
  11: 'intArray',
  12: 'longArray'
}

const TAG_IDS: Record<string, number> = {
  byte: 1,
  short: 2,
  int: 3,
  long: 4,
  float: 5,
  double: 6,
  byteArray: 7,
  string: 8,
  list: 9,
  compound: 10,
  intArray: 11,
  longArray: 12
}

class NbtReader {
  private offset = 0

  constructor(private readonly buffer: Buffer) {}

  private need(bytes: number): void {
    if (this.offset + bytes > this.buffer.length) {
      throw new Error(
        `NBT data ends at byte ${this.buffer.length} part-way through a value. A truncated or ` +
          `corrupt file looks exactly like this.`
      )
    }
  }

  /** The next tag's type id. */
  tagId(): number {
    this.need(1)
    return this.buffer.readUInt8(this.offset++)
  }

  /** A length-prefixed UTF-8 string. */
  name(): string {
    this.need(2)
    const length = this.buffer.readUInt16BE(this.offset)
    this.offset += 2
    this.need(length)
    const value = this.buffer.toString('utf8', this.offset, this.offset + length)
    this.offset += length
    return value
  }

  private i8(): number {
    this.need(1)
    return this.buffer.readInt8(this.offset++)
  }

  private payload(tagId: number): NbtValue {
    switch (tagId) {
      case 1:
        return { tag: 'byte', value: this.i8() }
      case 2:
        return { tag: 'short', value: this.readNumber(2, 'readInt16LE') }
      case 3:
        return { tag: 'int', value: this.readNumber(4, 'readInt32LE') }
      case 4:
        return { tag: 'long', value: this.readNumber(8, 'readBigInt64LE') }
      case 5:
        return { tag: 'float', value: this.readNumber(4, 'readFloatLE') }
      case 6:
        return { tag: 'double', value: this.readNumber(8, 'readDoubleLE') }
      case 7: {
        const length = this.length()
        return { tag: 'byteArray', value: [...this.bytes(length)] }
      }
      case 8:
        return { tag: 'string', value: this.name() }
      case 9: {
        const elementId = this.tagId()
        const length = this.length()
        const items: NbtValue[] = []
        for (let index = 0; index < length; index += 1) items.push(this.payload(elementId))
        return { tag: 'list', element: elementId === 0 ? 'end' : TAG_NAMES[elementId], items }
      }
      case 10:
        return { tag: 'compound', value: this.compound() }
      case 11: {
        const length = this.length()
        const values: number[] = []
        for (let index = 0; index < length; index += 1) values.push(this.readNumber(4, 'readInt32LE'))
        return { tag: 'intArray', value: values }
      }
      case 12: {
        const length = this.length()
        const values: number[] = []
        for (let index = 0; index < length; index += 1) values.push(this.readNumber(8, 'readBigInt64LE'))
        return { tag: 'longArray', value: values }
      }
      default:
        throw new Error(`Unknown NBT tag type ${tagId} at byte ${this.offset}`)
    }
  }

  private readNumber(
    bytes: number,
    reader: 'readInt16LE' | 'readInt32LE' | 'readFloatLE' | 'readDoubleLE' | 'readBigInt64LE'
  ): number {
    this.need(bytes)
    const value = this.buffer[reader](this.offset)
    this.offset += bytes
    // A long is a millisecond timestamp or a seed in every field the launcher
    // reads, and those are well inside what a double holds exactly.
    return typeof value === 'bigint' ? Number(value) : value
  }

  /** A signed 32-bit element count, which has to be sane before it is trusted. */
  private length(): number {
    const length = this.readNumber(4, 'readInt32LE')
    if (length < 0) throw new Error(`NBT declares a negative length (${length})`)
    return length
  }

  private bytes(length: number): Buffer {
    this.need(length)
    const value = this.buffer.subarray(this.offset, this.offset + length)
    this.offset += length
    return value
  }

  /** Reads a compound's entries, stopping at the end tag. */
  compound(): NbtCompound {
    const value: NbtCompound = {}
    for (;;) {
      const tagId = this.tagId()
      if (tagId === 0) return value
      const name = this.name()
      value[name] = this.payload(tagId)
    }
  }
}

export function isGzipped(buffer: Buffer): boolean {
  return buffer.length > 2 && buffer[0] === 0x1f && buffer[1] === 0x8b
}

/** Reads an NBT document, gzipped or not. */
export function readNbt(buffer: Buffer): NbtDocument {
  if (buffer.length < 3) throw new Error('NBT data is too short to hold a document')
  const gzipped = isGzipped(buffer)
  let payload = buffer
  if (gzipped) {
    try {
      payload = gunzipSync(buffer)
    } catch (error) {
      throw new Error(`NBT data is gzipped but could not be decompressed: ${(error as Error).message}`)
    }
  }

  const reader = new NbtReader(payload)
  const rootId = reader.tagId()
  if (rootId !== 10) {
    throw new Error(`NBT root tag is type ${rootId}, expected a compound (10)`)
  }
  return { name: reader.name(), root: reader.compound(), gzipped }
}

function toTagId(tag: NbtValue['tag'] | 'end'): number {
  if (tag === 'end') return 0
  return TAG_IDS[tag] ?? 1
}

function stringBytes(value: string): Buffer {
  const bytes = Buffer.from(value, 'utf8')
  const header = Buffer.alloc(2)
  header.writeUInt16BE(bytes.length, 0)
  return Buffer.concat([header, bytes])
}

/** One value's payload bytes, without the tag id that precedes it. */
function payloadBytes(value: NbtValue): Buffer {
  switch (value.tag) {
    case 'byte': {
      const buffer = Buffer.alloc(1)
      buffer.writeInt8(value.value, 0)
      return buffer
    }
    case 'short': {
      const buffer = Buffer.alloc(2)
      buffer.writeInt16LE(value.value, 0)
      return buffer
    }
    case 'int': {
      const buffer = Buffer.alloc(4)
      buffer.writeInt32LE(value.value, 0)
      return buffer
    }
    case 'long': {
      const buffer = Buffer.alloc(8)
      buffer.writeBigInt64LE(BigInt(Math.trunc(value.value)), 0)
      return buffer
    }
    case 'float': {
      const buffer = Buffer.alloc(4)
      buffer.writeFloatLE(value.value, 0)
      return buffer
    }
    case 'double': {
      const buffer = Buffer.alloc(8)
      buffer.writeDoubleLE(value.value, 0)
      return buffer
    }
    case 'byteArray':
      return Buffer.concat([
        int32Bytes(value.value.length),
        Buffer.from(value.value.map((byte) => byte & 0xff))
      ])
    case 'string':
      return stringBytes(value.value)
    case 'list': {
      const header = Buffer.alloc(5)
      header.writeUInt8(toTagId(value.element as NbtValue['tag'] | 'end'), 0)
      header.writeInt32LE(value.items.length, 1)
      return Buffer.concat([header, ...value.items.map((item) => payloadBytes(item))])
    }
    case 'compound':
      return compoundBytes(value.value)
    case 'intArray':
      return Buffer.concat([
        int32Bytes(value.value.length),
        ...value.value.map((item) => payloadBytes({ tag: 'int', value: item }))
      ])
    case 'longArray':
      return Buffer.concat([
        int32Bytes(value.value.length),
        ...value.value.map((item) => payloadBytes({ tag: 'long', value: item }))
      ])
    default:
      return Buffer.alloc(0)
  }
}

function int32Bytes(value: number): Buffer {
  const buffer = Buffer.alloc(4)
  buffer.writeInt32LE(value, 0)
  return buffer
}

function compoundBytes(value: NbtCompound): Buffer {
  const parts: Buffer[] = []
  for (const [name, entry] of Object.entries(value)) {
    const tag = Buffer.alloc(1)
    tag.writeUInt8(toTagId(entry.tag), 0)
    parts.push(tag, stringBytes(name), payloadBytes(entry))
  }
  parts.push(Buffer.from([0]))
  return Buffer.concat(parts)
}

/**
 * Writes an NBT document.
 *
 * Gzipped by default, which is what both files that use this are, and what the
 * game overwrites them with; `gzipped: false` exists for a reader to be checked
 * against bytes it can look at.
 */
export function writeNbt(document: { name: string; root: NbtCompound; gzipped?: boolean }): Buffer {
  const body = Buffer.concat([Buffer.from([10]), stringBytes(document.name), compoundBytes(document.root)])
  return document.gzipped === false ? body : gzipSync(body)
}

/** The value at a dotted path, or null when a step is missing or the wrong shape. */
export function nbtAt(root: NbtCompound, path: string): NbtValue | null {
  let current: NbtValue = { tag: 'compound', value: root }
  for (const step of path.split('.')) {
    if (current.tag !== 'compound') return null
    const next: NbtValue | undefined = current.value[step]
    if (!next) return null
    current = next
  }
  return current
}

/** A string or a number from a dotted path, or null when it is neither. */
export function nbtScalar(root: NbtCompound, path: string): string | number | null {
  const value = nbtAt(root, path)
  if (!value) return null
  switch (value.tag) {
    case 'string':
      return value.value
    case 'byte':
    case 'short':
    case 'int':
    case 'long':
    case 'float':
    case 'double':
      return value.value
    default:
      return null
  }
}

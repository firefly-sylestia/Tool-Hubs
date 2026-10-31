/**
 * The server list ping, as packets rather than as a socket.
 *
 * Every server list in every launcher speaks the same two packets: a handshake
 * that says this is a status request, then an empty request, answered with one
 * JSON string. Building and reading those bytes is pure, so it can be checked
 * without a server; the socket that carries them lives in the service.
 *
 * The protocol version sent is 47, which is what the servers themselves expect
 * from a status ping: it is old enough that every version since (and the proxies
 * in front of them) answers, and a status response does not depend on matching
 * the client's version.
 */

import type { ServerStatus } from '@shared/types'

/** The protocol version a status ping claims; the reply does not depend on it. */
export const STATUS_PROTOCOL_VERSION = 47

export function writeVarInt(value: number): Buffer {
  const bytes: number[] = []
  // A negative int is written as its 32-bit unsigned self, which is why the
  // shift is `>>>` and not `>>`.
  let remaining = value >>> 0
  for (;;) {
    if ((remaining & 0xffffff80) === 0) {
      bytes.push(remaining)
      break
    }
    bytes.push((remaining & 0x7f) | 0x80)
    remaining >>>= 7
  }
  return Buffer.from(bytes)
}

export interface VarIntRead {
  value: number
  bytes: number
}

export function readVarInt(buffer: Buffer, offset = 0): VarIntRead {
  let value = 0
  let bytes = 0
  for (;;) {
    if (offset + bytes >= buffer.length) {
      throw new Error('A varint is cut off: the packet is incomplete.')
    }
    const byte = buffer[offset + bytes]
    value |= (byte & 0x7f) << (7 * bytes)
    bytes += 1
    // Five bytes is the most a 32-bit varint can need; a sixth means this is not
    // one and the rest of the buffer would be read as garbage.
    if (bytes > 5) throw new Error('A varint is longer than five bytes, so the packet is not one.')
    if ((byte & 0x80) === 0) break
  }
  return { value: value >>> 0, bytes }
}

function stringBytes(value: string): Buffer {
  const bytes = Buffer.from(value, 'utf8')
  return Buffer.concat([writeVarInt(bytes.length), bytes])
}

/** A packet: its length, then its id, then its body. */
export function frame(packetId: number, body: Buffer): Buffer {
  const payload = Buffer.concat([writeVarInt(packetId), body])
  return Buffer.concat([writeVarInt(payload.length), payload])
}

/** The handshake, with `nextState` 1 for a status request. */
export function handshakePacket(host: string, port: number, protocol = STATUS_PROTOCOL_VERSION): Buffer {
  const portBytes = Buffer.alloc(2)
  portBytes.writeUInt16BE(port, 0)
  const body = Buffer.concat([writeVarInt(protocol), stringBytes(host), portBytes, writeVarInt(1)])
  return frame(0x00, body)
}

/** The empty status request that follows it. */
export function statusRequestPacket(): Buffer {
  return frame(0x00, Buffer.alloc(0))
}

/**
 * How many bytes the response still needs, or null when the length is not in yet.
 *
 * A response arrives in as many chunks as the network feels like sending, so the
 * socket loop asks this after every chunk instead of guessing.
 */
export function expectedFrameLength(buffer: Buffer): number | null {
  try {
    const { value, bytes } = readVarInt(buffer, 0)
    return value + bytes
  } catch {
    return null
  }
}

/** The JSON string out of a status response frame, or null when it is not one. */
export function statusJsonFromFrame(buffer: Buffer): string | null {
  let offset = 0
  const length = readVarInt(buffer, offset)
  offset += length.bytes
  const packetId = readVarInt(buffer, offset)
  offset += packetId.bytes
  if (packetId.value !== 0) return null
  const stringLength = readVarInt(buffer, offset)
  offset += stringLength.bytes
  return buffer.toString('utf8', offset, offset + stringLength.value)
}

/**
 * Minecraft's chat components, flattened.
 *
 * A description is either a plain string or an object with `text` and `extra`,
 * and `extra` can nest. Formatting codes (`§a`) are stripped: the game renders
 * them, and a launcher list that showed them raw would be showing the user
 * markup.
 */
export function flattenChat(description: unknown): string {
  const parts: string[] = []
  const walk = (node: unknown): void => {
    if (typeof node === 'string') {
      parts.push(node)
      return
    }
    if (Array.isArray(node)) {
      for (const item of node) walk(item)
      return
    }
    if (node && typeof node === 'object') {
      const record = node as Record<string, unknown>
      if (typeof record.text === 'string') parts.push(record.text)
      if (record.extra !== undefined) walk(record.extra)
      if (record.translate !== undefined && typeof record.text !== 'string') {
        // A translatable component has no text until a client renders it; the
        // key is the most honest thing to show.
        parts.push(String(record.translate))
      }
    }
  }
  walk(description)
  return parts
    .join('')
    .replace(/§./g, '')
    .replace(/\s*\n\s*/g, ' ')
    .trim()
}

/** Reads a status response's JSON into what a server card shows. */
export function parseServerStatus(
  payload: unknown,
  input: { host: string; port: number; latencyMs: number | null }
): ServerStatus {
  const record = (payload ?? {}) as Record<string, unknown>
  const version = (record.version ?? {}) as Record<string, unknown>
  const players = (record.players ?? {}) as Record<string, unknown>
  const sample = Array.isArray(players.sample) ? players.sample : []

  return {
    online: true,
    host: input.host,
    port: input.port,
    latencyMs: input.latencyMs,
    version: typeof version.name === 'string' ? version.name : null,
    protocol: typeof version.protocol === 'number' ? version.protocol : null,
    players:
      typeof players.online === 'number' || typeof players.max === 'number'
        ? {
            online: typeof players.online === 'number' ? players.online : 0,
            max: typeof players.max === 'number' ? players.max : 0,
            sample: sample
              .filter((entry): entry is Record<string, unknown> => typeof entry === 'object' && entry !== null)
              .map((entry) => ({
                name: typeof entry.name === 'string' ? entry.name : '',
                id: typeof entry.id === 'string' ? entry.id : null
              }))
              .filter((entry) => entry.name !== '')
          }
        : null,
    motd: flattenChat(record.description) || null,
    icon: typeof record.favicon === 'string' ? record.favicon : null,
    error: null
  }
}

/** A failed ping, in the same shape as a successful one. */
export function offlineStatus(input: {
  host: string
  port: number
  error: string
  latencyMs?: number | null
}): ServerStatus {
  return {
    online: false,
    host: input.host,
    port: input.port,
    latencyMs: input.latencyMs ?? null,
    version: null,
    protocol: null,
    players: null,
    motd: null,
    icon: null,
    error: input.error
  }
}

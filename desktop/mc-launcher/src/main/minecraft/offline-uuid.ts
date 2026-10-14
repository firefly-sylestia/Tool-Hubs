import { createHash } from 'node:crypto'

/**
 * Offline UUID for a local profile.
 *
 * This is the value the game itself expects for a profile with no session:
 * `UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))`, an
 * RFC 4122 version 3 (MD5) UUID. Nothing here authenticates anything; it only
 * gives the client a stable, name-derived identity.
 */
export function offlineUuid(name: string): string {
  const digest = Uint8Array.from(createHash('md5').update(`OfflinePlayer:${name}`, 'utf8').digest())
  digest[6] = (digest[6] & 0x0f) | 0x30 // version 3
  digest[8] = (digest[8] & 0x3f) | 0x80 // RFC 4122 variant
  const hex = Buffer.from(digest).toString('hex')
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20)
  ].join('-')
}

/** Same value, without dashes, which is what the game's `--uuid` expects. */
export function offlineUuidDashless(name: string): string {
  return offlineUuid(name).replace(/-/g, '')
}

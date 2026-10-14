import type { WorldInfo } from '@shared/types'
import type { NbtCompound } from './nbt'
import { nbtScalar } from './nbt'

/**
 * Worlds: reading what a `saves/` folder holds, and deciding how one leaves or
 * arrives.
 *
 * Everything here is a decision rather than a file operation, so what the
 * launcher says a world is can be checked against a `level.dat` written by hand.
 * The folder listing and the zipping live in the service.
 */

const GAME_MODES: Record<number, string> = {
  0: 'survival',
  1: 'creative',
  2: 'adventure',
  3: 'spectator'
}

/** A world as described by its own `level.dat`, or by its folder when that fails. */
export function describeWorld(input: {
  folder: string
  bytes: number
  level: NbtCompound | null
  problem?: string | null
}): WorldInfo {
  const level = input.level
  // `Data` is the compound every modern world keeps its metadata in; older ones
  // are read anyway because a name is still a name at the root.
  const data: NbtCompound | null =
    level && isCompound(level.Data) ? (level.Data.value as NbtCompound) : level

  const name = data ? (nbtScalar(data, 'LevelName') as string | null) : null
  const version = data ? (nbtScalar(data, 'Version.Name') as string | null) : null
  const played = data ? (nbtScalar(data, 'LastPlayed') as number | null) : null
  const gameType = data ? (nbtScalar(data, 'GameType') as number | null) : null
  const hardcore = data ? nbtScalar(data, 'hardcore') === 1 : false

  return {
    folder: input.folder,
    name: typeof name === 'string' && name.trim() !== '' ? name : input.folder,
    version: typeof version === 'string' && version !== '' ? version : null,
    lastPlayed: typeof played === 'number' && played > 0 ? new Date(played).toISOString() : null,
    gameMode: typeof gameType === 'number' ? (GAME_MODES[gameType] ?? null) : null,
    hardcore,
    bytes: input.bytes,
    problem: input.problem ?? null
  }
}

function isCompound(value: unknown): value is { tag: 'compound'; value: NbtCompound } {
  return (
    typeof value === 'object' &&
    value !== null &&
    (value as { tag?: string }).tag === 'compound' &&
    typeof (value as { value?: unknown }).value === 'object'
  )
}

/**
 * The name for a `saves/` folder that is not taken yet.
 *
 * An imported world arrives with a folder name that may already exist — the same
 * world from two machines, or two downloads of it — and the game shows folders,
 * not ids, so a duplicate has to become a different folder rather than
 * overwrite what is there. `New World` becomes `New World 2`, then `3`.
 */
export function uniqueWorldFolder(name: string, existing: string[]): string {
  const base = sanitizeWorldFolder(name)
  if (!existing.includes(base)) return base
  for (let index = 2; index < 1000; index += 1) {
    const candidate = `${base} ${index}`
    if (!existing.includes(candidate)) return candidate
  }
  return `${base} ${Date.now()}`
}

/** Strips what a folder name cannot hold, and never returns an empty one. */
export function sanitizeWorldFolder(name: string): string {
  const cleaned = name
    .replace(/[\\/:*?"<>|]/g, '-')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^\.+/, '')
  return cleaned === '' ? 'world' : cleaned.slice(0, 64)
}

/**
 * `saves-20261014-153012.zip`, for a backup that sorts by when it was taken.
 *
 * The automatic ones are prefixed `before-`, so a folder of backups reads as
 * what it is long after anyone remembers: this one was taken because the game
 * underneath the worlds was about to change.
 */
export function backupFileName(
  at: Date = new Date(),
  kind: 'manual' | 'before-change' = 'manual'
): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  const stamp =
    `${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-` +
    `${pad(at.getHours())}${pad(at.getMinutes())}${pad(at.getSeconds())}`
  return `${kind === 'before-change' ? 'before' : 'saves'}-${stamp}.zip`
}

/**
 * The one folder a zipped world's entries live under, or null when they are not
 * all in one place.
 *
 * An export made here puts `world/level.dat` at the top; some archives strip
 * that folder and start at `level.dat`. Both are readable, and an archive with
 * two worlds in it is refused rather than half-imported.
 */
export function detectWorldRoot(entryNames: string[]): string | null {
  const files = entryNames.map((name) => name.replace(/\\/g, '/')).filter((name) => !name.endsWith('/'))
  if (files.length === 0) return null
  if (files.includes('level.dat')) return ''
  const roots = new Set(files.map((name) => name.split('/')[0]).filter((part) => part !== ''))
  if (roots.size !== 1) return null
  const root = [...roots][0]
  // The folder has to hold the level file itself, or the single root is just a
  // directory inside something else.
  if (!files.some((name) => name.startsWith(`${root}/level.dat`))) return null
  return root
}

/** True when a file name is one the game would load as a world archive. */
export function looksLikeWorldZip(name: string): boolean {
  return /\.(zip|mcworld)$/i.test(name)
}

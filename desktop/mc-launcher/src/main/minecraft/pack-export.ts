import type { InstallLoader } from '@shared/types'
import { MODRINTH_INDEX_FILE } from './modpack'

/**
 * Writing an instance as a `.mrpack`.
 *
 * The format is a zip with `modrinth.index.json` in it and the pack's own files
 * under `overrides/`. The index can also list files by download URL, which is
 * what makes an import fast, and that is the one thing this cannot do: the
 * launcher does not record where a file came from, so pretending a URL would be
 * inventing one. Everything the instance holds is therefore carried in the
 * archive itself, which every launcher that reads the format will install — the
 * pack is bigger, and it is honest and self-contained.
 *
 * What goes in is what makes an instance an instance: the files that change how
 * the game behaves and what it looks like, plus its options. What stays behind
 * is the game itself, which a pack is supposed to install rather than carry, and
 * this instance's own history — its worlds, its backups, its logs.
 */

/** Folders and files a pack carries, as instance-relative paths. */
export const PACK_CONTENTS = [
  'mods',
  'resourcepacks',
  'shaderpacks',
  'config',
  'defaultconfigs',
  'scripts',
  'options.txt'
]

/**
 * True when an instance-relative path belongs in an exported pack.
 *
 * This is a list of what goes in rather than of what stays out, because the
 * things that must stay out are most of a game directory and each of them has a
 * reason: `versions/`, `libraries/` and `assets/` are the game, which a pack
 * asks an importer to install rather than carrying; `saves/`, `backups/`,
 * `logs/` and `crash-reports/` are this instance's own history, and a pack is
 * something you send to someone. A dot-file or a dot-directory is never
 * included either — that is where tools put their own state.
 */
export function packIncludes(relativePath: string): boolean {
  const parts = relativePath.replace(/\\/g, '/').split('/').filter((part) => part !== '')
  if (parts.length === 0) return false
  if (parts.some((part) => part.startsWith('.'))) return false
  return PACK_CONTENTS.includes(relativePath) || PACK_CONTENTS.includes(parts[0])
}

/** The name an instance file takes inside the archive. */
export function packArchivePath(relativePath: string): string {
  return `overrides/${relativePath.replace(/\\/g, '/')}`
}

export interface PackIndexInput {
  name: string
  /** The pack's own version, which is not the game's. */
  versionId: string
  gameVersion: string | null
  loader: InstallLoader | null
  loaderVersion: string | null
  /** Shown by launchers that list a pack's description. */
  summary?: string
}

/**
 * The index, as the format spells it.
 *
 * `dependencies` is what tells an importer which game and loader to install
 * first, and it carries only what the instance actually has: a vanilla instance
 * gets no loader key rather than a made-up one.
 */
export function buildPackIndex(input: PackIndexInput): Record<string, unknown> {
  const dependencies: Record<string, string> = {}
  if (input.gameVersion) dependencies.minecraft = input.gameVersion
  if (input.loader && input.loader !== 'vanilla' && input.loaderVersion) {
    dependencies[`${input.loader}-loader`] = input.loaderVersion
  }

  return {
    formatVersion: 1,
    game: 'minecraft',
    versionId: input.versionId,
    name: input.name,
    ...(input.summary ? { summary: input.summary } : {}),
    // Files with download URLs would be listed here; every file this launcher
    // exports is carried in the archive instead.
    files: [],
    dependencies
  }
}

/** `20261014-1530`, for a pack exported without a version of its own. */
export function packVersionId(at: Date = new Date()): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  return (
    `${at.getFullYear()}${pad(at.getMonth() + 1)}${pad(at.getDate())}-` +
    `${pad(at.getHours())}${pad(at.getMinutes())}`
  )
}

export { MODRINTH_INDEX_FILE }

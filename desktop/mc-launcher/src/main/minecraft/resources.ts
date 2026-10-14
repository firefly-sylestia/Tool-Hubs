import type { ResourceKind } from '@shared/types'

/**
 * Which folder holds which kind of resource, and what a disabled one looks
 * like on disk.
 *
 * Disabling is a rename: every mod loader, resource pack list and shader loader
 * ignores anything that is not `.jar`/`.zip`, so `sodium.jar.disabled` is
 * invisible to the game while staying exactly where the user can turn it back
 * on. That is the same trick every launcher uses, and the reason this file
 * carries the suffix rather than a per-instance enabled/disabled list that
 * could drift from reality.
 */

export const RESOURCE_FOLDERS: Record<ResourceKind, string> = {
  mod: 'mods',
  resourcepack: 'resourcepacks',
  shaderpack: 'shaderpacks'
}

export const RESOURCE_KINDS: ResourceKind[] = ['mod', 'resourcepack', 'shaderpack']

export const DISABLED_SUFFIX = '.disabled'

/** Extensions the game will actually load, per kind. */
const LOADABLE_EXTENSIONS: Record<ResourceKind, string[]> = {
  // Forge-era mods were zips as well as jars, and resource packs and shaders are zips.
  mod: ['.jar', '.zip'],
  resourcepack: ['.zip'],
  shaderpack: ['.zip']
}

export function folderForKind(kind: ResourceKind): string {
  return RESOURCE_FOLDERS[kind]
}

export function kindForFolder(folder: string): ResourceKind | null {
  const match = RESOURCE_KINDS.find((kind) => RESOURCE_FOLDERS[kind] === folder)
  return match ?? null
}

/** `sodium.jar.disabled` -> `{ title: 'sodium.jar', enabled: false }`. */
export function splitResourceName(name: string): { title: string; enabled: boolean } {
  if (name.toLowerCase().endsWith(DISABLED_SUFFIX)) {
    return { title: name.slice(0, -DISABLED_SUFFIX.length), enabled: false }
  }
  return { title: name, enabled: true }
}

export function resourceFileName(title: string, enabled: boolean): string {
  return enabled ? title : `${title}${DISABLED_SUFFIX}`
}

/**
 * Whether a file belongs in a resource list.
 *
 * Dotfiles, editor leftovers and files the game cannot load are skipped: a
 * `.DS_Store` or a half-downloaded `.part` is not a mod, and listing it as one
 * that "can be disabled" would be a lie.
 */
export function isResourceFile(name: string, kind: ResourceKind): boolean {
  if (name.startsWith('.')) return false
  if (name.endsWith('.part') || name.endsWith('.tmp')) return false
  const { title, enabled: _enabled } = splitResourceName(name)
  if (title.startsWith('.')) return false
  const lower = title.toLowerCase()
  return LOADABLE_EXTENSIONS[kind].some((extension) => lower.endsWith(extension))
}

/**
 * Sorts for display: enabled first, then by name, so the list does not
 * reshuffle every time a mod is toggled.
 */
export function sortResourceNames(names: string[]): string[] {
  return [...names].sort((left, right) => {
    const a = splitResourceName(left)
    const b = splitResourceName(right)
    if (a.enabled !== b.enabled) return a.enabled ? -1 : 1
    return a.title.localeCompare(b.title)
  })
}

/** What to say about a file a user is about to add to a kind's folder. */
export function explainUnsupportedAddition(name: string, kind: ResourceKind): string | null {
  const lower = name.toLowerCase()
  if (lower.endsWith('.jar') && kind === 'resourcepack') {
    return `${name} is a jar, which resource packs are not — did you mean to add it to mods?`
  }
  if (kind === 'shaderpack' && !lower.endsWith('.zip')) {
    return `${name} is not a zip, and shader packs are loaded from zips.`
  }
  return null
}

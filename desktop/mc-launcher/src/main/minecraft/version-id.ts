/**
 * What a version id says about the game under it.
 *
 * An instance's `versionId` is the id of a version json, and for anything
 * modded that is a loader profile — `fabric-loader-0.16.9-1.21.4` — not a
 * Minecraft version. Modrinth has never heard of the former, and a mod that
 * asks for `>=1.20` cannot be checked against it either, so every question
 * about "which Minecraft is this" goes through here rather than reading the id
 * as if it were one.
 */

/**
 * The game version a version id plays.
 *
 * The instance's version id may be a loader profile, such as
 * `fabric-loader-0.16.9-1.21.4`, and an importer needs the game underneath it
 * rather than the loader that sits on top. Both ends of that id are version
 * numbers, so the game is taken as the last run of segments that starts one; a
 * plain `1.21.4-pre1` therefore keeps its pre-release suffix.
 */
export function baseGameVersion(versionId: string): string | null {
  if (versionId === '') return null
  const segments = versionId.split('-')
  for (let index = segments.length - 1; index >= 0; index -= 1) {
    if (/^\d+(\.\d+)+/.test(segments[index])) return segments.slice(index).join('-')
  }
  return null
}

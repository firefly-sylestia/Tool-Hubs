import { copyFileSync, existsSync, linkSync, mkdirSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, sep } from 'node:path'
import type { PlannedFile } from './install-plan'

/**
 * Version folders another game directory has, brought into the shared folder.
 *
 * A version's json and its jar are the two files a launch needs before anything
 * else can be planned: without the json the version cannot be read at all, and
 * nothing below it — its libraries, its assets — can be looked for. They are
 * brought over first, by copy (the json is tiny and a link to it is one more
 * thing that can break) and by link for the jar, which is the big file.
 *
 * This is what moves an installation made before the shared folder existed: the
 * versions it holds are adopted, and everything they need follows from the
 * same sources the next install or repair already looks in.
 */
export function adoptVersions(input: { sharedDir: string; sources: string[] }): string[] {
  const taken: string[] = []
  for (const source of input.sources) {
    let entries: { name: string; isDirectory(): boolean }[] = []
    try {
      entries = readdirSync(join(source, 'versions'), { withFileTypes: true })
    } catch {
      // No versions directory here, which is the common case.
      continue
    }
    for (const entry of entries) {
      if (!entry.isDirectory()) continue
      const id = entry.name
      const json = join(source, 'versions', id, `${id}.json`)
      if (!existsSync(json)) continue
      const dir = join(input.sharedDir, 'versions', id)
      if (existsSync(join(dir, `${id}.json`))) continue

      mkdirSync(dir, { recursive: true })
      copyFileSync(json, join(dir, `${id}.json`))
      const jar = join(source, 'versions', id, `${id}.jar`)
      if (existsSync(jar)) {
        try {
          linkSync(jar, join(dir, `${id}.jar`))
        } catch {
          try {
            copyFileSync(jar, join(dir, `${id}.jar`))
          } catch {
            // Neither a link nor a copy: the installer will fetch the jar.
          }
        }
      }
      taken.push(id)
    }
  }
  return taken
}

/**
 * Filling the launcher's game folder in from copies of the game it can already
 * see.
 *
 * The launcher keeps one copy of Minecraft's own files and every instance plays
 * from it, so the first version installed is the only one that has to be
 * downloaded. The files before it are still on disk somewhere, though — an
 * instance imported from the official launcher, a game directory pointed at in
 * place, or the launcher's own folder from before this existed — and the same
 * relative path under any of those is the same file, because every one of them
 * is content-addressed or hash-verified: `assets/objects/<hash>` names its own
 * contents, `libraries/org/example/1.0/example-1.0.jar` is one published
 * artifact, and a version's jar comes from Mojang.
 *
 * A hard link is used when the filesystem allows it, so a second copy costs
 * nothing and exists only as a name. When it does not — a different drive, a
 * filesystem without links, Windows without permission — the file is copied,
 * which is still faster than downloading it. Nothing is ever written back to
 * the directory a file came from.
 */

export interface AdoptedFile {
  file: PlannedFile
  /** Where it was found. */
  from: string
  /** `link` when the two paths share their bytes, `copy` when they do not. */
  method: 'link' | 'copy'
}

export interface AdoptionResult {
  adopted: AdoptedFile[]
  /** The files no source had, which are the ones left to download. */
  remaining: PlannedFile[]
}

export function adoptFiles(input: {
  /** The game folder being filled in: destinations are read as paths under it. */
  sharedDir: string
  /** The files to look for. */
  files: PlannedFile[]
  /** Game directories to look in, in order. */
  sources: string[]
}): AdoptionResult {
  const adopted: AdoptedFile[] = []
  const remaining: PlannedFile[] = []
  const root = input.sharedDir.replace(/[\\/]+$/, '')

  for (const file of input.files) {
    const relativePath = relative(root, file.dest).split(sep).join('/')
    // A destination outside the game folder is not something to look for
    // elsewhere; whoever planned it owns where it goes.
    if (relativePath === '' || relativePath.startsWith('../')) {
      remaining.push(file)
      continue
    }

    const found = findIn(relativePath, file, input.sources)
    if (!found) {
      remaining.push(file)
      continue
    }

    mkdirSync(dirname(file.dest), { recursive: true })
    try {
      linkSync(found.path, file.dest)
      adopted.push({ file, from: found.source, method: 'link' })
      continue
    } catch {
      // A link across filesystems, or one this user may not make. The copy is
      // the same bytes on the same path, which is all the plan asked for.
    }
    try {
      copyFileSync(found.path, file.dest)
      adopted.push({ file, from: found.source, method: 'copy' })
    } catch {
      remaining.push(file)
    }
  }

  return { adopted, remaining }
}

/**
 * The first source holding this file, by size.
 *
 * Size rather than hash: the manifest already declared the size, the file is
 * about to be verified against its hash by nothing here, and hashing every
 * library and asset of a version to decide whether to copy it would cost more
 * than the download. A file of the wrong size is a half-finished download
 * somewhere else and is skipped, which is the one case that matters.
 */
function findIn(
  relativePath: string,
  file: PlannedFile,
  sources: string[]
): { path: string; source: string } | null {
  for (const source of sources) {
    const candidate = join(source, relativePath)
    if (!existsSync(candidate)) continue
    try {
      if (!statSync(candidate).isFile()) continue
      if (typeof file.size === 'number' && file.size > 0 && statSync(candidate).size !== file.size) {
        continue
      }
    } catch {
      continue
    }
    return { path: candidate, source }
  }
  return null
}

import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync, type Dirent } from 'node:fs'
import { isAbsolute, join, relative, sep } from 'node:path'
import type {
  InstanceSaves,
  SaveBackup,
  WorldExportResult,
  WorldImportResult,
  WorldInfo
} from '@shared/types'
import { readNbt, type NbtCompound } from '../minecraft/nbt'
import {
  backupFileName,
  describeWorld,
  detectWorldRoot,
  looksLikeWorldZip,
  sanitizeWorldFolder,
  uniqueWorldFolder
} from '../minecraft/saves'
import { extractZip, listZipEntries } from '../minecraft/zip'
import { writeZip, type ZipEntrySource } from '../minecraft/zip-writer'
import type { InstanceService } from './instance-service'

/**
 * Worlds: what an instance's `saves/` folder holds, and how one gets in or out.
 *
 * The launcher does not play the game and cannot know what a world means, so
 * this is deliberately all file work around the folder the game itself uses:
 * reading each world's own `level.dat` so the list says the real name, the
 * version that last wrote it and when, zipping one to send to someone, taking a
 * copy before something changes the game underneath it, and putting one back.
 *
 * The cap on what can be imported is the one limit here, and it is a real one:
 * an archive is read into memory before it is unpacked, so a world far larger
 * than that is copied in as a folder instead. A zip *written* here is streamed,
 * so exporting and backing up have no such limit.
 */

/** Archives are unpacked from memory; past this, a folder copy is the honest answer. */
const MAX_ARCHIVE_BYTES = 512 * 1024 * 1024

export class SaveService {
  constructor(
    private readonly instances: InstanceService,
    private readonly warn: (message: string) => void
  ) {}

  /** The worlds and the backups of an instance, newest world first. */
  list(instanceId?: string | null): InstanceSaves {
    const instance = this.instances.require(instanceId)
    const dir = savesDir(instance.dir)
    const worlds = existsSync(dir)
      ? readdirSync(dir, { withFileTypes: true })
          .filter((entry) => entry.isDirectory())
          .map((entry) => this.describe(join(dir, entry.name), entry.name))
          .sort(
            (left, right) =>
              (right.lastPlayed ?? '').localeCompare(left.lastPlayed ?? '') ||
              left.name.localeCompare(right.name)
          )
      : []

    return {
      instanceId: instance.id,
      instanceDir: instance.dir,
      dir,
      backupsDir: backupsDir(instance.dir),
      worlds,
      backups: this.backups(instance.dir)
    }
  }

  /**
   * Zips `saves/` into the instance's own `backups/` folder.
   *
   * `before-change` is the one taken automatically before an install that
   * changes the game a world was written by, and its name says so, so a folder
   * of backups reads as what it is years later.
   */
  async backup(input: {
    instanceId?: string | null
    kind?: SaveBackup['kind']
  } = {}): Promise<SaveBackup> {
    const instance = this.instances.require(input.instanceId)
    const kind = input.kind ?? 'manual'
    const source = savesDir(instance.dir)
    if (!existsSync(source)) {
      throw new Error(`Instance "${instance.name}" has no saves folder yet; there is nothing to back up.`)
    }

    const at = new Date()
    const dir = backupsDir(instance.dir)
    mkdirSync(dir, { recursive: true })
    const name = backupFileName(at, kind)
    const path = join(dir, name)
    const files = walkFiles(source)
    if (files.length === 0) {
      throw new Error(`Instance "${instance.name}" has no world files to back up yet.`)
    }

    const entries: ZipEntrySource[] = files.map((file) => ({
      name: `saves/${relative(source, file).split(sep).join('/')}`,
      path: file
    }))
    const written = await writeZip(path, entries, { at })
    this.warn(
      `Backed up ${files.length} world file(s) of ${instance.name} to ${path} ` +
        `(${formatMegabytes(written.bytes)}).`
    )
    return { name, path, bytes: statSync(path).size, at: at.toISOString(), kind }
  }

  /**
   * A backup taken only when there is something to lose.
   *
   * Every install goes through this, and an install into a fresh instance has no
   * worlds: writing an empty archive there would fill the folder with noise and
   * teach nobody anything.
   */
  async backupBeforeChange(instanceId?: string | null): Promise<SaveBackup | null> {
    const instance = this.instances.require(instanceId)
    const dir = savesDir(instance.dir)
    if (!existsSync(dir) || walkFiles(dir).length === 0) return null
    try {
      return await this.backup({ instanceId: instance.id, kind: 'before-change' })
    } catch (error) {
      // A failed backup must not become a failed install: it is a safety net,
      // and the install is what the user asked for. It is reported loudly.
      this.warn(
        `Could not back up the worlds of ${instance.name} before changing it: ` +
          `${(error as Error).message}`
      )
      return null
    }
  }

  /** Writes one world into a zip in a folder the user chose. */
  async exportWorld(input: {
    instanceId?: string | null
    folder: string
    destDir: string
  }): Promise<WorldExportResult> {
    const instance = this.instances.require(input.instanceId)
    if (!isAbsolute(input.destDir)) throw new Error('Choose an absolute folder to export into.')
    const world = join(savesDir(instance.dir), input.folder)
    if (!existsSync(world) || !statSync(world).isDirectory()) {
      throw new Error(`${input.folder} is not a world in ${instance.name}.`)
    }

    const at = new Date()
    const path = join(input.destDir, `${sanitizeWorldFolder(input.folder)}.zip`)
    const entries: ZipEntrySource[] = walkFiles(world).map((file) => ({
      name: `${sanitizeWorldFolder(input.folder)}/${relative(world, file).split(sep).join('/')}`,
      path: file
    }))
    if (entries.length === 0) throw new Error(`${input.folder} has no files in it.`)

    const written = await writeZip(path, entries, { at })
    this.warn(`Exported ${input.folder} to ${path} (${formatMegabytes(written.bytes)}).`)
    return { path, files: written.files, bytes: statSync(path).size }
  }

  /**
   * Puts a world into the instance from a folder or a zip.
   *
   * A folder is copied as it is, which is the case for a world taken from another
   * installation. A zip is read and unpacked, whether it holds `<world>/level.dat`
   * or a bare `level.dat`, and one that holds two worlds is refused rather than
   * half-imported. A folder name that is already taken gets a number, because the
   * game lists folders and the world that is already there must survive.
   */
  async importWorld(input: {
    instanceId?: string | null
    source: string
  }): Promise<WorldImportResult> {
    const instance = this.instances.require(input.instanceId)
    const destination = savesDir(instance.dir)
    mkdirSync(destination, { recursive: true })
    const existing = readdirSync(destination, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)

    if (!existsSync(input.source)) throw new Error(`${input.source} does not exist.`)
    const stats = statSync(input.source)

    if (stats.isDirectory()) {
      return await this.importFolder(input.source, destination, existing)
    }
    if (!looksLikeWorldZip(input.source)) {
      throw new Error(`Choose a world folder, a .zip or a .mcworld: ${input.source} is none of those.`)
    }
    if (stats.size > MAX_ARCHIVE_BYTES) {
      throw new Error(
        `${input.source} is ${formatMegabytes(stats.size)}, and archives are unpacked from memory. ` +
          `Unzip it and import the world folder instead.`
      )
    }

    const buffer = readFileSync(input.source)
    const root = detectWorldRoot(listZipEntries(buffer).map((entry) => entry.name))
    if (root === null) {
      throw new Error(
        `${input.source} does not hold one world: a world archive has a level.dat, either at the ` +
          `top or inside a single folder.`
      )
    }

    const folder = uniqueWorldFolder(root === '' ? baseName(input.source) : root, existing)
    const prefix = root === '' ? '' : `${root}/`
    let bytes = 0
    const files = extractZip(buffer, destination, {
      rename: (name) => (name.startsWith(prefix) ? `${folder}/${name.slice(prefix.length)}` : null),
      onEntry: (_name, size) => {
        bytes += size
      }
    })
    if (files === 0) throw new Error(`${input.source} holds no files to import.`)

    this.warn(`Imported ${folder} into ${instance.name}: ${files} file(s), ${formatMegabytes(bytes)}.`)
    return { folder, files, bytes, source: input.source }
  }

  private async importFolder(
    source: string,
    destination: string,
    existing: string[]
  ): Promise<WorldImportResult> {
    // A folder holding a level.dat is the world; one holding a single folder that
    // does is the world's parent, which is what a copy out of another launcher's
    // `saves/` looks like.
    const direct = existsSync(join(source, 'level.dat'))
    const candidates = direct
      ? [{ path: source, name: baseName(source) }]
      : readdirSync(source, { withFileTypes: true })
          .filter((entry) => entry.isDirectory() && existsSync(join(source, entry.name, 'level.dat')))
          .map((entry) => ({ path: join(source, entry.name), name: entry.name }))

    if (candidates.length === 0) {
      throw new Error(`${source} holds no world: no level.dat, in it or in a folder inside it.`)
    }
    if (candidates.length > 1) {
      throw new Error(
        `${source} holds ${candidates.length} worlds (${candidates
          .map((candidate) => candidate.name)
          .join(', ')}). Import them one at a time.`
      )
    }

    const [candidate] = candidates
    const folder = uniqueWorldFolder(candidate.name, existing)
    const files = walkFiles(candidate.path)
    let bytes = 0
    for (const file of files) {
      const contents = readFileSync(file)
      const target = join(destination, folder, relative(candidate.path, file).split(sep).join('/'))
      mkdirSync(join(target, '..'), { recursive: true })
      writeFileSync(target, contents)
      bytes += contents.length
    }
    return { folder, files: files.length, bytes, source }
  }

  private describe(worldDir: string, folder: string): WorldInfo {
    const levelPath = join(worldDir, 'level.dat')
    let level: NbtCompound | null = null
    let problem: string | null = null
    if (!existsSync(levelPath)) {
      problem = 'This folder has no level.dat, so the game will not list it.'
    } else {
      try {
        level = readNbt(readFileSync(levelPath)).root
      } catch (error) {
        problem = (error as Error).message
      }
    }
    return describeWorld({ folder, bytes: directorySize(worldDir), level, problem })
  }

  private backups(instanceDir: string): SaveBackup[] {
    const dir = backupsDir(instanceDir)
    if (!existsSync(dir)) return []
    return readdirSync(dir)
      .filter((name) => name.endsWith('.zip'))
      .map((name) => {
        const path = join(dir, name)
        const stats = statSync(path)
        return {
          name,
          path,
          bytes: stats.size,
          at: stats.mtime.toISOString(),
          kind: name.startsWith('before-') ? ('before-change' as const) : ('manual' as const)
        }
      })
      .sort((left, right) => right.at.localeCompare(left.at))
  }
}

function savesDir(instanceDir: string): string {
  return join(instanceDir, 'saves')
}

function backupsDir(instanceDir: string): string {
  return join(instanceDir, 'backups')
}

/** Every file under a directory, depth first, in a stable order. */
function walkFiles(dir: string): string[] {
  const found: string[] = []
  let entries: Dirent[]
  try {
    entries = readdirSync(dir, { withFileTypes: true })
  } catch {
    return found
  }
  for (const entry of [...entries].sort((left, right) => left.name.localeCompare(right.name))) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) found.push(...walkFiles(path))
    else if (entry.isFile()) found.push(path)
  }
  return found
}

function directorySize(dir: string): number {
  return walkFiles(dir).reduce((total, file) => {
    try {
      return total + statSync(file).size
    } catch {
      return total
    }
  }, 0)
}

function baseName(path: string): string {
  return path.split(/[\\/]/).filter((part) => part !== '').pop() ?? 'world'
}

function formatMegabytes(bytes: number): string {
  if (bytes <= 0) return '0 MB'
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1048576).toFixed(1)} MB`
}

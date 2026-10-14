import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { basename, extname, join, relative, sep } from 'node:path'
import type { ModpackImportResult, ModpackSummary, PackExportResult } from '@shared/types'
import { downloadAll, type DownloadProgress } from '../minecraft/download'
import {
  MODRINTH_INDEX_FILE as PACK_INDEX_FILE,
  buildPackIndex,
  packArchivePath,
  packIncludes,
  packVersionId
} from '../minecraft/pack-export'
import {
  CURSEFORGE_MANIFEST_FILE,
  MODRINTH_INDEX_FILE,
  detectModpackFormat,
  instancePathFor,
  parseCurseForgeManifest,
  parseModrinthIndex,
  type ModpackPlan
} from '../minecraft/modpack'
import { baseGameVersion } from '../minecraft/version-id'
import { extractZip, listZipEntries, readZipEntry } from '../minecraft/zip'
import { writeZip, type ZipEntrySource } from '../minecraft/zip-writer'
import type { InstanceService } from './instance-service'
import type { InstallService } from './install-service'
import type { TaskService } from './task-service'

/**
 * Importing a modpack.
 *
 * A pack is a zip with an index, so importing one is four steps: read the
 * index, create an instance, install the game and loader it asks for, then put
 * the pack's own files in place — downloading the ones it links to and
 * unpacking the ones it carries.
 */
/**
 * The two extensions a pack file can have.
 *
 * A Modrinth pack is a `.mrpack`, which is a zip with a `modrinth.index.json`
 * inside. CurseForge has no extension of its own: its export is a plain `.zip`
 * with a `manifest.json`. Both are read by what is inside them, so the extension
 * is only ever a first answer to "is this the file you meant?".
 */
export const PACK_EXTENSIONS = ['.mrpack', '.zip']

export class ModpackService {
  constructor(
    private readonly install: InstallService,
    private readonly instances: InstanceService,
    private readonly tasks: TaskService,
    private readonly warn: (message: string) => void
  ) {}

  /** Reads a pack without writing anything, so the UI can say what it is first. */
  inspect(path: string): ModpackSummary {
    return this.read(path).plan.summary
  }

  /**
   * Writes an instance out as a `.mrpack` someone else can import.
   *
   * The pack is self-contained: every file is carried under `overrides/` rather
   * than linked, because the launcher does not record where a file came from and
   * a made-up URL would be worse than a larger archive. The index still names
   * the game version and the loader, which is what an importer needs to build
   * the instance this one runs.
   */
  export(input: {
    instanceId?: string | null
    destDir: string
    name?: string
    versionId?: string
  }): Promise<PackExportResult> {
    const instance = this.instances.require(input.instanceId)
    if (!existsSync(input.destDir) || !statSync(input.destDir).isDirectory()) {
      throw new Error(`Choose an existing folder to export into: ${input.destDir} is not one.`)
    }
    return this.tasks.run(`Export ${instance.name} as a pack`, async (report) => {
      report(0.1, 'Working out what the pack carries')
      const files = walkIncluded(instance.dir)
      if (files.length === 0) {
        throw new Error(
          `${instance.name} has nothing to export: no mods, no resource packs, no shader packs and no ` +
            `config. A pack is those files; install the game in it or add something first.`
        )
      }

      const versionId = input.versionId?.trim() || packVersionId(new Date())
      const name = input.name?.trim() || instance.name
      const index = buildPackIndex({
        name,
        versionId,
        gameVersion: instance.versionId ? baseGameVersion(instance.versionId) : null,
        loader: instance.loader,
        loaderVersion: instance.loaderVersion
      })

      const entries: ZipEntrySource[] = [
        { name: PACK_INDEX_FILE, contents: Buffer.from(`${JSON.stringify(index, null, 2)}\n`, 'utf8') },
        ...files.map((file) => ({
          name: packArchivePath(relative(instance.dir, file).split(sep).join('/')),
          path: file
        }))
      ]

      const target = `${input.destDir.replace(/[\\/]+$/, '')}/${sanitizePackName(name)}.mrpack`
      report(0.35, `Writing ${entries.length - 1} file(s) into ${basename(target)}`)
      const written = await writeZip(target, entries)
      report(1, 'Done')
      this.warn(`Exported ${name} ${versionId} to ${target} (${written.files} files).`)

      return {
        path: target,
        name,
        versionId,
        instanceId: instance.id,
        gameVersion: (index.dependencies as Record<string, string>).minecraft ?? null,
        loader: instance.loader,
        loaderVersion: instance.loaderVersion,
        files: written.files,
        bytes: statSync(target).size
      }
    })
  }

  import(path: string): Promise<ModpackImportResult> {
    return this.tasks.run(`Import ${basename(path)}`, (report) => this.perform(path, report))
  }

  private read(path: string): { plan: ModpackPlan; buffer: Buffer } {
    if (typeof path !== 'string' || path.trim() === '') {
      throw new Error('Importing a modpack needs a file: choose a .mrpack or a .zip first.')
    }
    if (!existsSync(path)) {
      throw new Error(`${path} is not there any more. Choose the pack again.`)
    }
    // Checked by name as well as by content, so choosing the wrong kind of file
    // is answered before the archive is read: a pack is a `.mrpack` from
    // Modrinth or a `.zip` from CurseForge, whatever it is called inside.
    const extension = extname(path).toLowerCase()
    if (!PACK_EXTENSIONS.includes(extension)) {
      throw new Error(
        `${basename(path)} is not a modpack file: a Modrinth pack is a \`.mrpack\` and a ` +
          `CurseForge pack is a \`.zip\`. Choose one of those.`
      )
    }
    const buffer = readFileSync(path)
    const entries = listZipEntries(buffer)
    const names = entries.map((entry) => entry.name)
    const format = detectModpackFormat(names)
    if (!format) {
      throw new Error(
        `${basename(path)} is not a modpack: it has no ${MODRINTH_INDEX_FILE} (Modrinth) and no ` +
          `${CURSEFORGE_MANIFEST_FILE} (CurseForge) inside.`
      )
    }

    const indexName = format === 'modrinth' ? MODRINTH_INDEX_FILE : CURSEFORGE_MANIFEST_FILE
    const indexEntry = entries.find((entry) => entry.name === indexName)!
    let payload: unknown
    try {
      payload = JSON.parse(readZipEntry(buffer, indexEntry).toString('utf8'))
    } catch (error) {
      throw new Error(`${indexName} in ${basename(path)} is not valid JSON: ${(error as Error).message}`)
    }

    const plan = format === 'modrinth' ? parseModrinthIndex(payload) : parseCurseForgeManifest(payload)
    plan.summary.bundled = names.filter((name) => instancePathFor(name, plan) !== null).length
    return { plan, buffer }
  }

  private async perform(
    path: string,
    report: (progress: number, message: string) => void
  ): Promise<ModpackImportResult> {
    report(0.02, 'Reading the pack')
    const { plan, buffer } = this.read(path)
    const summary = plan.summary

    report(0.05, `Creating an instance for ${summary.name}`)
    const instance = this.instances.create({ name: this.uniqueName(summary.name), source: 'modpack' })

    let installed = { targetId: null as string | null, bytes: 0, files: 0, reused: 0 }
    if (summary.gameVersion) {
      const loader = summary.loader ?? 'vanilla'
      report(0.08, `Installing ${summary.gameVersion}${loader === 'vanilla' ? '' : ` with ${loader}`}`)
      const result = await this.install.installInto(
        instance.id,
        {
          versionId: summary.gameVersion,
          loader,
          loaderVersion: summary.loaderVersion
        },
        (progress, message) => report(0.08 + progress * 0.55, message)
      )
      installed = {
        targetId: result.targetId,
        bytes: result.bytes,
        files: result.files,
        reused: result.reused
      }
    } else {
      this.warn(`${summary.name} names no Minecraft version; install one in the instance before playing.`)
    }

    let packBytes = 0
    let packFiles = 0
    let packReused = 0
    if (plan.files.length > 0) {
      report(0.65, `Downloading ${plan.files.length} pack file(s)`)
      const result = await downloadAll(
        plan.files.map((file) => ({
          url: file.url,
          dest: joinInside(instance.dir, file.path),
          sha1: file.sha1,
          sha512: file.sha512,
          size: file.size,
          label: file.path
        })),
        {
          onProgress: (progress: DownloadProgress) => {
            const fraction =
              progress.bytesTotal > 0 ? progress.bytesDone / progress.bytesTotal : progress.completed / progress.total
            report(0.65 + 0.25 * Math.min(1, fraction), `${progress.completed}/${progress.total} pack files`)
          }
        }
      )
      packBytes = result.bytes
      packFiles = result.downloaded
      packReused = result.reused
    }

    report(0.92, 'Unpacking the pack’s own files')
    const overrides = extractZip(buffer, instance.dir, {
      rename: (name) => instancePathFor(name, plan)
    })

    this.instances.update(instance.id, {
      versionId: installed.targetId,
      loader: summary.loader,
      loaderVersion: summary.loaderVersion
    })
    this.instances.resources(instance.id)

    const unresolved = plan.unresolved
    if (unresolved.length > 0) {
      this.warn(
        `${summary.name} names ${unresolved.length} file(s) that need CurseForge's API, which this ` +
          `launcher does not call. Add them by hand: ${unresolved.slice(0, 10).join(', ')}` +
          (unresolved.length > 10 ? ` and ${unresolved.length - 10} more.` : '.')
      )
    }
    report(1, 'Done')

    const updated = this.instances.require(instance.id)
    return {
      summary,
      instance: updated,
      files: packFiles,
      reused: packReused + installed.reused,
      bytes: packBytes + installed.bytes,
      overrides,
      unresolved
    }
  }

  private uniqueName(name: string): string {
    const taken = this.instances.list().map((instance) => instance.name.toLowerCase())
    if (!taken.includes(name.toLowerCase())) return name
    for (let index = 2; index < 1000; index += 1) {
      const candidate = `${name} ${index}`
      if (!taken.includes(candidate.toLowerCase())) return candidate
    }
    return `${name} ${Date.now()}`
  }
}

/**
 * The instance's own files a pack carries, depth first.
 *
 * `save/`, `logs/` and `versions/` are excluded by {@link packIncludes}, so this
 * walks everything and lets that decide, rather than walking only the folders it
 * expects and missing whatever a mod added beside them.
 */
function walkIncluded(root: string): string[] {
  const found: string[] = []
  const visit = (dir: string): void => {
    let entries
    try {
      entries = readdirSync(dir, { withFileTypes: true })
    } catch {
      return
    }
    for (const entry of [...entries].sort((left, right) => left.name.localeCompare(right.name))) {
      const path = join(dir, entry.name)
      const relativePath = relative(root, path).split(sep).join('/')
      if (entry.isDirectory()) {
        if (packIncludes(relativePath)) visit(path)
        continue
      }
      if (entry.isFile() && packIncludes(relativePath)) found.push(path)
    }
  }
  visit(root)
  return found
}

/** A file name from a pack name, so an export lands next to its own kind. */
function sanitizePackName(name: string): string {
  const cleaned = name.replace(/[\\/:*?"<>|]/g, '-').trim()
  return cleaned === '' ? 'pack' : cleaned
}

/**
 * A pack names its files with forward slashes and promises they stay inside the
 * instance, but a pack is untrusted input, so the path is checked rather than
 * trusted.
 */
function joinInside(root: string, relative: string): string {
  const parts = relative.split('/').filter((part) => part !== '' && part !== '.')
  if (parts.some((part) => part === '..')) {
    throw new Error(`The pack tries to write outside the instance: ${relative}`)
  }
  return `${root.replace(/[\\/]+$/, '')}/${parts.join('/')}`
}

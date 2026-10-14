import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type {
  GameInstance,
  InstallLoader,
  ModrinthStatus,
  ModpackImportResult,
  ModrinthInstallResult,
  ModrinthInstalledItem,
  ModrinthProject,
  ModrinthProjectDetail,
  ModrinthSearch,
  ModrinthVersion,
  ResourceKind,
  StoreInstall,
  StoreInstalls
} from '@shared/types'
import { downloadAll, fetchJson, type DownloadProgress, type FetchLike } from '../minecraft/download'
import {
  bestVersion,
  parseProjectDetail,
  parseProjectList,
  parseSearchResponse,
  parseVersion,
  parseVersionList,
  primaryFile,
  projectsByIdsUrl,
  projectVersionsUrl,
  requiredDependencies,
  projectUrl,
  searchUrl,
  userAgent,
  versionUrl,
  type SearchSort
} from '../minecraft/modrinth'
import { RESOURCE_KINDS, folderForKind } from '../minecraft/resources'
import { baseGameVersion } from '../minecraft/version-id'
import type { AppEnvironment } from './env'
import type { InstanceService } from './instance-service'
import type { ModpackService } from './modpack-service'
import type { TaskService } from './task-service'

/** Where an instance remembers what the store put in it, relative to its own root. */
const INSTALL_RECORD = '.toolhubs/store.json'

/**
 * Whether a record entry is one this launcher can use.
 *
 * Every entry is a file name inside a folder named by its kind, so an entry
 * with neither — one this launcher did not write, or one written by an older
 * shape — is not a record of anything. Kept as a predicate rather than trusted
 * because a single unusable entry used to make every later read of the record
 * throw the runtime's own path error, and stayed in the file to do it again.
 */
function isStoreInstall(value: unknown): value is StoreInstall {
  if (value === null || typeof value !== 'object') return false
  const entry = value as Partial<StoreInstall>
  return (
    typeof entry.projectId === 'string' &&
    typeof entry.filename === 'string' &&
    entry.filename !== '' &&
    typeof entry.kind === 'string' &&
    (RESOURCE_KINDS as string[]).includes(entry.kind)
  )
}

/**
 * Reads that record, and treats one it cannot read as empty: the files are the
 * source of truth, and the launcher does not get to claim what it cannot show.
 * An entry that cannot be read as one is dropped rather than kept.
 */
export function readStoreInstalls(instanceDir: string): StoreInstall[] {
  if (typeof instanceDir !== 'string' || instanceDir === '') return []
  const path = join(instanceDir, INSTALL_RECORD)
  if (!existsSync(path)) return []
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8')) as { installs?: unknown[] }
    return Array.isArray(parsed.installs) ? parsed.installs.filter(isStoreInstall) : []
  } catch {
    return []
  }
}

export function writeStoreInstalls(instanceDir: string, installs: StoreInstall[]): void {
  const dir = join(instanceDir, '.toolhubs')
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'store.json'), `${JSON.stringify({ version: 1, installs }, null, 2)}\n`)
}

/**
 * The installs that are still on disk.
 *
 * A file deleted by hand is not installed, whatever the record says: the store's
 * markers are read against the folder they describe rather than believed.
 */
export function presentInstalls(instanceDir: string, installs: StoreInstall[]): StoreInstall[] {
  return installs.filter(
    (entry) =>
      isStoreInstall(entry) && existsSync(join(instanceDir, folderForKind(entry.kind), entry.filename))
  )
}

/** How deep a dependency chain is followed, so one project cannot fan out forever. */
const DEPENDENCY_DEPTH = 3
/** A ceiling on dependencies per install, for the same reason. */
const MAX_DEPENDENCIES = 25

export interface SearchInput {
  query: string
  /** A resource kind, or `modpack` for whole packs, which become instances. */
  kind: ResourceKind | 'modpack'
  /** When omitted, the selected instance's version and loader are used. */
  gameVersion?: string | null
  loader?: InstallLoader | null
  /** True to ignore the instance entirely and search the whole catalogue. */
  anyVersion?: boolean
  sort?: SearchSort | null
  limit?: number
  offset?: number
}

export interface InstallInput {
  projectId?: string | null
  /** A specific version, when the user picked one from the list. */
  versionId?: string | null
  kind: ResourceKind
  instanceId?: string | null
}

/**
 * Installing mods, resource packs and shader packs from Modrinth.
 *
 * Three things make this more than a download: the version has to match the
 * instance (its game version and its loader), every file's hash is checked
 * against what the publisher declared rather than trusted because it arrived,
 * and the mods a project requires come with it — a mod that silently needs
 * another one is the most common way a modded launch fails.
 *
 * Modrinth answers without an API key, so nothing here needs one from the user.
 */
export class ModrinthService {
  constructor(
    private readonly env: AppEnvironment,
    private readonly instances: InstanceService,
    private readonly modpacks: ModpackService,
    private readonly tasks: TaskService,
    private readonly warn: (message: string) => void,
    private readonly options: { fetchImpl?: FetchLike } = {}
  ) {}

  /** Modrinth asks to know who is calling; an unnamed client gets refused. */
  private get headers(): Record<string, string> {
    return { 'User-Agent': userAgent(this.env.launcherName, this.env.launcherVersion) }
  }

  private async json<T>(url: string): Promise<T> {
    return await fetchJson<T>(url, { fetchImpl: this.options.fetchImpl, headers: this.headers })
  }

  /**
   * The instance supplies the game version and loader the search is filtered by,
   * unless the caller asked for everything: browsing a catalogue should not need
   * an instance, and should be able to ignore the one that happens to be
   * selected.
   */
  private target(input: {
    gameVersion?: string | null
    loader?: InstallLoader | null
    anyVersion?: boolean
  }): {
    instance: ReturnType<InstanceService['selected']>
    gameVersion: string | null
    loader: InstallLoader | null
  } {
    const instance = input.anyVersion ? null : this.instances.selected()
    return {
      instance,
      gameVersion: input.anyVersion ? null : (input.gameVersion ?? gameVersionOf(instance)),
      loader: input.anyVersion ? null : (input.loader ?? instance?.loader ?? null)
    }
  }

  /**
   * Whether Modrinth answers at all, and what it said when it did not.
   *
   * "Modrinth could not be reached" is not an answer anyone can act on: a
   * refused connection, a proxy that will not pass the request and a rate limit
   * all look the same from the outside, and only one of them is fixed by trying
   * again. So this reports what came back instead of guessing, and the store
   * shows it beside the button that caused it.
   */
  async status(): Promise<ModrinthStatus> {
    const started = Date.now()
    try {
      const parsed = parseSearchResponse(
        await this.json<unknown>(searchUrl({ query: '', kind: 'mod', limit: 1 }))
      )
      return {
        reachable: true,
        elapsedMs: Date.now() - started,
        totalHits: parsed.totalHits,
        error: null
      }
    } catch (reason) {
      return {
        reachable: false,
        elapsedMs: Date.now() - started,
        totalHits: 0,
        error: (reason as Error).message
      }
    }
  }

  async search(input: SearchInput): Promise<ModrinthSearch & { folder: string }> {
    const { gameVersion, loader } = this.target(input)
    const payload = await this.json<unknown>(
      searchUrl({
        query: input.query,
        kind: input.kind,
        gameVersion,
        loader,
        sort: input.sort ?? 'relevance',
        limit: input.limit ?? 20,
        offset: input.offset ?? 0
      })
    )
    const folder = input.kind === 'modpack' ? 'a new instance' : folderForKind(input.kind)
    return { ...parseSearchResponse(payload), folder }
  }

  /**
   * What the store has installed into an instance.
   *
   * The record is read against the disk: a file somebody deleted by hand is not
   * reported as installed, so the store's markers cannot outlive the files they
   * describe.
   */
  installed(input: { instanceId?: string | null }): StoreInstalls {
    const instance = this.instances.require(input.instanceId)
    return {
      instanceId: instance.id,
      dir: instance.dir,
      items: presentInstalls(instance.dir, readStoreInstalls(instance.dir))
    }
  }

  /** A project's own page, for the detail view. */
  async project(projectIdOrSlug: string): Promise<ModrinthProjectDetail> {
    const detail = parseProjectDetail(await this.json<unknown>(projectUrl(projectIdOrSlug)))
    if (!detail) throw new Error(`Modrinth has no project ${projectIdOrSlug}.`)
    return detail
  }

  async versions(input: {
    projectIdOrSlug: string
    gameVersion?: string | null
    loader?: InstallLoader | null
    /** No filter at all: what a modpack's version list needs, since it brings its own. */
    free?: boolean
  }): Promise<ModrinthVersion[]> {
    if (input.free) {
      return parseVersionList(
        await this.json<unknown>(projectVersionsUrl({ projectIdOrSlug: input.projectIdOrSlug }))
      )
    }
    const { gameVersion, loader } = this.target(input)
    const payload = await this.json<unknown>(
      projectVersionsUrl({
        projectIdOrSlug: input.projectIdOrSlug,
        gameVersion,
        loader
      })
    )
    return parseVersionList(payload)
  }

  async projects(projectIds: string[]): Promise<ModrinthProject[]> {
    if (projectIds.length === 0) return []
    return parseProjectList(await this.json<unknown>(projectsByIdsUrl(projectIds)))
  }

  /**
   * Installs a modpack from Modrinth.
   *
   * A pack is not a file in an instance, it *is* an instance: the `.mrpack` is
   * fetched, verified and then handed to the modpack importer, which creates the
   * instance and installs the game, the loader and the pack's own files. That is
   * the difference between the Store's Modpacks tab and its Mods tab.
   */
  installModpack(input: { projectId?: string | null; versionId?: string | null }): Promise<ModpackImportResult> {
    return this.tasks.run('Install modpack from Modrinth', async (report) => {
      report(0.05, 'Reading the pack from Modrinth')
      const versions = await this.freeVersions({
        projectId: input.projectId ?? null,
        versionId: input.versionId ?? null
      })
      const version = versions.version
      const file = primaryFile(version)
      if (!file) throw new Error(`${version.name} lists no file to download.`)

      const cacheDir = join(this.env.userDataDir, 'store')
      mkdirSync(cacheDir, { recursive: true })
      const dest = join(cacheDir, file.filename)
      report(0.15, `Downloading ${file.filename} (${(file.size / 1048576).toFixed(0)} MB)`)
      await downloadAll([{ url: file.url, dest, sha1: file.sha1, sha512: file.sha512, size: file.size }])
      if (!existsSync(dest)) throw new Error(`${file.filename} did not arrive.`)

      report(0.3, 'Installing the pack')
      return await this.modpacks.import(dest)
    })
  }

  /**
   * A project's newest version with no filter on game version or loader, which
   * is what a modpack needs: it names its own Minecraft version and loader, and
   * filtering by the currently selected instance would hide nearly all of them.
   */
  private async freeVersions(input: {
    projectId: string | null
    versionId: string | null
  }): Promise<{ version: ModrinthVersion }> {
    if (input.versionId) {
      const version = parseVersion(await this.json<unknown>(versionUrl(input.versionId)))
      if (!version) throw new Error(`${input.versionId} is no longer published.`)
      return { version }
    }
    if (!input.projectId) throw new Error('A modpack needs a project id.')
    const versions = parseVersionList(
      await this.json<unknown>(projectVersionsUrl({ projectIdOrSlug: input.projectId }))
    )
    const version = bestVersion(versions, { gameVersion: null, loader: null })
    if (!version) throw new Error(`${input.projectId} has no released version.`)
    return { version }
  }

  /** Installs a project — and what it requires — into an instance, as a task. */
  install(input: InstallInput): Promise<ModrinthInstallResult> {
    return this.tasks.run('Install from Modrinth', (report) => this.perform(input, report))
  }

  private async perform(
    input: InstallInput,
    report: (progress: number, message: string) => void
  ): Promise<ModrinthInstallResult> {
    const instance = this.instances.require(input.instanceId)
    if (!instance.versionId) {
      throw new Error(
        `Instance "${instance.name}" has no version yet. Install one first — what is added has to ` +
          `match it.`
      )
    }
    if (input.kind === 'mod' && (instance.loader === null || instance.loader === 'vanilla')) {
      this.warn(
        `Instance "${instance.name}" is vanilla, so a mod will not load in it however it is added.`
      )
    }

    const folder = join(instance.dir, folderForKind(input.kind))
    mkdirSync(folder, { recursive: true })

    // The game version, not the id of the version json: a Fabric instance's id
    // is `fabric-loader-0.16.9-1.21.4`, and no mod publishes a build for that.
    const gameVersion = gameVersionOf(instance)
    if (!gameVersion) {
      throw new Error(
        `Instance "${instance.name}" names no Minecraft version this can match against ` +
          `(${instance.versionId}). Install a version in it, then add what you want.`
      )
    }
    const loader = instance.loader ?? null
    const installed: ModrinthInstalledItem[] = []
    const skippedDependencies: string[] = []
    const warnings: string[] = []
    const seenProjects = new Set<string>()
    const seenFiles = new Set<string>()

    const first = await this.resolveVersion({
      projectId: input.projectId ?? null,
      versionId: input.versionId ?? null,
      gameVersion,
      loader
    })
    if (!first) throw new Error(this.noVersionMessage(input, gameVersion, loader))
    seenProjects.add(first.projectId)

    const queue: { version: ModrinthVersion; dependency: boolean; depth: number }[] = [
      { version: first, dependency: false, depth: 0 }
    ]

    while (queue.length > 0) {
      const item = queue.shift()!
      const file = primaryFile(item.version)
      if (!file) {
        if (item.dependency) skippedDependencies.push(item.version.projectId)
        else throw new Error(`${item.version.name} has no file to download.`)
        continue
      }
      // Two projects that ship the same file name would otherwise overwrite each
      // other; the first one in wins and the rest are reported.
      if (seenFiles.has(file.filename)) {
        if (item.dependency) skippedDependencies.push(item.version.projectId)
        else throw new Error(`${file.filename} is already part of this install.`)
        continue
      }
      seenFiles.add(file.filename)

      // Each file owns an equal slice of the task, so the bar moves while a
      // large file arrives instead of jumping once per file. The message says
      // the bytes, which is the only answer to "what is it doing" there is.
      const span = 1 / (installed.length + queue.length + 1)
      const base = installed.length * span
      const required = item.dependency ? ' (required by what you asked for)' : ''
      report(base, `Downloading ${item.version.name}${required}`)
      const result = await downloadAll(
        [
          {
            url: file.url,
            dest: join(folder, file.filename),
            sha1: file.sha1,
            sha512: file.sha512,
            size: file.size,
            label: file.filename
          }
        ],
        {
          // The service's own fetch, so a check can run this without a network.
          fetchImpl: this.options.fetchImpl,
          onProgress: (progress) =>
            report(
              base + span * downloadFraction(progress),
              `${item.version.name}${required}: ${describeDownload(progress, file.size)}`
            )
        }
      )

      installed.push({
        projectId: item.version.projectId,
        title: item.version.name,
        versionNumber: item.version.versionNumber,
        versionType: item.version.versionType,
        filename: file.filename,
        dependency: item.dependency,
        bytes: file.size,
        reused: result.reused === 1
      })
      if (item.version.versionType !== 'release') {
        warnings.push(
          `${item.version.name} is a ${item.version.versionType} build (${item.version.versionNumber}).`
        )
      }

      if (item.depth >= DEPENDENCY_DEPTH) continue
      if (seenProjects.size >= MAX_DEPENDENCIES) {
        warnings.push(`Stopped following dependencies after ${MAX_DEPENDENCIES} projects.`)
        continue
      }
      for (const dependency of requiredDependencies(item.version, {
        installedProjectIds: [],
        seenProjectIds: [...seenProjects]
      })) {
        if (!dependency.projectId) continue
        seenProjects.add(dependency.projectId)
        const resolved = await this.resolveVersion({
          projectId: dependency.projectId,
          versionId: dependency.versionId,
          gameVersion,
          loader
        })
        if (!resolved) {
          skippedDependencies.push(dependency.projectId)
          continue
        }
        queue.push({ version: resolved, dependency: true, depth: item.depth + 1 })
      }
    }

    if (skippedDependencies.length > 0) {
      const names = await this.namesFor(skippedDependencies)
      warnings.push(
        `No version for ${gameVersion}${loader && loader !== 'vanilla' ? ` on ${loader}` : ''} was ` +
          `found for: ${names.join(', ')}. Add those by hand, or the mod may fail to load.`
      )
      this.warn(`Dependencies not installed: ${names.join(', ')}`)
    }

    this.record(instance, input.kind, installed)

    report(1, 'Done')
    const dependencies = installed.filter((item) => item.dependency).length
    this.warn(
      `Installed ${installed.length} file(s) into ${folder}` +
        (dependencies > 0 ? `, including ${dependencies} required dependency file(s).` : '.')
    )
    return {
      kind: input.kind,
      instanceId: instance.id,
      instanceDir: instance.dir,
      requested: first.name,
      installed,
      skippedDependencies,
      warnings
    }
  }

  /**
   * Remembers what this install put in the instance, keyed by project.
   *
   * It lives in a dot-folder inside the instance, which is where tools keep their
   * own state: the game ignores it, and a pack exported from the instance leaves
   * it behind because nothing starting with a dot is ever carried.
   */
  private record(
    instance: GameInstance,
    kind: ResourceKind,
    items: ModrinthInstalledItem[]
  ): void {
    if (items.length === 0) return
    const at = new Date().toISOString()
    const written = new Set(items.map((item) => item.filename))
    const kept = readStoreInstalls(instance.dir).filter(
      (entry) => entry.kind !== kind || !written.has(entry.filename)
    )
    writeStoreInstalls(instance.dir, [
      ...kept,
      ...items.map((item) => ({
        projectId: item.projectId,
        title: item.title,
        versionNumber: item.versionNumber,
        filename: item.filename,
        kind,
        at
      }))
    ])
  }

  private noVersionMessage(
    input: InstallInput,
    gameVersion: string,
    loader: InstallLoader | null
  ): string {
    const forThis = `${gameVersion}${loader && loader !== 'vanilla' ? ` on ${loader}` : ''}`
    if (!input.projectId && input.versionId) return `${input.versionId} is no longer published.`
    return `Modrinth has no version of ${input.projectId} for ${forThis}.`
  }

  /**
   * The version to install: the one asked for, or the newest that matches the
   * instance's game version and loader.
   */
  private async resolveVersion(input: {
    projectId: string | null
    versionId: string | null
    gameVersion: string | null
    loader: InstallLoader | null
  }): Promise<ModrinthVersion | null> {
    if (input.versionId) {
      return parseVersion(await this.json<unknown>(versionUrl(input.versionId)))
    }
    if (!input.projectId) return null
    const versions = parseVersionList(
      await this.json<unknown>(
        projectVersionsUrl({
          projectIdOrSlug: input.projectId,
          gameVersion: input.gameVersion,
          loader: input.loader
        })
      )
    )
    return bestVersion(versions, { gameVersion: input.gameVersion, loader: input.loader })
  }

  /** Project ids turned into names, so a skipped dependency reads as a mod. */
  private async namesFor(projectIds: string[]): Promise<string[]> {
    const unique = [...new Set(projectIds)]
    if (unique.length === 0) return []
    try {
      const byId = new Map((await this.projects(unique)).map((project) => [project.projectId, project.title]))
      return unique.map((id) => byId.get(id) ?? id)
    } catch {
      return unique
    }
  }
}

/**
 * The Minecraft version an instance plays, read off its version json's id.
 *
 * Null when the instance has no version at all, or one whose id names no game
 * version: the store then browses the whole catalogue rather than filtering on
 * something that matches nothing.
 */
function gameVersionOf(instance: { versionId: string | null } | null): string | null {
  if (!instance?.versionId) return null
  return baseGameVersion(instance.versionId)
}

/** How far through a download is, by bytes when the server declared them. */
function downloadFraction(progress: DownloadProgress): number {
  if (progress.bytesTotal > 0) return Math.min(1, progress.bytesDone / progress.bytesTotal)
  if (progress.total > 0) return Math.min(1, progress.completed / progress.total)
  return 0
}

/** `12.4 MB of 30.1 MB`, or the file count when no size was declared. */
function describeDownload(progress: DownloadProgress, size?: number): string {
  const total = progress.bytesTotal > 0 ? progress.bytesTotal : (size ?? 0)
  if (total > 0) return `${formatBytes(progress.bytesDone)} of ${formatBytes(total)}`
  return `${progress.completed} of ${progress.total} file(s)`
}

function formatBytes(bytes: number): string {
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} kB`
  return `${bytes} B`
}

import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import type {
  InstallLoader,
  ModrinthInstallResult,
  ModrinthInstalledItem,
  ModrinthProject,
  ModrinthSearch,
  ModrinthVersion,
  ResourceKind
} from '@shared/types'
import { downloadAll, fetchJson, type FetchLike } from '../minecraft/download'
import {
  bestVersion,
  parseProjectList,
  parseSearchResponse,
  parseVersion,
  parseVersionList,
  primaryFile,
  projectsByIdsUrl,
  projectVersionsUrl,
  requiredDependencies,
  searchUrl,
  userAgent,
  versionUrl
} from '../minecraft/modrinth'
import { folderForKind } from '../minecraft/resources'
import type { AppEnvironment } from './env'
import type { InstanceService } from './instance-service'
import type { TaskService } from './task-service'

/** How deep a dependency chain is followed, so one project cannot fan out forever. */
const DEPENDENCY_DEPTH = 3
/** A ceiling on dependencies per install, for the same reason. */
const MAX_DEPENDENCIES = 25

export interface SearchInput {
  query: string
  kind: ResourceKind
  /** When omitted, the selected instance's version and loader are used. */
  gameVersion?: string | null
  loader?: InstallLoader | null
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

  /** The instance supplies the game version and loader the search is filtered by. */
  private target(input: { gameVersion?: string | null; loader?: InstallLoader | null }): {
    instance: ReturnType<InstanceService['selected']>
    gameVersion: string | null
    loader: InstallLoader | null
  } {
    const instance = this.instances.selected()
    return {
      instance,
      gameVersion: input.gameVersion ?? instance?.versionId ?? null,
      loader: input.loader ?? instance?.loader ?? null
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
        limit: input.limit ?? 20,
        offset: input.offset ?? 0
      })
    )
    return { ...parseSearchResponse(payload), folder: folderForKind(input.kind) }
  }

  async versions(input: {
    projectIdOrSlug: string
    gameVersion?: string | null
    loader?: InstallLoader | null
  }): Promise<ModrinthVersion[]> {
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

    const gameVersion = instance.versionId
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

      report(
        queue.length + installed.length === 0
          ? 0.5
          : installed.length / (installed.length + queue.length + 1),
        `Downloading ${item.version.name}${item.dependency ? ' (required by what you asked for)' : ''}`
      )
      const result = await downloadAll([
        {
          url: file.url,
          dest: join(folder, file.filename),
          sha1: file.sha1,
          sha512: file.sha512,
          size: file.size,
          label: file.filename
        }
      ])

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

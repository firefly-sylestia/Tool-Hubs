import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'
import type {
  ImportResult,
  InstallRequest,
  InstallResult,
  LauncherSettings,
  LoaderOption,
  RemoteVersion
} from '@shared/types'
import {
  downloadAll,
  fetchJson,
  writeTextFile,
  type DownloadProgress,
  type FetchLike
} from '../minecraft/download'
import {
  mergePlans,
  nativesDirFor,
  planAssetObjects,
  planVersionFiles,
  type AssetIndexJson,
  type PlannedFile,
  type VersionFilePlan
} from '../minecraft/install-plan'
import {
  loaderListUrl,
  loaderProfileUrl,
  parseLoaderProfile,
  parseLoaderVersions,
  type LoaderKind
} from '../minecraft/loader'
import { ruleContextFor, type RuleContext } from '../minecraft/rules'
import { versionDir, type VersionJson } from '../minecraft/version-json'
import {
  VERSION_MANIFEST_URL,
  findManifestVersion,
  parseVersionManifest,
  type VersionManifest
} from '../minecraft/version-manifest'
import { extractZip, isNativeLibraryEntry } from '../minecraft/zip'
import type { AppEnvironment } from './env'
import type { InstallationService } from './installation-service'
import type { SettingsService } from './settings-service'
import type { StateStore } from './state-store'
import type { TaskService } from './task-service'

/**
 * Installs game versions and mod loaders.
 *
 * Everything here comes from an official source: Mojang's manifest and version
 * jsons for the game and its libraries and assets, and Fabric's or Quilt's
 * metadata for a loader profile. No account is involved — these are public
 * files, and the same ones the official launcher fetches.
 *
 * A version only counts as installed once every file its version json asks for
 * is on disk with the hash that json declared. Downloads that are interrupted
 * keep their `.part` file and resume on the next run, and a jar whose hash does
 * not match is never renamed into place.
 */
export class InstallService {
  private manifest: VersionManifest | null = null

  constructor(
    private readonly state: StateStore,
    private readonly environment: AppEnvironment,
    private readonly tasks: TaskService,
    private readonly installations: InstallationService,
    private readonly settings: SettingsService,
    private readonly warn: (message: string) => void,
    private readonly options: { fetchImpl?: FetchLike; concurrency?: number } = {}
  ) {}

  private get fetchImpl(): FetchLike | undefined {
    return this.options.fetchImpl
  }

  /** Versions the launcher can offer, newest first, straight from the manifest. */
  async remoteVersions(options: { refresh?: boolean } = {}): Promise<RemoteVersion[]> {
    const manifest = await this.loadManifest(options.refresh ?? false)
    return manifest.versions.map((version) => ({
      id: version.id,
      type: version.type,
      releaseTime: version.releaseTime
    }))
  }

  async loaderVersions(input: { loader: LoaderKind }): Promise<LoaderOption[]> {
    return parseLoaderVersions(
      await fetchJson<unknown>(loaderListUrl(input.loader), { fetchImpl: this.fetchImpl })
    )
  }

  /** Runs as a task, so the UI gets byte-level progress for the whole install. */
  install(input: InstallRequest): Promise<InstallResult> {
    const label =
      input.loader === 'vanilla' ? input.versionId : `${input.versionId} with ${input.loader}`
    return this.tasks.run(`Install ${label}`, (report) => this.performInstall(input, report))
  }

  /**
   * Brings in something that was not installed here.
   *
   * A folder holding `versions/` is a Minecraft game directory, and using it in
   * place is both what "import my installation" means and the only sane option:
   * copying a game directory is several gigabytes. A folder holding
   * `<name>.json` is one version, and that is small enough to copy into the
   * current installation — which is how a version downloaded elsewhere gets in.
   */
  importFrom(path: string): ImportResult {
    if (!existsSync(path)) throw new Error(`${path} does not exist.`)

    if (existsSync(join(path, 'versions'))) {
      const versions = readdirSync(join(path, 'versions'), { withFileTypes: true }).filter((entry) =>
        entry.isDirectory()
      ).length
      this.settings.update({ gameDir: path } as Partial<LauncherSettings>)
      const installed = this.installations.rescan()
      const missing = ['libraries', 'assets']
        .filter((name) => !existsSync(join(path, name)))
        .map((name) => `${name}/`)
      return {
        kind: 'gameDirectory',
        path,
        message:
          `Now using ${path} — ${versions} version folder(s) there` +
          (missing.length > 0
            ? `, but it has no ${missing.join(' or ')} yet, so an install is still needed.`
            : '.'),
        installationCount: installed.length
      }
    }

    const name = basename(path)
    const jsonPath = join(path, `${name}.json`)
    if (existsSync(jsonPath)) {
      const dest = versionDir(this.state.snapshot().settings.gameDir, name)
      if (existsSync(dest)) {
        throw new Error(`${name} is already installed at ${dest}. Remove it first to replace it.`)
      }
      mkdirSync(dirname(dest), { recursive: true })
      cpSync(path, dest, { recursive: true })
      const installed = this.installations.rescan()
      return {
        kind: 'version',
        path: dest,
        message: `Copied ${name} into ${dest}. Its files are not checked; rescan and launch it to see what is there.`,
        installationCount: installed.length
      }
    }

    throw new Error(
      `Nothing to import in ${path}: expected a game directory (with versions/) or a version ` +
        `folder (with ${name}.json).`
    )
  }

  private async loadManifest(refresh: boolean): Promise<VersionManifest> {
    if (this.manifest && !refresh) return this.manifest
    const manifest = parseVersionManifest(
      await fetchJson<unknown>(VERSION_MANIFEST_URL, { fetchImpl: this.fetchImpl })
    )
    this.manifest = manifest
    return manifest
  }

  private async performInstall(
    input: InstallRequest,
    report: (progress: number, message: string) => void
  ): Promise<InstallResult> {
    const gameDir = this.state.snapshot().settings.gameDir
    const ctx: RuleContext = ruleContextFor(this.environment.platform, this.environment.arch, {
      is_demo_user: false,
      has_custom_resolution: false,
      has_quick_plays_support: true
    })

    report(0.02, 'Reading the version manifest')
    const manifest = await this.loadManifest(false)
    const entry = findManifestVersion(manifest, input.versionId)
    if (!entry) {
      throw new Error(`Version ${input.versionId} is not in Mojang's manifest.`)
    }

    report(0.05, `Fetching the ${input.versionId} version json`)
    const fetched = await fetchJson<VersionJson>(entry.url, { fetchImpl: this.fetchImpl })
    const baseJson: VersionJson = { ...fetched, id: fetched.id ?? input.versionId }
    const basePath = join(versionDir(gameDir, input.versionId), `${input.versionId}.json`)
    if (existsSync(basePath)) {
      this.warn(`Keeping the existing ${basePath}; only missing files will be fetched.`)
    } else {
      await writeTextFile(basePath, JSON.stringify(baseJson, null, 2))
    }

    let loaderJson: VersionJson | null = null
    let targetId = input.versionId
    if (input.loader !== 'vanilla') {
      if (!input.loaderVersion) throw new Error(`Choose a ${input.loader} loader version.`)
      report(0.08, `Fetching the ${input.loader} profile for ${input.versionId}`)
      const profile = await fetchJson<unknown>(
        loaderProfileUrl(input.loader, input.versionId, input.loaderVersion),
        { fetchImpl: this.fetchImpl }
      )
      loaderJson = parseLoaderProfile(profile, {
        kind: input.loader,
        gameVersion: input.versionId,
        loaderVersion: input.loaderVersion
      })
      targetId = loaderJson.id
      // Loader profiles are generated rather than authored, so rewriting one
      // cannot lose anything a user edited, and the id on disk stays the id the
      // launch plan will resolve.
      await writeTextFile(
        join(versionDir(gameDir, targetId), `${targetId}.json`),
        JSON.stringify(loaderJson, null, 2)
      )
    }

    report(0.1, 'Working out which files are needed')
    const plans: VersionFilePlan[] = []
    if (loaderJson) plans.push(planVersionFiles(gameDir, loaderJson, ctx))
    plans.push(planVersionFiles(gameDir, baseJson, ctx))
    const versionPlan = mergePlans(plans)

    const files: PlannedFile[] = [...versionPlan.files]
    const indexId = baseJson.assetIndex?.id ?? baseJson.assets ?? null
    if (baseJson.assetIndex?.url && indexId) {
      report(0.12, 'Fetching the asset index')
      const index = await fetchJson<AssetIndexJson>(baseJson.assetIndex.url, {
        fetchImpl: this.fetchImpl
      })
      files.push({
        url: baseJson.assetIndex.url,
        dest: join(gameDir, 'assets', 'indexes', `${indexId}.json`),
        sha1: baseJson.assetIndex.sha1,
        size: baseJson.assetIndex.size,
        label: `asset index ${indexId}`
      })
      files.push(...planAssetObjects(gameDir, index))
    } else {
      this.warn(`${input.versionId} declares no asset index; the game may not render correctly.`)
    }

    for (const jar of versionPlan.nativeJars) {
      files.push({ url: jar.url, dest: jar.path, sha1: jar.sha1, size: jar.size, label: jar.label })
    }

    const alreadyThere = files.filter((file) => existsSync(file.dest)).length
    report(0.15, `${files.length} files needed, ${alreadyThere} already on disk`)

    const result = await downloadAll(files, {
      fetchImpl: this.fetchImpl,
      concurrency: this.options.concurrency,
      onProgress: (progress: DownloadProgress) => {
        const fraction =
          progress.bytesTotal > 0
            ? progress.bytesDone / progress.bytesTotal
            : progress.total > 0
              ? progress.completed / progress.total
              : 0
        report(
          0.15 + 0.75 * Math.min(1, fraction),
          `${progress.completed}/${progress.total} files · ${formatBytes(progress.bytesDone)}` +
            (progress.bytesTotal > 0 ? ` of ${formatBytes(progress.bytesTotal)}` : '')
        )
      }
    })

    report(0.92, 'Unpacking native libraries')
    const nativesDir = nativesDirFor(gameDir, targetId)
    let nativesExtracted = 0
    for (const jar of versionPlan.nativeJars) {
      if (!existsSync(jar.path)) continue
      nativesExtracted += extractZip(readFileSync(jar.path), nativesDir, {
        exclude: jar.exclude,
        filter: (name) => isNativeLibraryEntry(name, ctx.os)
      })
    }

    report(0.97, 'Rescanning installations')
    this.installations.rescan()
    this.warn(
      `Installed ${targetId}: ${result.downloaded} files downloaded, ${result.reused} already present, ` +
        `${nativesExtracted} native libraries unpacked.`
    )
    report(1, 'Done')

    return {
      versionId: input.versionId,
      targetId,
      files: files.length,
      reused: result.reused,
      bytes: result.bytes,
      nativesExtracted
    }
  }
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 10 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

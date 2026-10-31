import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync
} from 'node:fs'
import { basename, isAbsolute, join, resolve, sep } from 'node:path'
import { randomUUID } from 'node:crypto'
import type {
  GameInstance,
  InstallLoader,
  InstanceGameOptions,
  InstanceImportResult,
  InstanceResources,
  ResourceEntry,
  ResourceKind,
  ResourceMetadata
} from '@shared/types'
import {
  OPTIONS_FILE,
  QUICK_OPTIONS,
  parseOptions,
  patchOptions,
  quickOptionValues
} from '../minecraft/options'
import { modWarnings, readModMetadataFromFile, type ModMetadata } from '../minecraft/mod-metadata'
import { matchesVersionRange } from '../minecraft/modrinth'
import {
  RESOURCE_KINDS,
  explainUnsupportedAddition,
  folderForKind,
  isResourceFile,
  resourceFileName,
  splitResourceName
} from '../minecraft/resources'
import type { AppEnvironment } from './env'
import type { InstallationService } from './installation-service'
import { assertMemory, type SettingsService } from './settings-service'
import type { StateStore } from './state-store'

export interface CreateInstanceInput {
  name: string
  versionId?: string | null
  loader?: InstallLoader | null
  loaderVersion?: string | null
  source?: GameInstance['source']
  dir?: string
}

const MAX_NAME = 48

function assertName(value: unknown): string {
  const name = String(value ?? '').trim()
  if (name.length < 1 || name.length > MAX_NAME) {
    throw new Error(`An instance name must be 1-${MAX_NAME} characters.`)
  }
  if (/[\\/:*?"<>|]/.test(name)) {
    throw new Error('An instance name cannot contain \\ / : * ? " < > or |.')
  }
  return name
}

/** A folder name that cannot collide with anything and is readable in a file manager. */
export function slugFor(name: string, taken: string[]): string {
  const base = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
  const stem = base === '' ? 'instance' : base
  if (!taken.includes(stem)) return stem
  for (let index = 2; index < 1000; index += 1) {
    const candidate = `${stem}-${index}`
    if (!taken.includes(candidate)) return candidate
  }
  throw new Error(`Could not find a free folder name for ${name}.`)
}

function isInside(child: string, parent: string): boolean {
  const a = resolve(child)
  const b = resolve(parent)
  return a === b || a.startsWith(b.endsWith(sep) ? b : b + sep)
}

/**
 * Instances and the resources inside them.
 *
 * An instance is one game directory: its own `versions/`, `libraries/`,
 * `assets/`, and its own `mods/`, `resourcepacks/` and `shaderpacks/`. That is
 * what makes "this mod only in this pack" possible at all — a single shared
 * game directory cannot express it, which is why the app moved from one global
 * directory to one directory per instance.
 */
export class InstanceService {
  /** Jar metadata by path, keyed by size and mtime so an edited mod is re-read. */
  private readonly metadataCache = new Map<
    string,
    { size: number; modifiedAt: string; metadata: ModMetadata | null }
  >()

  constructor(
    private readonly state: StateStore,
    private readonly env: AppEnvironment,
    private readonly installations: InstallationService,
    private readonly settings: SettingsService,
    private readonly warn: (message: string) => void
  ) {}

  list(): GameInstance[] {
    return this.state.snapshot().instances
  }

  selected(): GameInstance | null {
    const snapshot = this.state.snapshot()
    return snapshot.instances.find((instance) => instance.id === snapshot.selectedInstanceId) ?? null
  }

  /** The instance a call applies to when the renderer does not name one. */
  require(instanceId?: string | null): GameInstance {
    if (instanceId) {
      const found = this.state.snapshot().instances.find((instance) => instance.id === instanceId)
      if (!found) throw new Error(`Unknown instance ${instanceId}.`)
      return found
    }
    const selected = this.selected()
    if (!selected) throw new Error('No instance is selected. Create or import one first.')
    return selected
  }

  create(input: CreateInstanceInput): GameInstance {
    const name = assertName(input.name)
    const snapshot = this.state.snapshot()
    if (snapshot.instances.some((instance) => instance.name.toLowerCase() === name.toLowerCase())) {
      throw new Error(`An instance named "${name}" already exists.`)
    }

    const dir = input.dir
      ? resolve(input.dir)
      : join(this.settings.get().instancesDir, slugFor(name, snapshot.instances.map((instance) => basename(instance.dir))))

    const instance: GameInstance = {
      id: randomUUID(),
      name,
      dir,
      source: input.source ?? (input.dir ? 'imported' : 'created'),
      versionId: input.versionId ?? null,
      loader: input.loader ?? null,
      loaderVersion: input.loaderVersion ?? null,
      createdAt: new Date().toISOString(),
      lastPlayedAt: null,
      jvmArgs: [],
      // Both are inherited from the launcher's settings until this instance is
      // given its own, which is the only sensible answer for a new instance.
      javaPath: null,
      maxMemoryMb: null
    }

    mkdirSync(instance.dir, { recursive: true })
    this.ensureFolders(instance)
    this.state.update((state) => {
      state.instances.push(instance)
      state.selectedInstanceId = instance.id
    })
    this.installations.rescan()
    this.warn(`Created instance ${instance.name} at ${instance.dir}.`)
    return instance
  }

  update(
    id: string,
    patch: Partial<
      Pick<
        GameInstance,
        'name' | 'versionId' | 'loader' | 'loaderVersion' | 'jvmArgs' | 'javaPath' | 'maxMemoryMb'
      >
    >
  ): GameInstance {
    const name = patch.name === undefined ? undefined : assertName(patch.name)
    // Checked before the update runs, so a bad value leaves the state alone.
    const javaPath = patch.javaPath === undefined ? undefined : assertJavaPath(patch.javaPath)
    const maxMemoryMb =
      patch.maxMemoryMb === undefined
        ? undefined
        : patch.maxMemoryMb === null
          ? null
          : assertMemory(patch.maxMemoryMb)
    const updated = this.state.update((state) => {
      const instance = state.instances.find((candidate) => candidate.id === id)
      if (!instance) throw new Error(`Unknown instance ${id}.`)
      if (name !== undefined) {
        const clash = state.instances.some(
          (candidate) => candidate.id !== id && candidate.name.toLowerCase() === name.toLowerCase()
        )
        if (clash) throw new Error(`An instance named "${name}" already exists.`)
        instance.name = name
      }
      if (patch.versionId !== undefined) instance.versionId = patch.versionId
      if (patch.loader !== undefined) instance.loader = patch.loader
      if (patch.loaderVersion !== undefined) instance.loaderVersion = patch.loaderVersion
      if (patch.jvmArgs !== undefined) instance.jvmArgs = patch.jvmArgs.filter((arg) => arg.trim() !== '')
      if (javaPath !== undefined) instance.javaPath = javaPath
      if (maxMemoryMb !== undefined) instance.maxMemoryMb = maxMemoryMb
    })
    return updated.instances.find((instance) => instance.id === id)!
  }

  select(id: string): GameInstance {
    const instance = this.require(id)
    this.state.update((state) => {
      state.selectedInstanceId = instance.id
    })
    this.installations.rescan()
    return instance
  }

  /**
   * Nothing selected, which is a real state rather than an error.
   *
   * The store browses the whole catalogue that way, and so does anything else
   * that is not about one instance. The instances themselves are untouched.
   */
  clearSelection(): { selectedInstanceId: null } {
    this.state.update((state) => {
      state.selectedInstanceId = null
    })
    return { selectedInstanceId: null }
  }

  /**
   * Forgets an instance. Its files are left alone unless `deleteFiles` is set,
   * and that is only allowed for an instance this launcher created inside its
   * own instances directory — deleting a directory the user pointed us at from
   * somewhere else is not ours to do.
   */
  remove(id: string, options: { deleteFiles?: boolean } = {}): { removed: string; deleted: boolean; dir: string } {
    const instance = this.require(id)
    const managed = isInside(instance.dir, this.settings.get().instancesDir)
    if (options.deleteFiles && !managed) {
      throw new Error(
        `${instance.dir} is outside the launcher's instances folder, so its files were not deleted. ` +
          `Remove it from disk yourself if that is what you want.`
      )
    }

    this.state.update((state) => {
      state.instances = state.instances.filter((candidate) => candidate.id !== id)
      if (state.selectedInstanceId === id) {
        state.selectedInstanceId = state.instances[0]?.id ?? null
      }
    })
    if (options.deleteFiles) rmSync(instance.dir, { recursive: true, force: true })
    this.installations.rescan()
    this.warn(`Removed instance ${instance.name}${options.deleteFiles ? ' and its files' : ' (files kept)'}.`)
    return { removed: instance.name, deleted: Boolean(options.deleteFiles), dir: instance.dir }
  }

  /** Creates the three resource folders, so a user can drop files in themselves. */
  ensureFolders(instance: GameInstance): void {
    for (const kind of RESOURCE_KINDS) {
      mkdirSync(join(instance.dir, folderForKind(kind)), { recursive: true })
    }
  }

  resources(instanceId?: string | null): InstanceResources {
    const instance = this.require(instanceId)
    this.ensureFolders(instance)

    const entries = {} as Record<ResourceKind, ResourceEntry[]>
    const folders = {} as Record<ResourceKind, string>
    for (const kind of RESOURCE_KINDS) {
      const folder = join(instance.dir, folderForKind(kind))
      folders[kind] = folder
      entries[kind] = readResourceFolder(folder, kind)
    }
    this.describeMods(instance, entries.mod)

    return { instanceId: instance.id, instanceDir: instance.dir, folders, entries }
  }

  /** Turning a resource off is a rename to `<name>.disabled`, and back again. */
  setResourceEnabled(input: {
    instanceId?: string | null
    kind: ResourceKind
    name: string
    enabled: boolean
  }): InstanceResources {
    const instance = this.require(input.instanceId)
    const folder = join(instance.dir, folderForKind(input.kind))
    const from = join(folder, input.name)
    if (!existsSync(from)) throw new Error(`${input.name} is no longer in ${folder}.`)

    const { title } = splitResourceName(input.name)
    const to = join(folder, resourceFileName(title, input.enabled))
    if (from !== to) {
      if (existsSync(to)) {
        throw new Error(
          `${basename(to)} already exists, so ${input.name} cannot be renamed to it. ` +
            `Remove the other copy first.`
        )
      }
      renameSync(from, to)
    }
    return this.resources(instance.id)
  }

  /**
   * Fills in what each mod's jar says about itself, and what is wrong with it.
   *
   * Reading a jar costs a file read, so the answer is cached by size and
   * modification time: a folder paint must not re-read every mod, and editing a
   * mod in place has to be noticed. A jar that cannot be read is left with no
   * metadata rather than being called broken.
   */
  private describeMods(instance: GameInstance, entries: ResourceEntry[]): void {
    if (entries.length === 0) return

    const metadata = new Map<string, ModMetadata | null>()
    for (const entry of entries) {
      const cached = this.metadataCache.get(entry.path)
      if (cached && cached.size === entry.size && cached.modifiedAt === entry.modifiedAt) {
        metadata.set(entry.path, cached.metadata)
        continue
      }
      const read = readModMetadataFromFile(entry.path)
      this.metadataCache.set(entry.path, {
        size: entry.size,
        modifiedAt: entry.modifiedAt,
        metadata: read
      })
      metadata.set(entry.path, read)
    }

    const byId = new Map<string, string[]>()
    for (const entry of entries) {
      const id = metadata.get(entry.path)?.id
      if (!id) continue
      byId.set(id, [...(byId.get(id) ?? []), entry.name])
    }

    for (const entry of entries) {
      const read = metadata.get(entry.path)
      if (!read) {
        entry.metadata = null
        continue
      }
      const warnings = modWarnings(read, {
        versionId: instance.versionId,
        loader: instance.loader,
        rangeMatcher: matchesVersionRange
      })
      const copies = byId.get(read.id ?? '') ?? []
      if (copies.length > 1) {
        const others = copies.filter((name) => name !== entry.name)
        warnings.push(
          `Another copy of this mod is in the folder (${others.join(', ')}); the game loads only one.`
        )
      }
      entry.metadata = {
        loader: read.loader,
        id: read.id,
        name: read.name,
        version: read.version,
        requires: read.requires,
        authors: read.authors,
        legacy: read.legacy,
        warnings
      } satisfies ResourceMetadata
    }
  }

  /**
   * The instance's own game options, from its `options.txt`.
   *
   * Every instance has a game directory, so every instance has its own
   * options.txt — the game writes it on exit. This reads it as it is; a key the
   * launcher has never heard of is listed too, because it is the game's file.
   */
  gameOptions(instanceId?: string | null): InstanceGameOptions {
    const instance = this.require(instanceId)
    const path = join(instance.dir, OPTIONS_FILE)
    const exists = existsSync(path)
    const contents = exists ? readFileSync(path, 'utf8') : ''
    return {
      instanceId: instance.id,
      instanceDir: instance.dir,
      path,
      exists,
      entries: parseOptions(contents).entries,
      fields: QUICK_OPTIONS,
      quick: quickOptionValues(contents),
      jvmArgs: instance.jvmArgs
    }
  }

  /**
   * Sets or removes options, and this instance's JVM arguments.
   *
   * The file is patched rather than rewritten, so keys this launcher does not
   * know about survive — which is the only way a settings file that the game
   * itself rewrites can be edited safely from outside.
   */
  setGameOptions(input: {
    instanceId?: string | null
    /** Values to set; null removes the key and lets the game use its default. */
    changes?: Record<string, string | null>
    jvmArgs?: string[]
  }): InstanceGameOptions {
    const instance = this.require(input.instanceId)
    const path = join(instance.dir, OPTIONS_FILE)
    const changes = input.changes ?? {}

    if (Object.keys(changes).length > 0) {
      const existing = existsSync(path) ? readFileSync(path, 'utf8') : ''
      mkdirSync(instance.dir, { recursive: true })
      writeFileSync(path, patchOptions(existing, changes), 'utf8')
    }
    if (input.jvmArgs !== undefined) {
      this.update(instance.id, { jvmArgs: input.jvmArgs })
    }
    return this.gameOptions(instance.id)
  }

  removeResource(input: { instanceId?: string | null; kind: ResourceKind; name: string }): InstanceResources {
    const instance = this.require(input.instanceId)
    const folder = join(instance.dir, folderForKind(input.kind))
    const target = resolve(join(folder, input.name))
    if (!isInside(target, folder)) throw new Error(`${input.name} is outside ${folder}.`)
    rmSync(target, { recursive: true, force: true })
    this.warn(`Removed ${input.name} from ${folder}.`)
    return this.resources(instance.id)
  }

  /**
   * Copies files into a resource folder.
   *
   * Files that the game cannot load are refused with the reason rather than
   * copied and then ignored: a `.jar` in `resourcepacks/` is a mistake someone
   * will otherwise hunt for hours.
   */
  addResources(input: {
    instanceId?: string | null
    kind: ResourceKind
    paths: string[]
  }): { added: string[]; skipped: string[]; resources: InstanceResources } {
    const instance = this.require(input.instanceId)
    const folder = join(instance.dir, folderForKind(input.kind))
    mkdirSync(folder, { recursive: true })

    const added: string[] = []
    const skipped: string[] = []
    for (const path of input.paths) {
      const name = basename(path)
      if (!existsSync(path) || !statSync(path).isFile()) {
        skipped.push(`${name} — not a file`)
        continue
      }
      const problem = explainUnsupportedAddition(name, input.kind)
      if (problem) {
        skipped.push(problem)
        continue
      }
      if (!isResourceFile(name, input.kind)) {
        skipped.push(`${name} — not a file the game loads from ${folderForKind(input.kind)}`)
        continue
      }
      const dest = join(folder, name)
      if (resolve(dest) !== resolve(path)) cpSync(path, dest)
      added.push(name)
    }
    if (added.length > 0) this.warn(`Added ${added.length} file(s) to ${folder}.`)
    return { added, skipped, resources: this.resources(instance.id) }
  }

  /**
   * Turns a folder into an instance.
   *
   * A game directory — one with `versions/` — becomes an instance that keeps
   * its own path, because copying a game directory is several gigabytes; its
   * files are then reused from there rather than downloaded again. A version
   * folder is small, so it is copied into the launcher's game folder, where
   * every instance can play it.
   */
  importFrom(path: string): InstanceImportResult {
    const source = resolve(path)
    if (!existsSync(source)) throw new Error(`${source} does not exist.`)

    if (existsSync(join(source, 'versions'))) {
      const versionsDir = join(source, 'versions')
      const versions = readdirSync(versionsDir, { withFileTypes: true }).filter((entry) => entry.isDirectory())
      const instance = this.create({
        name: basename(source) || 'Imported installation',
        dir: source,
        source: 'imported',
        versionId: pickVersionId(source, versions.map((entry) => entry.name))
      })
      const missing = ['libraries', 'assets'].filter((name) => !existsSync(join(source, name)))
      return {
        instance,
        versionCount: versions.length,
        message:
          `Using ${source} in place — ${versions.length} version folder(s) there, which the launcher ` +
          `reuses when a version is installed or played rather than downloading again` +
          (missing.length > 0 ? `, but it has no ${missing.join(' or ')} yet.` : '.')
      }
    }

    const name = basename(source)
    if (existsSync(join(source, `${name}.json`))) {
      let instance = this.selected()
      if (!instance) {
        instance = this.create({ name, source: 'created' })
      }
      // A version folder is the game itself, so it goes where every instance
      // can play it rather than into this one instance's folder.
      const dest = join(this.env.sharedDir, 'versions', name)
      if (existsSync(dest)) {
        throw new Error(`${name} is already installed. Remove it from ${this.env.sharedDir} first to replace it.`)
      }
      mkdirSync(join(this.env.sharedDir, 'versions'), { recursive: true })
      cpSync(source, dest, { recursive: true })
      const updated = this.update(instance.id, {
        versionId: instance.versionId ?? name,
        loader: instance.loader ?? null
      })
      this.installations.rescan()
      return {
        instance: updated,
        versionCount: 1,
        message: `Copied ${name} into the launcher's game folder; every instance can play it. Its files are not checked; install it to have them verified.`
      }
    }

    throw new Error(
      `Nothing to import in ${source}: expected a game directory (with versions/) or a version ` +
        `folder (with ${name}.json).`
    )
  }
}

function readResourceFolder(folder: string, kind: ResourceKind): ResourceEntry[] {
  if (!existsSync(folder)) return []
  const entries: ResourceEntry[] = []
  for (const entry of readdirSync(folder, { withFileTypes: true })) {
    if (!entry.isFile()) continue
    if (!isResourceFile(entry.name, kind)) continue
    const full = join(folder, entry.name)
    const stat = statSync(full)
    const { title, enabled } = splitResourceName(entry.name)
    entries.push({
      name: entry.name,
      title,
      kind,
      enabled,
      size: stat.size,
      path: full,
      modifiedAt: stat.mtime.toISOString(),
      // Filled in per instance for mods; a pack or shader has nothing to read.
      metadata: null
    })
  }
  return entries.sort((a, b) => {
    if (a.enabled !== b.enabled) return a.enabled ? -1 : 1
    return a.title.localeCompare(b.title)
  })
}

/**
 * A Java binary this instance runs, which has to be a path rather than a name.
 *
 * An instance that names a runtime is naming a file; `java` on PATH is what
 * leaving it empty means, and that is resolved when the game is launched.
 */
function assertJavaPath(value: unknown): string | null {
  const path = String(value ?? '').trim()
  if (path === '') return null
  if (!isAbsolute(path)) {
    throw new Error('A Java path must be an absolute path, or empty to find one for the version.')
  }
  return path
}

/** The version an imported directory should start on: its newest release, else anything. */
function pickVersionId(dir: string, ids: string[]): string | null {
  if (ids.length === 0) return null
  const preferred = ids.find((id) => /^\d+\.\d+(\.\d+)?$/.test(id))
  if (preferred) return preferred
  if (existsSync(join(dir, 'versions', ids[0], `${ids[0]}.json`))) return ids[0]
  return ids[0]
}

export function isManagedBy(instanceDir: string, instancesDir: string): boolean {
  return isAbsolute(instanceDir) && isInside(instanceDir, instancesDir)
}

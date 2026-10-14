/**
 * Shared shapes for the launcher state. The main process owns the persisted
 * copy; the renderer keeps a working snapshot of the same structure.
 *
 * The unit of play is an *instance*: one game directory with its own version,
 * loader, mods, resource packs and shader packs. A profile is only an identity
 * (the name the game reports), so several profiles can play the same instance
 * and one profile can play any instance.
 */

export interface Profile {
  id: string
  /** Local profile name, also the in-game name. No account is attached. */
  name: string
  /** Offline UUID derived from the name (RFC 4122 version 3, MD5). */
  uuid: string
  createdAt: string
  lastPlayedAt: string | null
}

/** 'vanilla' means no loader is installed on the instance. */
export type InstallLoader = 'vanilla' | 'fabric' | 'quilt'

export interface GameInstance {
  id: string
  name: string
  /** Absolute path holding versions/, libraries/, assets/ and the resource folders. */
  dir: string
  /** How it got here, which is what the UI says about it. */
  source: 'created' | 'imported' | 'modpack'
  /** Installed version the instance plays, or null before one is installed. */
  versionId: string | null
  /** The version the instance was installed with, for display; 'vanilla' for none. */
  loader: InstallLoader | null
  loaderVersion: string | null
  createdAt: string
  lastPlayedAt: string | null
  /** Extra JVM arguments for this instance, on top of the version json's and the global settings'. */
  jvmArgs: string[]
}

/** One `key:value` line of the game's own options.txt. */
export interface GameOptionEntry {
  key: string
  value: string
}

/** An option the launcher offers directly, with what it expects. */
export interface GameOptionField {
  key: string
  label: string
  /** Fixed values, shown as a select. */
  choices?: string[]
  numeric?: boolean
}

/** An instance's game options, as read from its own options.txt. */
export interface InstanceGameOptions {
  instanceId: string
  instanceDir: string
  path: string
  /** True when the game has written the file at least once. */
  exists: boolean
  entries: GameOptionEntry[]
  /** The options the UI offers directly, and their current values. */
  fields: GameOptionField[]
  quick: Record<string, string>
  /** Extra JVM arguments for this instance. */
  jvmArgs: string[]
}

/** The three folders a launcher manages as resources. Saves belong to the game. */
export type ResourceKind = 'mod' | 'resourcepack' | 'shaderpack'

/**
 * What a mod's jar says about itself, read from the jar rather than from its
 * file name, plus anything worth saying before it is launched.
 */
export interface ResourceMetadata {
  loader: 'fabric' | 'quilt' | 'forge' | 'neoforge'
  id: string | null
  name: string | null
  version: string | null
  /** The game version or range the file asks for, exactly as written. */
  requires: string | null
  authors: string[]
  /** True for the legacy `mcmod.info` that old Forge mods carry. */
  legacy: boolean
  /** Wrong loader, wrong game version, a duplicate of another file, or nothing. */
  warnings: string[]
}

export interface ResourceEntry {
  /** The file name on disk, including a `.disabled` suffix when it is off. */
  name: string
  /** The file name without the suffix the launcher adds. */
  title: string
  kind: ResourceKind
  enabled: boolean
  size: number
  path: string
  modifiedAt: string
  /** Null for a file that carries no metadata this reader understands. */
  metadata: ResourceMetadata | null
}

export interface InstanceResources {
  instanceId: string
  instanceDir: string
  /** Absolute folder per kind, whether or not it exists yet. */
  folders: Record<ResourceKind, string>
  entries: Record<ResourceKind, ResourceEntry[]>
}

/** A game version offered by Mojang's manifest, as the install picker shows it. */
export interface RemoteVersion {
  id: string
  type: string
  releaseTime: string
}

export interface LoaderOption {
  version: string
  stable: boolean
}

export interface InstallRequest {
  versionId: string
  loader: InstallLoader
  /** Required for fabric and quilt, ignored for vanilla. */
  loaderVersion?: string | null
  /** Which instance to install into; the selected one when omitted. */
  instanceId?: string | null
}

export interface InstallResult {
  /** The game version that was asked for. */
  versionId: string
  /** The version that was actually installed: the loader profile id when one was added. */
  targetId: string
  instanceId: string
  instanceDir: string
  files: number
  reused: number
  bytes: number
  nativesExtracted: number
}

export interface InstanceImportResult {
  instance: GameInstance
  versionCount: number
  message: string
}

/** What a modpack archive turned out to be, before anything is downloaded. */
export interface ModpackSummary {
  format: 'modrinth' | 'curseforge'
  name: string
  version: string | null
  gameVersion: string | null
  loader: InstallLoader | null
  loaderVersion: string | null
  /** Files the launcher can fetch itself. */
  downloadable: number
  /** Files included in the archive, under overrides/ or at the top level. */
  bundled: number
  /** Files that need a source the launcher does not use, such as CurseForge's API. */
  unresolved: string[]
}

export interface ModpackImportResult {
  summary: ModpackSummary
  instance: GameInstance
  files: number
  reused: number
  bytes: number
  overrides: number
  unresolved: string[]
}

export interface ServerEntry {
  id: string
  name: string
  host: string
  port: number
}

/**
 * Modrinth's API, as the renderer reads it. These are shared rather than owned
 * by the main process so the UI shows exactly what the API returned instead of
 * a second, drifting copy of the same fields.
 */
export interface ModrinthFile {
  url: string
  filename: string
  primary: boolean
  size: number
  sha1?: string
  sha512?: string
}

export type ModrinthDependencyType = 'required' | 'optional' | 'incompatible' | 'embedded'

export interface ModrinthDependency {
  projectId: string | null
  versionId: string | null
  dependencyType: ModrinthDependencyType
}

export interface ModrinthVersion {
  id: string
  projectId: string
  name: string
  versionNumber: string
  /** release | beta | alpha */
  versionType: string
  gameVersions: string[]
  loaders: string[]
  files: ModrinthFile[]
  dependencies: ModrinthDependency[]
  datePublished: string
}

export interface ModrinthProject {
  projectId: string
  slug: string
  title: string
  projectType: string
  iconUrl: string | null
}

export interface ModrinthHit {
  projectId: string
  slug: string
  title: string
  description: string
  /** mod | modpack | resourcepack | shader | plugin | datapack */
  projectType: string
  downloads: number
  follows: number
  iconUrl: string | null
  gameVersions: string[]
  loaders: string[]
  categories: string[]
  latestVersion: string | null
}

export interface ModrinthSearch {
  hits: ModrinthHit[]
  totalHits: number
  offset: number
  limit: number
}

/** One file the launcher installed for a project, and what it decided along the way. */
export interface ModrinthInstalledItem {
  projectId: string
  title: string
  versionNumber: string
  versionType: string
  /** File name inside the instance, as written. */
  filename: string
  /** True when the project was pulled in as a dependency of the one asked for. */
  dependency: boolean
  bytes: number
  reused: boolean
}

export interface ModrinthInstallResult {
  kind: ResourceKind
  instanceId: string
  instanceDir: string
  /** The item asked for, always first in `installed`. */
  requested: string
  installed: ModrinthInstalledItem[]
  /** Dependencies that could not be resolved for this instance's version or loader. */
  skippedDependencies: string[]
  warnings: string[]
}

export type TaskState = 'running' | 'succeeded' | 'failed'

export interface TaskRecord {
  id: string
  name: string
  state: TaskState
  progress: number
  message: string
  startedAt: string
  finishedAt: string | null
}

export interface LauncherSettings {
  /** Where newly created instances live. An imported instance keeps its own path. */
  instancesDir: string
  /** Explicit java binary, or null to resolve `java` from PATH. */
  javaPath: string | null
  maxMemoryMb: number
  width: number
  height: number
}

export interface LauncherState {
  profiles: Profile[]
  selectedProfileId: string | null
  instances: GameInstance[]
  selectedInstanceId: string | null
  servers: ServerEntry[]
  /** Server the next launch should join, or null. */
  pendingServerId: string | null
  settings: LauncherSettings
  tasks: TaskRecord[]
  /** Versions found under the selected instance. */
  installations: InstalledVersion[]
  runningProfileId: string | null
}

export interface InstalledVersion {
  id: string
  jsonPath: string
  type: string | null
  mainClass: string | null
  assetsIndex: string | null
  javaMajorVersion: number | null
  inheritsFrom: string | null
}

export interface LaunchPlan {
  profileId: string
  /** Null when a plan was built without naming an instance. */
  instanceId: string | null
  versionId: string
  javaPath: string
  args: string[]
  cwd: string
  connect: { host: string; port: number } | null
}

export interface LogLine {
  stream: 'stdout' | 'stderr'
  line: string
  at: string
}

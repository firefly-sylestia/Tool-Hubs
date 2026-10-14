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
  /**
   * The Java binary this instance runs, or null to let Java be resolved.
   *
   * Per instance rather than one setting for everything is what the game
   * actually needs: 1.12.2 wants Java 8 and 1.21 wants 21, and one machine
   * often has both.
   */
  javaPath: string | null
  /** This instance's own `-Xmx`, or null to use the launcher's default. */
  maxMemoryMb: number | null
}

/**
 * What the store's connection check found.
 *
 * `error` carries what the request itself said, which is the difference between
 * a rate limit, a refused connection and a proxy that will not pass it.
 */
export interface ModrinthStatus {
  reachable: boolean
  elapsedMs: number
  /** Total projects Modrinth reported, which is also proof it really answered. */
  totalHits: number
  error: string | null
}

/** A Java runtime the launcher can see, machine-wide or inside an instance. */
export interface JavaRuntimeInfo {
  path: string
  major: number | null
  source: string
}

/** An installed runtime inside a game directory, by the component it came from. */
export interface InstalledJavaRuntime {
  component: string
  path: string
}

/**
 * What the Java section answers: what the selected instance needs, what is on
 * the machine, and what Mojang's own runtime has already been unpacked there.
 */
export interface JavaStatus {
  instanceId: string | null
  /** The instance's game directory, which is where a runtime is installed. */
  dir: string | null
  /** The component the version json names, e.g. `java-runtime-epsilon`. */
  component: string | null
  requiredMajor: number | null
  installed: InstalledJavaRuntime[]
  detected: JavaRuntimeInfo[]
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

export type FindingSeverity = 'error' | 'warning' | 'info'

/** One thing that looks wrong in a crash report or a log, with what to do about it. */
export interface LogFinding {
  id: string
  severity: FindingSeverity
  title: string
  /** The line of the file it was read from, so a wrong reading can be seen. */
  evidence: string
  hints: string[]
}

/** One `crash-reports/crash-*.txt`, read. */
export interface CrashFile {
  name: string
  path: string
  bytes: number
  at: string
  /** The report's own `Description:` line. */
  description: string | null
  /** The root cause of the `Caused by:` chain. */
  exception: string | null
  /** The frame that threw it. */
  at_line: string | null
  minecraftVersion: string | null
  javaVersion: string | null
  /** How many mods the report listed, when it listed any. */
  mods: number
  findings: LogFinding[]
  /** The one the view opened. */
  selected: boolean
}

/** Everything the game left behind in an instance when it went wrong. */
export interface InstanceDiagnostics {
  instanceId: string
  instanceDir: string
  crashDir: string
  logPath: string
  crashes: CrashFile[]
  latestLog: {
    path: string
    exists: boolean
    bytes: number
    /** Lines in the file, of which only the tail was read. */
    lines: number
    findings: LogFinding[]
  }
  /** What the view should show: the selected crash, or the log. */
  findings: LogFinding[]
  /** Which of the two the findings came from. */
  source: string | null
}

/**
 * A world in an instance's `saves/` folder, described by the world's own
 * `level.dat` rather than by its folder name wherever that file says more.
 */
export interface WorldInfo {
  /** The directory name under `saves/`, which is what the game lists. */
  folder: string
  /** `LevelName`, or the folder when the file does not name one. */
  name: string
  /** The version that last wrote this world, when `level.dat` says. */
  version: string | null
  lastPlayed: string | null
  gameMode: string | null
  hardcore: boolean
  bytes: number
  /** Why the world could not be read, so it is listed with a reason rather than hidden. */
  problem: string | null
}

/** A zip of an instance's `saves/` folder, kept next to it under `backups/`. */
export interface SaveBackup {
  name: string
  path: string
  bytes: number
  at: string
  /** A backup taken by hand, or one taken automatically before the game changed. */
  kind: 'manual' | 'before-change'
}

export interface InstanceSaves {
  instanceId: string
  instanceDir: string
  /** `<instance>/saves`, whether or not it exists yet. */
  dir: string
  backupsDir: string
  worlds: WorldInfo[]
  backups: SaveBackup[]
}

export interface WorldExportResult {
  path: string
  files: number
  bytes: number
}

export interface WorldImportResult {
  /** The folder under `saves/` the world landed in. */
  folder: string
  files: number
  bytes: number
  source: string
}

/** One world put back out of a backup. */
export interface RestoredWorld {
  /** The folder name the backup held. */
  name: string
  /** Where it landed, which differs when that name was already taken. */
  folder: string
  files: number
  bytes: number
}

export interface WorldRestoreResult {
  instanceId: string
  /** The backup that was read. */
  path: string
  worlds: RestoredWorld[]
  /** Entries in the archive that are not part of any world. */
  skipped: number
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

/** An instance written out as a `.mrpack` someone else can import. */
export interface PackExportResult {
  path: string
  name: string
  /** The pack's own version, which is not the game's. */
  versionId: string
  instanceId: string
  /** The game version the index names, for the importer to install first. */
  gameVersion: string | null
  loader: InstallLoader | null
  loaderVersion: string | null
  files: number
  bytes: number
}

export interface ServerPlayerSample {
  name: string
  id: string | null
}

/** What a server list ping answered, or why it could not. */
export interface ServerStatus {
  online: boolean
  host: string
  port: number
  /** Milliseconds between the status request and the answer. */
  latencyMs: number | null
  version: string | null
  protocol: number | null
  players: { online: number; max: number; sample: ServerPlayerSample[] } | null
  /** The description, with its colour codes stripped. */
  motd: string | null
  /** The server's icon as the `data:image/png;base64,…` string it publishes. */
  icon: string | null
  /** Why it is not online, when it is not. */
  error: string | null
}

/** A server in the game's own `servers.dat`. */
export interface GameServer {
  name: string
  host: string
  port: number
  /** The `ip` field exactly as the file spells it, port included when it is not default. */
  address: string
  /** Players the game recorded for it, when a status ping was written in. */
  players: string[]
}

/** The game's own server list for an instance, and what a sync did to it. */
export interface ServersDatResult {
  instanceId: string
  path: string
  /** Launcher servers the game's list did not have. */
  added: number
  /** Servers the game's list already had and still has. */
  kept: number
  total: number
  servers: GameServer[]
  /** Set when the file could not be read, in which case nothing was written. */
  problem: string | null
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

/**
 * A project's own page: the written description, the gallery, which side each
 * environment needs it on, and where its source, issues and wiki live.
 */
export interface ModrinthProjectDetail {
  projectId: string
  slug: string
  title: string
  description: string
  projectType: string
  iconUrl: string | null
  downloads: number
  followers: number
  updated: string
  published: string
  categories: string[]
  gameVersions: string[]
  loaders: string[]
  /** The long description, as Markdown, exactly as published. */
  body: string
  galleryUrls: string[]
  clientSide: string | null
  serverSide: string | null
  license: string | null
  links: { label: string; url: string }[]
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

/**
 * A file the store put in an instance, remembered by the project it came from.
 *
 * The store is a catalogue, and a catalogue that cannot say what you already
 * have makes you open the folder to find out. This is written beside the
 * instance rather than in the launcher's own state, because the files it
 * describes are the instance's: delete the instance's folder and the record goes
 * with it.
 */
export interface StoreInstall {
  projectId: string
  title: string
  versionNumber: string
  /** File name inside the instance, as written. */
  filename: string
  kind: ResourceKind
  at: string
}

export interface StoreInstalls {
  instanceId: string
  dir: string
  /** Only what is still on disk: a file deleted by hand is not installed. */
  items: StoreInstall[]
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
  /**
   * Which theme the window paints, or `system` to follow the desktop.
   *
   * A launcher that stays dark on a light desktop reads as broken rather than
   * deliberate, so following the desktop is the default and the two fixed
   * themes are there for someone who wants what they asked for.
   */
  theme: ThemeChoice
}

export type ThemeChoice = 'system' | 'light' | 'dark'

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

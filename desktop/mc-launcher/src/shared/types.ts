/**
 * Shared shapes for the launcher state. The main process owns the persisted
 * copy; the renderer keeps a working snapshot of the same structure.
 */

export interface Profile {
  id: string
  /** Local profile name, also the in-game name. No account is attached. */
  name: string
  /** Offline UUID derived from the name (RFC 4122 version 3, MD5). */
  uuid: string
  /** Per-profile game directory, or null to use the global one. */
  gameDir: string | null
  /** Selected version id, or null while the user has not picked one yet. */
  versionId: string | null
  createdAt: string
  lastPlayedAt: string | null
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

/** A game version offered by Mojang's manifest, as the install picker shows it. */
export interface RemoteVersion {
  id: string
  type: string
  releaseTime: string
}

/** 'vanilla' installs the game alone; the others add a mod loader on top of it. */
export type InstallLoader = 'vanilla' | 'fabric' | 'quilt'

export interface LoaderOption {
  version: string
  stable: boolean
}

export interface InstallRequest {
  versionId: string
  loader: InstallLoader
  /** Required for fabric and quilt, ignored for vanilla. */
  loaderVersion?: string | null
}

export interface InstallResult {
  /** The game version that was asked for. */
  versionId: string
  /** The version that was actually installed: the loader profile id when one was added. */
  targetId: string
  files: number
  reused: number
  bytes: number
  nativesExtracted: number
}

export interface ImportResult {
  /** A game directory is used in place; a version folder is copied in. */
  kind: 'gameDirectory' | 'version'
  path: string
  message: string
  /** Versions visible after the import. */
  installationCount: number
}

export interface ServerEntry {
  id: string
  name: string
  host: string
  port: number
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
  gameDir: string
  /** Explicit java binary, or null to resolve `java` from PATH. */
  javaPath: string | null
  maxMemoryMb: number
  width: number
  height: number
}

export interface LauncherState {
  profiles: Profile[]
  selectedProfileId: string | null
  servers: ServerEntry[]
  /** Server the next launch should join, or null. */
  pendingServerId: string | null
  settings: LauncherSettings
  tasks: TaskRecord[]
  installations: InstalledVersion[]
  runningProfileId: string | null
}

export interface LaunchPlan {
  profileId: string
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

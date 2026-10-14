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

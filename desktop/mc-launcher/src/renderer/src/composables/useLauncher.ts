import { computed, ref, shallowRef } from 'vue'
import type {
  GameInstance,
  InstanceDiagnostics,
  InstanceGameOptions,
  InstanceSaves,
  InstallRequest,
  JavaRuntimeInfo,
  JavaStatus,
  InstallResult,
  InstanceImportResult,
  InstanceResources,
  InstalledVersion,
  LaunchPlan,
  LauncherSettings,
  LauncherState,
  LoaderOption,
  LogLine,
  ModpackImportResult,
  ModpackSummary,
  ModrinthInstallResult,
  ModrinthProjectDetail,
  ModrinthSearch,
  ModrinthStatus,
  ModrinthVersion,
  PackExportResult,
  Profile,
  RemoteVersion,
  ResourceKind,
  WorldRestoreResult,
  SaveBackup,
  ServerEntry,
  ServerStatus,
  ServersDatResult,
  StoreInstalls,
  TaskRecord,
  WorldExportResult,
  WorldImportResult
} from '@shared/types'

const snapshot = shallowRef<LauncherState | null>(null)
const lines = ref<LogLine[]>([])
const validating = ref(false)
const error = ref<string | null>(null)
const started = ref(false)

/** Java runtimes the main process found, for the Settings page to offer. */
export interface DetectedJava {
  path: string
  major: number | null
  source: string
}

/** Electron prefixes rejected invoke calls; keep the original message. */
export function describeError(value: unknown): string {
  const raw = value instanceof Error ? value.message : String(value)
  const marker = raw.lastIndexOf('Error: ')
  return marker === -1 ? raw : raw.slice(marker + 'Error: '.length)
}

async function call<T>(service: string, method: string, params?: unknown): Promise<T> {
  try {
    return (await window.serviceChannels.call(service, method, params)) as T
  } catch (reason) {
    error.value = describeError(reason)
    throw reason
  }
}

export async function initLauncher(): Promise<void> {
  if (started.value) return
  started.value = true

  window.bootstrap.onSnapshot((next) => {
    snapshot.value = next
  })
  window.bootstrap.onValidating((value) => {
    validating.value = value
  })
  window.serviceChannels.onEvent((event) => {
    if (event.service === 'log' && event.event === 'line') {
      lines.value = [...lines.value, event.payload as LogLine].slice(-1000)
    }
  })

  const boot = await window.bootstrap.load()
  snapshot.value = boot.state
  lines.value = await call<LogLine[]>('log', 'recent', { limit: 500 })
}

export function useLauncher() {
  const profiles = computed<Profile[]>(() => snapshot.value?.profiles ?? [])
  const instances = computed<GameInstance[]>(() => snapshot.value?.instances ?? [])
  const servers = computed<ServerEntry[]>(() => snapshot.value?.servers ?? [])
  const installations = computed<InstalledVersion[]>(() => snapshot.value?.installations ?? [])
  const tasks = computed<TaskRecord[]>(() => snapshot.value?.tasks ?? [])
  const settings = computed<LauncherSettings | null>(() => snapshot.value?.settings ?? null)
  const selectedProfile = computed<Profile | null>(
    () => profiles.value.find((profile) => profile.id === snapshot.value?.selectedProfileId) ?? null
  )
  const selectedInstance = computed<GameInstance | null>(
    () => instances.value.find((instance) => instance.id === snapshot.value?.selectedInstanceId) ?? null
  )
  const pendingServer = computed<ServerEntry | null>(
    () => servers.value.find((server) => server.id === snapshot.value?.pendingServerId) ?? null
  )
  const runningProfileId = computed<string | null>(() => snapshot.value?.runningProfileId ?? null)
  const state = computed<LauncherState | null>(() => snapshot.value)

  return {
    state,
    profiles,
    instances,
    servers,
    installations,
    tasks,
    settings,
    selectedProfile,
    selectedInstance,
    pendingServer,
    runningProfileId,
    lines,
    validating,
    error,
    call,
    dismissError: (): void => {
      error.value = null
    },
    createProfile: (name: string) => call<Profile>('profile', 'create', { name }),
    updateProfile: (id: string, patch: Partial<Profile>) => call<Profile>('profile', 'update', { id, patch }),
    removeProfile: (id: string) => call<{ removed: string }>('profile', 'remove', { id }),
    selectProfile: (id: string | null) => call('profile', 'select', { id }),
    /** Null clears the selection, which is how the store browses everything. */
    selectInstance: (id: string | null) => call('instance', 'select', { id }),

    createInstance: (input: {
      name: string
      versionId?: string | null
      loader?: InstallRequest['loader'] | null
      loaderVersion?: string | null
    }) => call<GameInstance>('instance', 'create', input),
    updateInstance: (id: string, patch: Partial<GameInstance>) =>
      call<GameInstance>('instance', 'update', { id, patch }),
    removeInstance: (id: string, deleteFiles = false) =>
      call<{ removed: string; deleted: boolean; dir: string }>('instance', 'remove', { id, deleteFiles }),
    importInstanceFolder: (path: string) =>
      call<InstanceImportResult>('instance', 'importFolder', { path }),

    resources: (instanceId?: string | null) =>
      call<InstanceResources>('instance', 'resources', instanceId ? { instanceId } : undefined),
    setResourceEnabled: (input: {
      instanceId?: string | null
      kind: ResourceKind
      name: string
      enabled: boolean
    }) => call<InstanceResources>('instance', 'setResourceEnabled', input),
    addResources: (input: { instanceId?: string | null; kind: ResourceKind; paths: string[] }) =>
      call<{ added: string[]; skipped: string[]; resources: InstanceResources }>('instance', 'addResources', input),
    removeResource: (input: { instanceId?: string | null; kind: ResourceKind; name: string }) =>
      call<InstanceResources>('instance', 'removeResource', input),

    gameOptions: (instanceId?: string | null) =>
      call<InstanceGameOptions>('instance', 'gameOptions', instanceId ? { instanceId } : undefined),
    saveGameOptions: (input: {
      instanceId?: string | null
      changes?: Record<string, string | null>
      jvmArgs?: string[]
    }) => call<InstanceGameOptions>('instance', 'setGameOptions', input),

    /** The worlds an instance holds and the backups kept beside them. */
    instanceSaves: (instanceId?: string | null) =>
      call<InstanceSaves>('save', 'list', instanceId ? { instanceId } : undefined),
    backupWorlds: (kind: SaveBackup['kind'] = 'manual', instanceId?: string | null) =>
      call<SaveBackup>('save', 'backup', { instanceId, kind }),
    /** A backup put back into `saves/`, beside whatever is already there. */
    restoreWorldBackup: (input: { instanceId?: string | null; path: string }) =>
      call<WorldRestoreResult>('save', 'restoreBackup', input),
    exportWorld: (input: { instanceId?: string | null; folder: string; destDir: string }) =>
      call<WorldExportResult>('save', 'exportWorld', input),
    importWorld: (input: { instanceId?: string | null; source: string }) =>
      call<WorldImportResult>('save', 'importWorld', input),

    inspectModpack: (path: string) => call<ModpackSummary>('modpack', 'inspect', { path }),
    /** Writes the selected instance out as a `.mrpack`. */
    exportPack: (input: { instanceId?: string | null; destDir: string; name?: string; versionId?: string }) =>
      call<PackExportResult>('modpack', 'export', input),
    importModpack: (path: string) => call<ModpackImportResult>('modpack', 'import', { path }),

    /** Modrinth's own answer about whether it can be reached, and how fast. */
    modrinthStatus: () => call<ModrinthStatus>('modrinth', 'status'),
    modrinthSearch: (input: {
      query: string
      kind: ResourceKind | 'modpack'
      sort?: string
      limit?: number
      offset?: number
      /** Ignores the selected instance and searches the whole catalogue. */
      anyVersion?: boolean
    }) => call<ModrinthSearch & { folder: string }>('modrinth', 'search', input),
    modrinthVersions: (projectIdOrSlug: string, free = false) =>
      call<ModrinthVersion[]>('modrinth', 'versions', { projectIdOrSlug, free }),
    modrinthProject: (projectIdOrSlug: string) =>
      call<ModrinthProjectDetail>('modrinth', 'project', { projectIdOrSlug }),
    /** What the store has already installed into an instance. */
    storeInstalls: (instanceId?: string | null) =>
      call<StoreInstalls>('modrinth', 'installed', instanceId ? { instanceId } : undefined),
    installModpackFromStore: (input: { projectId?: string | null; versionId?: string | null }) =>
      call<ModpackImportResult>('modrinth', 'installModpack', input),
    installFromModrinth: (input: {
      projectId?: string | null
      versionId?: string | null
      kind: ResourceKind
      instanceId?: string | null
    }) => call<ModrinthInstallResult>('modrinth', 'install', input),

    /** Forgets the tasks that have finished, leaving whatever is still running. */
    clearTasks: () => call<{ cleared: number }>('task', 'clear'),

    rescan: () => call<InstalledVersion[]>('installation', 'rescan'),
    remoteVersions: (refresh = false) =>
      call<RemoteVersion[]>('install', 'versions', { refresh }),
    loaderVersions: (loader: 'fabric' | 'quilt') =>
      call<LoaderOption[]>('install', 'loaders', { loader }),
    installVersion: (request: InstallRequest) =>
      call<InstallResult>('install', 'start', request),

    detectJava: () => call<DetectedJava[]>('java', 'detect'),
    javaStatus: (instanceId?: string | null) =>
      call<JavaStatus>('java', 'status', instanceId ? { instanceId } : undefined),
    /** Downloads Mojang's own runtime for a component, as a task. */
    installJava: (component: string) =>
      call<JavaRuntimeInfo & { files: number; bytes: number; reused: number }>('java', 'install', {
        component
      }),
    pickFolder: () => call<{ path: string | null }>('window', 'pickFolder'),
    /** `any` drops the jar/zip filter, which is what choosing a Java binary needs. */
    pickFiles: (any = false) => call<{ paths: string[] }>('window', 'pickFiles', { any }),
    openPath: (path: string) => call<{ opened: boolean; error: string | null }>('window', 'openPath', { path }),

    addServer: (name: string, host: string, port: number) =>
      call<ServerEntry>('server', 'add', { name, host, port }),
    removeServer: (id: string) => call<{ removed: string }>('server', 'remove', { id }),
    setPendingServer: (id: string | null) => call('server', 'setPending', { id }),
    /** A server list ping: the answer says why it failed rather than throwing. */
    pingServer: (input: { id?: string | null; host?: string; port?: number }) =>
      call<ServerStatus>('server', 'ping', input),
    gameServers: (instanceId?: string | null) =>
      call<ServersDatResult>('server', 'gameServers', instanceId ? { instanceId } : undefined),
    syncGameServers: (instanceId?: string | null) =>
      call<ServersDatResult>('server', 'syncGameServers', instanceId ? { instanceId } : undefined),
    importGameServers: (instanceId?: string | null) =>
      call<{ added: number; servers: ServerEntry[] }>(
        'server',
        'importGameServers',
        instanceId ? { instanceId } : undefined
      ),
    planLaunch: (profileId: string, serverId?: string | null, instanceId?: string | null) =>
      call<LaunchPlan>('launch', 'plan', { profileId, serverId, instanceId }),
    startLaunch: (profileId: string, serverId?: string | null, instanceId?: string | null) =>
      call<{ plan: LaunchPlan; pid: number }>('launch', 'start', { profileId, serverId, instanceId }),
    /**
     * Launches what is selected, guarded in one place.
     *
     * The Play page and the keyboard shortcut both come through here, so the two
     * cannot drift into disagreeing about what a launch needs.
     */
    launchSelected: () => {
      const profile = selectedProfile.value
      const instance = selectedInstance.value
      if (!profile) throw new Error('Create a profile first: a launch needs an identity.')
      if (!instance) throw new Error('Create an instance first: a launch needs a game directory.')
      if (!instance.versionId) {
        throw new Error(`Instance "${instance.name}" has no version yet. Install one first.`)
      }
      return call<{ plan: LaunchPlan; pid: number }>('launch', 'start', {
        profileId: profile.id,
        serverId: pendingServer.value?.id ?? null,
        instanceId: instance.id
      })
    },
    stopLaunch: () => call<{ stopped: boolean }>('launch', 'stop'),
    /** The game's own crash reports and log, read and diagnosed. */
    diagnostics: (instanceId?: string | null, name?: string | null) =>
      call<InstanceDiagnostics>('diagnostics', 'report', { instanceId, name }),

    saveSettings: (patch: Partial<LauncherSettings>) => call<LauncherSettings>('settings', 'update', patch),
    clearLogs: () => call<{ cleared: number }>('log', 'clear').then(() => {
      lines.value = []
    })
  }
}

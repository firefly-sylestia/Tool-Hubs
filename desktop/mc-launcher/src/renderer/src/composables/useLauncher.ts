import { computed, ref, shallowRef } from 'vue'
import type {
  GameInstance,
  InstallRequest,
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
  Profile,
  RemoteVersion,
  ResourceKind,
  ServerEntry,
  TaskRecord
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
function cleanMessage(value: unknown): string {
  const raw = value instanceof Error ? value.message : String(value)
  const marker = raw.lastIndexOf('Error: ')
  return marker === -1 ? raw : raw.slice(marker + 'Error: '.length)
}

async function call<T>(service: string, method: string, params?: unknown): Promise<T> {
  try {
    return (await window.serviceChannels.call(service, method, params)) as T
  } catch (reason) {
    error.value = cleanMessage(reason)
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

    createInstance: (input: {
      name: string
      versionId?: string | null
      loader?: InstallRequest['loader'] | null
      loaderVersion?: string | null
    }) => call<GameInstance>('instance', 'create', input),
    updateInstance: (id: string, patch: Partial<GameInstance>) =>
      call<GameInstance>('instance', 'update', { id, patch }),
    selectInstance: (id: string) => call<GameInstance>('instance', 'select', { id }),
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

    inspectModpack: (path: string) => call<ModpackSummary>('modpack', 'inspect', { path }),
    importModpack: (path: string) => call<ModpackImportResult>('modpack', 'import', { path }),

    rescan: () => call<InstalledVersion[]>('installation', 'rescan'),
    remoteVersions: (refresh = false) =>
      call<RemoteVersion[]>('install', 'versions', { refresh }),
    loaderVersions: (loader: 'fabric' | 'quilt') =>
      call<LoaderOption[]>('install', 'loaders', { loader }),
    installVersion: (request: InstallRequest) =>
      call<InstallResult>('install', 'start', request),

    detectJava: () => call<DetectedJava[]>('java', 'detect'),
    pickFolder: () => call<{ path: string | null }>('window', 'pickFolder'),
    pickFiles: () => call<{ paths: string[] }>('window', 'pickFiles'),
    openPath: (path: string) => call<{ opened: boolean; error: string | null }>('window', 'openPath', { path }),

    addServer: (name: string, host: string, port: number) =>
      call<ServerEntry>('server', 'add', { name, host, port }),
    removeServer: (id: string) => call<{ removed: string }>('server', 'remove', { id }),
    setPendingServer: (id: string | null) => call('server', 'setPending', { id }),
    planLaunch: (profileId: string, serverId?: string | null, instanceId?: string | null) =>
      call<LaunchPlan>('launch', 'plan', { profileId, serverId, instanceId }),
    startLaunch: (profileId: string, serverId?: string | null, instanceId?: string | null) =>
      call<{ plan: LaunchPlan; pid: number }>('launch', 'start', { profileId, serverId, instanceId }),
    stopLaunch: () => call<{ stopped: boolean }>('launch', 'stop'),
    saveSettings: (patch: Partial<LauncherSettings>) => call<LauncherSettings>('settings', 'update', patch),
    clearLogs: () => call<{ cleared: number }>('log', 'clear').then(() => {
      lines.value = []
    })
  }
}

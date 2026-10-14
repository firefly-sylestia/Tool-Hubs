import { computed, ref, shallowRef } from 'vue'
import type {
  InstalledVersion,
  LaunchPlan,
  LauncherSettings,
  LauncherState,
  LogLine,
  Profile,
  ServerEntry,
  TaskRecord
} from '@shared/types'

const snapshot = shallowRef<LauncherState | null>(null)
const lines = ref<LogLine[]>([])
const validating = ref(false)
const error = ref<string | null>(null)
const started = ref(false)

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
  const servers = computed<ServerEntry[]>(() => snapshot.value?.servers ?? [])
  const installations = computed<InstalledVersion[]>(() => snapshot.value?.installations ?? [])
  const tasks = computed<TaskRecord[]>(() => snapshot.value?.tasks ?? [])
  const settings = computed<LauncherSettings | null>(() => snapshot.value?.settings ?? null)
  const selectedProfile = computed<Profile | null>(
    () => profiles.value.find((profile) => profile.id === snapshot.value?.selectedProfileId) ?? null
  )
  const pendingServer = computed<ServerEntry | null>(
    () => servers.value.find((server) => server.id === snapshot.value?.pendingServerId) ?? null
  )
  const runningProfileId = computed<string | null>(() => snapshot.value?.runningProfileId ?? null)
  const state = computed<LauncherState | null>(() => snapshot.value)

  return {
    state,
    profiles,
    servers,
    installations,
    tasks,
    settings,
    selectedProfile,
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
    rescan: () => call<InstalledVersion[]>('installation', 'rescan'),
    addServer: (name: string, host: string, port: number) =>
      call<ServerEntry>('server', 'add', { name, host, port }),
    removeServer: (id: string) => call<{ removed: string }>('server', 'remove', { id }),
    setPendingServer: (id: string | null) => call('server', 'setPending', { id }),
    planLaunch: (profileId: string, serverId?: string | null) =>
      call<LaunchPlan>('launch', 'plan', { profileId, serverId }),
    startLaunch: (profileId: string, serverId?: string | null) =>
      call<{ plan: LaunchPlan; pid: number }>('launch', 'start', { profileId, serverId }),
    stopLaunch: () => call<{ stopped: boolean }>('launch', 'stop'),
    saveSettings: (patch: Partial<LauncherSettings>) => call<LauncherSettings>('settings', 'update', patch),
    clearLogs: () => call<{ cleared: number }>('log', 'clear').then(() => {
      lines.value = []
    })
  }
}

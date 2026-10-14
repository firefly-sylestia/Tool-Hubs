import { join } from 'node:path'
import { homedir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { BrowserWindow, app, ipcMain, shell } from 'electron'
import { IPC, type ServiceCallMessage } from '@shared/ipc'
import type { LauncherSettings, LauncherState } from '@shared/types'
import type { AddServerInput } from './services/server-service'
import type { CreateProfileInput, UpdateProfileInput } from './services/profile-service'
import { InstallationService } from './services/installation-service'
import { LaunchService } from './services/launch-service'
import { LogService } from './services/log-service'
import { ProfileService } from './services/profile-service'
import { ServiceRegistry } from './services/registry'
import { ServerService } from './services/server-service'
import { SettingsService } from './services/settings-service'
import { STATE_FILE, StateStore, defaultState } from './services/state-store'
import { TaskService } from './services/task-service'
import { WindowController } from './window-controller'

const here = fileURLToPath(new URL('.', import.meta.url))

function createWindow(): BrowserWindow {
  const window = new BrowserWindow({
    width: 1180,
    height: 780,
    minWidth: 900,
    minHeight: 600,
    show: false,
    autoHideMenuBar: true,
    title: 'MC Launcher',
    // The preload needs `ipcRenderer`, so the sandbox stays off; the renderer
    // itself still has no Node access.
    webPreferences: {
      preload: join(here, '../preload/index.mjs'),
      sandbox: false,
      contextIsolation: true,
      nodeIntegration: false
    }
  })

  window.once('ready-to-show', () => window.show())

  // Nothing in this app opens a second window; external links go to the OS.
  window.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url)
    return { action: 'deny' }
  })

  const rendererUrl = process.env.ELECTRON_RENDERER_URL
  if (rendererUrl) void window.loadURL(rendererUrl)
  else void window.loadFile(join(here, '../renderer/index.html'))

  return window
}

function bootstrapServices(): { registry: ServiceRegistry; state: StateStore } {
  const registry = new ServiceRegistry()
  const log = new LogService(registry)
  const warn = (message: string): void => {
    log.append('stderr', message)
  }

  const environment = {
    userDataDir: app.getPath('userData'),
    defaultGameDir: join(homedir(), '.minecraft'),
    launcherName: 'mc-launcher',
    launcherVersion: app.getVersion(),
    platform: process.platform,
    arch: process.arch
  }

  const state = new StateStore(
    join(environment.userDataDir, STATE_FILE),
    defaultState(environment.defaultGameDir),
    registry,
    warn
  )

  const settings = new SettingsService(state)
  const profiles = new ProfileService(state)
  const installations = new InstallationService(state, warn)
  const servers = new ServerService(state)
  const tasks = new TaskService(state, registry)
  const launch = new LaunchService(state, environment, log, tasks, servers)
  const windows = new WindowController()

  const as = <T,>(params: unknown): T => params as T
  const profileInput = (params: unknown): { id: string; patch: UpdateProfileInput } =>
    as<{ id: string; patch: UpdateProfileInput }>(params)
  const launchInput = (params: unknown): { profileId: string; serverId?: string | null } =>
    as<{ profileId: string; serverId?: string | null }>(params)

  registry.register('settings', {
    get: () => settings.get(),
    update: (params) => settings.update(as<Partial<LauncherSettings>>(params))
  })
  registry.register('profile', {
    list: () => profiles.list(),
    create: (params) => profiles.create(as<CreateProfileInput>(params)),
    update: (params) => {
      const input = profileInput(params)
      return profiles.update(input.id, input.patch)
    },
    remove: (params) => profiles.remove(as<{ id: string }>(params).id),
    select: (params) => profiles.select(as<{ id: string | null }>(params).id)
  })
  registry.register('installation', {
    list: () => installations.list(),
    rescan: () => installations.rescan()
  })
  registry.register('server', {
    list: () => servers.list(),
    add: (params) => servers.add(as<AddServerInput>(params)),
    remove: (params) => servers.remove(as<{ id: string }>(params).id),
    setPending: (params) => servers.setPending(as<{ id: string | null }>(params).id)
  })
  registry.register('launch', {
    plan: (params) => {
      const input = launchInput(params)
      return launch.plan(input.profileId, input.serverId)
    },
    start: (params) => {
      const input = launchInput(params)
      return launch.start(input.profileId, input.serverId)
    },
    stop: () => launch.stop(),
    status: () => launch.status()
  })
  registry.register('task', {
    list: () => tasks.list()
  })
  registry.register('log', {
    recent: (params) => log.recent(as<{ limit?: number } | undefined>(params)?.limit),
    clear: () => log.clear()
  })
  registry.register('window', {
    minimize: () => windows.minimize(),
    maximize: () => windows.maximize(),
    close: () => windows.close(),
    focus: () => windows.focus(),
    isMaximized: () => windows.isMaximized(),
    toggleDevTools: () => windows.toggleDevTools()
  })

  // First inspection of an existing installation, so the UI has something to
  // show on boot. Failures are logged, not fatal.
  try {
    installations.rescan()
  } catch (error) {
    warn(`Initial scan failed: ${(error as Error).message}`)
  }

  return { registry, state }
}

function registerIpc(registry: ServiceRegistry, state: StateStore): void {
  registry.setEmitter((event) => {
    for (const window of BrowserWindow.getAllWindows()) {
      window.webContents.send(IPC.serviceEvent, event)
      if (event.service === 'state' && event.event === 'validating') {
        window.webContents.send(IPC.stateValidating, event.payload === true)
      }
    }
  })

  ipcMain.handle(IPC.serviceCall, async (_event, message: ServiceCallMessage) => {
    if (!message || typeof message.service !== 'string' || typeof message.method !== 'string') {
      throw new Error('A service call needs a service name and a method.')
    }
    return await registry.call(message.service, message.method, message.params)
  })

  ipcMain.handle(IPC.serviceCallTraced, async (_event, message: ServiceCallMessage) => {
    const started = Date.now()
    const result = await registry.call(message.service, message.method, message.params)
    return { result, elapsedMs: Date.now() - started }
  })

  ipcMain.handle(IPC.bootstrap, () => ({
    state: state.snapshot(),
    services: registry.list()
  }))

  ipcMain.handle(IPC.revalidate, () => {
    state.broadcast()
    return state.snapshot()
  })

  ipcMain.handle(IPC.commit, (_event, next: LauncherState) => state.replace(next))
}

app.whenReady().then(() => {
  const { registry, state } = bootstrapServices()
  registerIpc(registry, state)
  createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

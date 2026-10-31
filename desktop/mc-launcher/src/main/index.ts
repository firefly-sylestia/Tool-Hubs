import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { homedir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { BrowserWindow, app, ipcMain, net, shell } from 'electron'
import { IPC, type ServiceCallMessage } from '@shared/ipc'
import type {
  FilePickerKind,
  GameInstance,
  InstallRequest,
  LauncherSettings,
  LauncherState,
  ResourceKind
} from '@shared/types'
import type { AddServerInput } from './services/server-service'
import type { CreateProfileInput, UpdateProfileInput } from './services/profile-service'
import type { CreateInstanceInput } from './services/instance-service'
import type { InstallInput as ModrinthInstallInput, SearchInput } from './services/modrinth-service'
import { DiagnosticService } from './services/diagnostic-service'
import { InstallationService } from './services/installation-service'
import { InstallService } from './services/install-service'
import { InstanceService } from './services/instance-service'
import { JavaService } from './services/java-service'
import { LaunchService } from './services/launch-service'
import { LogService } from './services/log-service'
import { ModpackService } from './services/modpack-service'
import { ModrinthService } from './services/modrinth-service'
import { ProfileService } from './services/profile-service'
import { ServiceRegistry } from './services/registry'
import { SaveService } from './services/save-service'
import { ServerService } from './services/server-service'
import { SettingsService } from './services/settings-service'
import { STATE_FILE, StateStore, defaultState } from './services/state-store'
import { TaskService } from './services/task-service'
import { setSystemFetch } from './minecraft/download'
import { resolveVersionChain } from './minecraft/version-json'
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
    defaultInstancesDir: join(app.getPath('userData'), 'instances'),
    // The launcher's own copy of the game, not the user's `.minecraft`: what is
    // written here is a version's files and nothing of theirs.
    sharedDir: join(app.getPath('userData'), 'game'),
    defaultGameDir: join(homedir(), '.minecraft'),
    launcherName: 'mc-launcher',
    launcherVersion: app.getVersion(),
    platform: process.platform,
    arch: process.arch
  }

  const state = new StateStore(
    join(environment.userDataDir, STATE_FILE),
    defaultState(environment.defaultInstancesDir),
    registry,
    warn
  )

  // Made before anything can look in it, so the first install finds a game
  // folder rather than creating one under a locked path mid-download.
  mkdirSync(environment.sharedDir, { recursive: true })

  const settings = new SettingsService(state)
  const profiles = new ProfileService(state)
  const installations = new InstallationService(state, environment, warn)
  const instances = new InstanceService(state, environment, installations, settings, warn)
  const servers = new ServerService(state, instances, warn)
  const tasks = new TaskService(state, registry)
  const java = new JavaService(environment, warn)
  const saves = new SaveService(instances, warn)
  const diagnostics = new DiagnosticService(instances)
  const install = new InstallService(environment, tasks, installations, instances, warn, {}, saves)
  // A launch checks the installation and repairs it before it builds a command,
  // which means it owns the same installer every other path uses.
  const launch = new LaunchService(state, environment, log, tasks, servers, java, install, diagnostics)
  const modpacks = new ModpackService(install, instances, tasks, warn)
  const modrinth = new ModrinthService(environment, instances, modpacks, tasks, warn)
  const windows = new WindowController()

  const as = <T,>(params: unknown): T => params as T

  /**
   * A path the renderer says it has, checked where it arrives.
   *
   * Every one of these comes from the OS file dialog, so the only way to get
   * here without one is a bug on the other side of the bridge. A service that
   * is handed `undefined` fails inside `node:path` with a message about the
   * argument's type, which names neither the file nor the thing that wanted it;
   * this says both before any work starts.
   */
  const requirePath = (params: unknown, what: string): string => {
    const value = (params as { path?: unknown } | undefined)?.path
    if (typeof value !== 'string' || value.trim() === '') {
      throw new Error(`${what} needs a file, and none was chosen. Pick one and try again.`)
    }
    return value
  }
  const profileInput = (params: unknown): { id: string; patch: UpdateProfileInput } =>
    as<{ id: string; patch: UpdateProfileInput }>(params)
  const instanceInput = (params: unknown): { id: string; patch: Partial<GameInstance> } =>
    as<{ id: string; patch: Partial<GameInstance> }>(params)
  const launchInput = (params: unknown): { profileId: string; serverId?: string | null; instanceId?: string | null } =>
    as<{ profileId: string; serverId?: string | null; instanceId?: string | null }>(params)
  const resourceInput = (params: unknown): { instanceId?: string | null; kind: ResourceKind; name: string } =>
    as<{ instanceId?: string | null; kind: ResourceKind; name: string }>(params)

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
  registry.register('instance', {
    list: () => instances.list(),
    selected: () => instances.selected(),
    create: (params) => instances.create(as<CreateInstanceInput>(params)),
    update: (params) => {
      const input = instanceInput(params)
      return instances.update(input.id, input.patch)
    },
    select: (params) => {
      const input = as<{ id: string | null }>(params)
      // Nothing selected is a state the store uses to browse the whole catalogue.
      return input.id === null ? instances.clearSelection() : instances.select(input.id)
    },
    remove: (params) => instances.remove(as<{ id: string }>(params).id, as<{ deleteFiles?: boolean }>(params)),
    resources: (params) => instances.resources(as<{ instanceId?: string | null } | undefined>(params)?.instanceId),
    setResourceEnabled: (params) => {
      const input = resourceInput(params)
      return instances.setResourceEnabled({
        instanceId: input.instanceId,
        kind: input.kind,
        name: input.name,
        enabled: as<{ enabled: boolean }>(params).enabled
      })
    },
    removeResource: (params) => instances.removeResource(resourceInput(params)),
    gameOptions: (params) =>
      instances.gameOptions(as<{ instanceId?: string | null } | undefined>(params)?.instanceId),
    setGameOptions: (params) =>
      instances.setGameOptions(
        as<{ instanceId?: string | null; changes?: Record<string, string | null>; jvmArgs?: string[] }>(params)
      ),
    addResources: (params) => {
      const input = as<{ instanceId?: string | null; kind: ResourceKind; paths: string[] }>(params)
      return instances.addResources({ instanceId: input.instanceId, kind: input.kind, paths: input.paths })
    },
    importFolder: (params) =>
      instances.importFrom(requirePath(params, 'Importing a game folder'))
  })
  registry.register('installation', {
    list: () => installations.list(),
    rescan: () => installations.rescan()
  })
  registry.register('install', {
    versions: (params) => install.remoteVersions(as<{ refresh?: boolean } | undefined>(params) ?? {}),
    loaders: (params) => install.loaderVersions(as<{ loader: 'fabric' | 'quilt' }>(params)),
    start: (params) => install.install(as<InstallRequest>(params))
  })
  registry.register('modpack', {
    inspect: (params) => modpacks.inspect(requirePath(params, 'Importing a modpack')),
    import: (params) => modpacks.import(requirePath(params, 'Importing a modpack')),
    /** The other half of the modpack story: this instance as a pack. */
    export: (params) =>
      modpacks.export(
        as<{ instanceId?: string | null; destDir: string; name?: string; versionId?: string }>(params)
      )
  })
  registry.register('modrinth', {
    /** Whether Modrinth answers at all, and what it said when it did not. */
    status: () => modrinth.status(),
    search: (params) => modrinth.search(as<SearchInput>(params)),
    versions: (params) =>
      modrinth.versions(
        as<{ projectIdOrSlug: string; gameVersion?: string | null; free?: boolean }>(params)
      ),
    project: (params) => modrinth.project(as<{ projectIdOrSlug: string }>(params).projectIdOrSlug),
    install: (params) => modrinth.install(as<ModrinthInstallInput>(params)),
    installModpack: (params) => modrinth.installModpack(as<{ projectId?: string | null }>(params)),
    /** What the store has already put in this instance, so the store can say so. */
    installed: (params) =>
      modrinth.installed(as<{ instanceId?: string | null } | undefined>(params) ?? {})
  })
  registry.register('java', {
    detect: () => java.detect(),
    /** What this instance needs, what is on the machine, and what is already inside it. */
    status: async (params) => {
      const input = as<{ instanceId?: string | null } | undefined>(params)
      const instance = input?.instanceId ? instances.require(input.instanceId) : instances.selected()
      const detected = await java.detect()
      if (!instance) {
        return {
          instanceId: null,
          dir: null,
          component: null,
          requiredMajor: null,
          installed: [],
          detected
        }
      }
      let component: string | null = null
      let requiredMajor: number | null = null
      if (instance.versionId) {
        const resolved = resolveVersionChain(environment.sharedDir, instance.versionId)
        component =
          resolved.chain
            .map((version) => version.javaVersion?.component)
            .find((value) => typeof value === 'string') ?? null
        requiredMajor = resolved.merged.javaMajorVersion
      }
      return {
        instanceId: instance.id,
        dir: environment.sharedDir,
        component,
        requiredMajor,
        installed: java.installedRuntimes(environment.sharedDir),
        detected
      }
    },
    /** Downloads Mojang's runtime for the version this instance plays. */
    install: (params) => {
      const input = as<{ instanceId?: string | null; component: string }>(params)
      const instance = instances.require(input.instanceId)
      return tasks.run(`Download Java runtime ${input.component}`, (report) =>
        java.installRuntime({
          // The shared game folder: a runtime Mojang publishes is the same
          // for every instance, so it is fetched once for the launcher.
          instanceDir: environment.sharedDir,
          component: input.component,
          onProgress: report
        })
      )
    }
  })
  registry.register('save', {
    list: (params) => saves.list(as<{ instanceId?: string | null } | undefined>(params)?.instanceId),
    backup: (params) => {
      const input = as<{ instanceId?: string | null; kind?: 'manual' | 'before-change' } | undefined>(params)
      return tasks.run('Back up the worlds', () =>
        saves.backup({ instanceId: input?.instanceId, kind: input?.kind })
      )
    },
    restoreBackup: (params) => {
      const input = as<{ instanceId?: string | null; path?: unknown }>(params)
      const path = requirePath(params, 'Restoring a backup')
      return tasks.run('Restore a backup', () => saves.restoreBackup({ ...input, path }))
    },
    exportWorld: (params) => {
      const input = as<{ instanceId?: string | null; folder: string; destDir: string }>(params)
      return tasks.run(`Export ${input.folder}`, () => saves.exportWorld(input))
    },
    importWorld: (params) => {
      const input = as<{ instanceId?: string | null; source?: unknown }>(params)
      const source = (params as { source?: unknown } | undefined)?.source
      if (typeof source !== 'string' || source.trim() === '') {
        throw new Error('Importing a world needs a folder or a .zip to import, and none was chosen.')
      }
      return tasks.run('Import a world', () => saves.importWorld({ ...input, source }))
    }
  })
  registry.register('server', {
    list: () => servers.list(),
    add: (params) => servers.add(as<AddServerInput>(params)),
    remove: (params) => servers.remove(as<{ id: string }>(params).id),
    setPending: (params) => servers.setPending(as<{ id: string | null }>(params).id),
    /** A status ping: the reply is reported, not thrown, so a list can show it. */
    ping: (params) => servers.ping(as<{ id?: string | null; host?: string; port?: number }>(params) ?? {}),
    gameServers: (params) => servers.gameServers(as<{ instanceId?: string | null } | undefined>(params) ?? {}),
    syncGameServers: (params) => servers.syncGameServers(as<{ instanceId?: string | null } | undefined>(params) ?? {}),
    importGameServers: (params) => {
      const input = as<{ instanceId?: string | null } | undefined>(params) ?? {}
      return servers.importGameServers(input)
    }
  })
  registry.register('launch', {
    plan: (params) => {
      const input = launchInput(params)
      return launch.plan(input.profileId, input.serverId, input.instanceId)
    },
    start: (params) => {
      const input = launchInput(params)
      return launch.start(input.profileId, input.serverId, input.instanceId)
    },
    stop: () => launch.stop(),
    status: () => launch.status(),
    dismiss: () => launch.dismissOutcome()
  })
  registry.register('task', {
    list: () => tasks.list(),
    clear: () => tasks.clear()
  })
  registry.register('log', {
    recent: (params) => log.recent(as<{ limit?: number } | undefined>(params)?.limit),
    clear: () => log.clear()
  })
  registry.register('diagnostics', {
    /** What the game left behind: the newest crash, or the tail of its log. */
    report: (params) => {
      const input = as<{ instanceId?: string | null; name?: string | null } | undefined>(params) ?? {}
      return diagnostics.report(input)
    }
  })
  registry.register('window', {
    minimize: () => windows.minimize(),
    maximize: () => windows.maximize(),
    close: () => windows.close(),
    focus: () => windows.focus(),
    isMaximized: () => windows.isMaximized(),
    toggleDevTools: () => windows.toggleDevTools(),
    pickFolder: () => windows.pickFolder(),
    pickFiles: (params) =>
      windows.pickFiles(undefined, as<{ kind?: FilePickerKind } | undefined>(params) ?? {}),
    openPath: (params) => windows.openPath({ path: requirePath(params, 'Opening a folder') })
  })

  // First inspection of the selected instance, so the UI has something to show
  // on boot. Version folders the launcher's own folder does not have yet are
  // taken from the instances that hold them first, so what the UI lists and
  // what a launch can read includes every version already on this machine.
  try {
    const adopted = install.adoptVersions()
    if (adopted.length > 0) {
      warn(`Adopted ${adopted.length} version(s) already on this machine: ${adopted.join(', ')}.`)
    }
    const selected = instances.selected()
    if (selected) instances.ensureFolders(selected)
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
  // Everything that reaches the network goes through Electron's stack rather
  // than Node's: it follows the desktop's proxy settings and trusts its
  // certificate store, which is the difference between Modrinth answering and
  // every request failing behind a proxy.
  setSystemFetch((url, init) => net.fetch(url, init) as Promise<Response>)

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

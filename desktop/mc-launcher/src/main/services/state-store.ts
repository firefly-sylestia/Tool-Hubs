import { randomUUID } from 'node:crypto'
import { existsSync } from 'node:fs'
import type { LauncherState } from '@shared/types'
import { AtomicJsonStore } from './atomic-store'
import type { ServiceRegistry } from './registry'

export const STATE_FILE = 'launcher-state.json'

export function defaultState(instancesDir: string): LauncherState {
  return {
    profiles: [],
    selectedProfileId: null,
    instances: [],
    selectedInstanceId: null,
    servers: [],
    pendingServerId: null,
    settings: {
      instancesDir,
      javaPath: null,
      maxMemoryMb: 4096,
      width: 1280,
      height: 720,
      theme: 'system'
    },
    tasks: [],
    installations: [],
    runningProfileId: null
  }
}

/**
 * Brings an older state file forward.
 *
 * Before instances existed there was one game directory and a version on each
 * profile. A state file from then keeps working by turning that directory into
 * an imported instance and taking the version from the profile — without this,
 * upgrading would look like the launcher had forgotten everything.
 */
export function migrateState(
  state: LauncherState,
  options: { exists?: (path: string) => boolean } = {}
): LauncherState {
  const next = state
  const legacyGameDir = (next.settings as { gameDir?: string }).gameDir ?? null
  const exists = options.exists ?? (() => true)

  if (!Array.isArray(next.instances)) next.instances = []
  if (next.selectedInstanceId === undefined) next.selectedInstanceId = null

  // A directory that is still there becomes an instance; one that was deleted
  // long ago is not worth resurrecting as a broken entry.
  if (next.instances.length === 0 && legacyGameDir && exists(legacyGameDir)) {
    const version = (next.profiles.find((profile) => (profile as { versionId?: string }).versionId) as
      | { versionId?: string }
      | undefined)?.versionId
    next.instances = [
      {
        id: randomUUID(),
        name: 'Imported installation',
        dir: legacyGameDir,
        source: 'imported',
        versionId: version ?? null,
        loader: null,
        loaderVersion: null,
        createdAt: new Date().toISOString(),
        lastPlayedAt: null,
        jvmArgs: [],
        javaPath: null,
        maxMemoryMb: null
      }
    ]
    next.selectedInstanceId = next.instances[0].id
  }

  // An instance from before per-instance JVM arguments simply has none, and one
  // from before Java and memory became per-instance uses the launcher's own
  // choices, which is what `null` means for both.
  for (const instance of next.instances) {
    if (!Array.isArray(instance.jvmArgs)) instance.jvmArgs = []
    if (instance.javaPath === undefined) instance.javaPath = null
    if (instance.maxMemoryMb === undefined) instance.maxMemoryMb = null
  }

  // A state file from before the theme was a setting follows the desktop, which
  // is what the app did then too.
  if (next.settings.theme === undefined) next.settings.theme = 'system'

  // The old fields are gone from the types; drop them so a rewritten state file
  // does not keep carrying values nothing reads.
  for (const profile of next.profiles) delete (profile as { versionId?: string }).versionId
  delete (next.settings as { gameDir?: string }).gameDir
  return next
}

/**
 * Holds the persisted launcher state. Mutations go through `update`, which
 * persists and then broadcasts a fresh snapshot to every renderer, so the UI
 * never has to guess whether a change landed.
 */
export class StateStore {
  private readonly store: AtomicJsonStore<LauncherState>
  private state: LauncherState

  constructor(
    stateFilePath: string,
    defaults: LauncherState,
    private readonly registry: ServiceRegistry,
    onWarning?: (message: string) => void
  ) {
    this.store = new AtomicJsonStore<LauncherState>(stateFilePath, defaults, onWarning)
    this.state = migrateState({ ...defaults, ...this.store.read() }, { exists: existsSync })
  }

  snapshot(): LauncherState {
    return structuredClone(this.state)
  }

  update(mutator: (state: LauncherState) => void): LauncherState {
    const next = structuredClone(this.state)
    mutator(next)
    this.state = next
    this.store.write(next)
    this.broadcast()
    return structuredClone(next)
  }

  /** Replaces the whole state, used by the renderer's `commit` message. */
  replace(next: LauncherState): LauncherState {
    this.state = { ...structuredClone(this.state), ...structuredClone(next) }
    this.store.write(this.state)
    this.broadcast()
    return structuredClone(this.state)
  }

  broadcast(): void {
    this.registry.emit({ service: 'state', event: 'snapshot', payload: this.snapshot() })
  }

  /** Mirrors the studied app's `state-validating` signal around long work. */
  setValidating(value: boolean): void {
    this.registry.emit({ service: 'state', event: 'validating', payload: value })
  }
}

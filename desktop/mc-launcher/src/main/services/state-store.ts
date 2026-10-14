import type { LauncherState } from '@shared/types'
import { AtomicJsonStore } from './atomic-store'
import type { ServiceRegistry } from './registry'

export const STATE_FILE = 'launcher-state.json'

export function defaultState(gameDir: string): LauncherState {
  return {
    profiles: [],
    selectedProfileId: null,
    servers: [],
    pendingServerId: null,
    settings: {
      gameDir,
      javaPath: null,
      maxMemoryMb: 4096,
      width: 1280,
      height: 720
    },
    tasks: [],
    installations: [],
    runningProfileId: null
  }
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
    this.state = { ...defaults, ...this.store.read() }
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

import { isAbsolute } from 'node:path'
import type { LauncherSettings } from '@shared/types'
import type { StateStore } from './state-store'

export const MIN_MEMORY_MB = 512
export const MAX_MEMORY_MB = 65536

/**
 * A memory limit, checked in one place.
 *
 * The default in Settings and an instance's own override both end up as
 * `-Xmx`, so they are held to the same bounds rather than each inventing one.
 */
export function assertMemory(value: unknown): number {
  const memory = Number(value)
  if (!Number.isFinite(memory) || memory < MIN_MEMORY_MB || memory > MAX_MEMORY_MB) {
    throw new Error(`Memory must be between ${MIN_MEMORY_MB} and ${MAX_MEMORY_MB} MB.`)
  }
  return Math.round(memory)
}

function assertDimension(value: unknown, label: string): number {
  const size = Number(value)
  if (!Number.isFinite(size) || size < 320 || size > 16384) {
    throw new Error(`${label} must be between 320 and 16384 pixels.`)
  }
  return Math.round(size)
}

export class SettingsService {
  constructor(private readonly state: StateStore) {}

  get(): LauncherSettings {
    return this.state.snapshot().settings
  }

  update(patch: Partial<LauncherSettings>): LauncherSettings {
    return this.state.update((state) => {
      if (patch.instancesDir !== undefined) {
        if (typeof patch.instancesDir !== 'string' || patch.instancesDir.trim() === '') {
          throw new Error('The instances directory cannot be empty.')
        }
        if (!isAbsolute(patch.instancesDir)) {
          throw new Error('The instances directory must be an absolute path.')
        }
        state.settings.instancesDir = patch.instancesDir
      }
      if (patch.javaPath !== undefined) {
        if (patch.javaPath !== null && patch.javaPath.trim() !== '' && !isAbsolute(patch.javaPath)) {
          throw new Error('The java path must be an absolute path, or empty to use `java` from PATH.')
        }
        state.settings.javaPath = patch.javaPath?.trim() ? patch.javaPath : null
      }
      if (patch.maxMemoryMb !== undefined) {
        state.settings.maxMemoryMb = assertMemory(patch.maxMemoryMb)
      }
      if (patch.width !== undefined) state.settings.width = assertDimension(patch.width, 'Width')
      if (patch.height !== undefined) state.settings.height = assertDimension(patch.height, 'Height')
      if (patch.theme !== undefined) {
        if (patch.theme !== 'system' && patch.theme !== 'light' && patch.theme !== 'dark') {
          throw new Error('The theme must be system, light or dark.')
        }
        state.settings.theme = patch.theme
      }
    }).settings
  }
}

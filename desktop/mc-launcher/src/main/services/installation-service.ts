import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import type { InstalledVersion } from '@shared/types'
import { readVersionJson } from '../minecraft/version-json'
import type { AppEnvironment } from './env'
import type { StateStore } from './state-store'

/**
 * Finds versions that are already installed under `<sharedDir>/versions`.
 *
 * This is a scan, not a repair: it reports what is on disk. Installing a
 * version that is missing — or completing one that is incomplete — is the
 * installer's job, and that is a separate service so a scan stays instant.
 *
 * The versions belong to the launcher rather than to one instance, because the
 * files behind them do: every one of them can be played by any instance, and an
 * instance only has to say which one it plays. That is why this reads the shared
 * game folder and not the instance's own.
 */
export class InstallationService {
  constructor(
    private readonly state: StateStore,
    private readonly env: AppEnvironment,
    private readonly warn: (message: string) => void
  ) {}

  list(): InstalledVersion[] {
    return this.state.snapshot().installations
  }

  /**
   * Scans the launcher's `versions/`. The directory can still be named — the
   * checks point this at a throwaway folder — but nothing in the app does.
   */
  rescan(dir?: string | null): InstalledVersion[] {
    const gameDir = dir ?? this.env.sharedDir ?? null

    if (gameDir === null) {
      return this.state.update((state) => {
        state.installations = []
      }).installations
    }

    const versionsDir = join(gameDir, 'versions')
    if (!existsSync(versionsDir)) {
      this.warn(
        `No versions directory at ${versionsDir}. Install a version into this instance, ` +
          `or import an installation you already have.`
      )
      return this.state.update((state) => {
        state.installations = []
      }).installations
    }

    const found: InstalledVersion[] = []
    for (const entry of readdirSync(versionsDir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue
      const jsonPath = join(versionsDir, entry.name, `${entry.name}.json`)
      if (!existsSync(jsonPath)) continue
      try {
        const json = readVersionJson(jsonPath)
        found.push({
          id: json.id ?? entry.name,
          jsonPath,
          type: json.type ?? null,
          mainClass: json.mainClass ?? null,
          assetsIndex: json.assetIndex?.id ?? json.assets ?? null,
          javaMajorVersion: json.javaVersion?.majorVersion ?? null,
          inheritsFrom: json.inheritsFrom ?? null
        })
      } catch (error) {
        this.warn(`Skipping ${jsonPath}: ${(error as Error).message}`)
      }
    }

    found.sort((a, b) => a.id.localeCompare(b.id))
    return this.state.update((state) => {
      state.installations = found
    }).installations
  }
}

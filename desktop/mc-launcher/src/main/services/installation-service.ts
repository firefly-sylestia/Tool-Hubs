import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import type { InstalledVersion } from '@shared/types'
import { readVersionJson } from '../minecraft/version-json'
import type { StateStore } from './state-store'

/**
 * Finds versions that are already installed under `<gameDir>/versions`.
 *
 * This is a scan, not a repair: it reports what is on disk. Installing a
 * version that is missing — or completing one that is incomplete — is the
 * installer's job, and that is a separate service so a scan stays instant.
 */
export class InstallationService {
  constructor(
    private readonly state: StateStore,
    private readonly warn: (message: string) => void
  ) {}

  list(): InstalledVersion[] {
    return this.state.snapshot().installations
  }

  rescan(): InstalledVersion[] {
    const gameDir = this.state.snapshot().settings.gameDir
    const versionsDir = join(gameDir, 'versions')

    if (!existsSync(versionsDir)) {
      this.warn(
        `No versions directory at ${versionsDir}. Install a version, or point the game ` +
          `directory at an installation you already have.`
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

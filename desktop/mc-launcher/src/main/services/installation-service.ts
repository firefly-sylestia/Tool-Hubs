import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import type { InstalledVersion } from '@shared/types'
import { readVersionJson } from '../minecraft/version-json'
import type { StateStore } from './state-store'

/**
 * Finds versions that are already installed under `<gameDir>/versions`.
 *
 * This launcher deliberately does not download game files. The installation it
 * reads is the one the official launcher (or another launcher) already put
 * there; a missing or incomplete version is reported, not repaired.
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
        `No versions directory at ${versionsDir}. Point the game directory at an existing Minecraft installation.`
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

import { randomUUID } from 'node:crypto'
import { isAbsolute } from 'node:path'
import type { Profile } from '@shared/types'
import { offlineUuid } from '../minecraft/offline-uuid'
import type { StateStore } from './state-store'

/** The name rules the game applies to offline profiles. */
const NAME_PATTERN = /^[A-Za-z0-9_]{3,16}$/

export interface CreateProfileInput {
  name: string
  versionId?: string | null
  gameDir?: string | null
}

export interface UpdateProfileInput {
  name?: string
  versionId?: string | null
  gameDir?: string | null
}

export class ProfileService {
  constructor(private readonly state: StateStore) {}

  list(): Profile[] {
    return this.state.snapshot().profiles
  }

  create(input: CreateProfileInput): Profile {
    const name = assertName(input.name)
    const profile: Profile = {
      id: randomUUID(),
      name,
      uuid: offlineUuid(name),
      gameDir: assertGameDir(input.gameDir),
      versionId: input.versionId ?? null,
      createdAt: new Date().toISOString(),
      lastPlayedAt: null
    }
    this.state.update((state) => {
      if (state.profiles.some((existing) => existing.name.toLowerCase() === name.toLowerCase())) {
        throw new Error(`A profile named "${name}" already exists.`)
      }
      state.profiles.push(profile)
      state.selectedProfileId = profile.id
    })
    return profile
  }

  update(id: string, patch: UpdateProfileInput): Profile {
    let updated: Profile | undefined
    this.state.update((state) => {
      const profile = state.profiles.find((candidate) => candidate.id === id)
      if (!profile) throw new Error(`Unknown profile ${id}.`)
      if (patch.name !== undefined) {
        const name = assertName(patch.name)
        const clash = state.profiles.some(
          (other) => other.id !== id && other.name.toLowerCase() === name.toLowerCase()
        )
        if (clash) throw new Error(`A profile named "${name}" already exists.`)
        profile.name = name
        profile.uuid = offlineUuid(name)
      }
      if (patch.versionId !== undefined) profile.versionId = patch.versionId
      if (patch.gameDir !== undefined) profile.gameDir = assertGameDir(patch.gameDir)
      updated = profile
    })
    if (!updated) throw new Error(`Unknown profile ${id}.`)
    return updated
  }

  remove(id: string): { removed: string } {
    this.state.update((state) => {
      const index = state.profiles.findIndex((candidate) => candidate.id === id)
      if (index === -1) throw new Error(`Unknown profile ${id}.`)
      state.profiles.splice(index, 1)
      if (state.selectedProfileId === id) state.selectedProfileId = state.profiles[0]?.id ?? null
    })
    return { removed: id }
  }

  select(id: string | null): { selectedProfileId: string | null } {
    this.state.update((state) => {
      if (id !== null && !state.profiles.some((profile) => profile.id === id)) {
        throw new Error(`Unknown profile ${id}.`)
      }
      state.selectedProfileId = id
    })
    return { selectedProfileId: id }
  }
}

function assertName(value: unknown): string {
  if (typeof value !== 'string' || !NAME_PATTERN.test(value)) {
    throw new Error('A profile name must be 3-16 characters and use only letters, numbers and underscores.')
  }
  return value
}

function assertGameDir(value: unknown): string | null {
  if (value === undefined || value === null || value === '') return null
  if (typeof value !== 'string' || !isAbsolute(value)) {
    throw new Error('A profile game directory must be an absolute path.')
  }
  return value
}

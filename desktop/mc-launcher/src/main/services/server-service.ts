import { randomUUID } from 'node:crypto'
import type { ServerEntry } from '@shared/types'
import type { StateStore } from './state-store'

export interface AddServerInput {
  name: string
  host: string
  port?: number
}

const HOST_PATTERN = /^[A-Za-z0-9.-]+$/

function assertHost(value: unknown): string {
  if (typeof value !== 'string' || !HOST_PATTERN.test(value)) {
    throw new Error('A server address may only contain letters, numbers, dots and dashes.')
  }
  return value
}

function assertPort(value: unknown): number {
  const port = value === undefined ? 25565 : Number(value)
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('A server port must be a whole number between 1 and 65535.')
  }
  return port
}

export class ServerService {
  constructor(private readonly state: StateStore) {}

  list(): ServerEntry[] {
    return this.state.snapshot().servers
  }

  add(input: AddServerInput): ServerEntry {
    const entry: ServerEntry = {
      id: randomUUID(),
      name: typeof input.name === 'string' && input.name.trim() !== '' ? input.name.trim() : assertHost(input.host),
      host: assertHost(input.host),
      port: assertPort(input.port)
    }
    this.state.update((state) => {
      state.servers.push(entry)
    })
    return entry
  }

  remove(id: string): { removed: string } {
    this.state.update((state) => {
      const index = state.servers.findIndex((server) => server.id === id)
      if (index === -1) throw new Error(`Unknown server ${id}.`)
      state.servers.splice(index, 1)
      if (state.pendingServerId === id) state.pendingServerId = null
    })
    return { removed: id }
  }

  /** Queues a server for the next launch, or clears the queue with null. */
  setPending(id: string | null): { pendingServerId: string | null } {
    this.state.update((state) => {
      if (id !== null && !state.servers.some((server) => server.id === id)) {
        throw new Error(`Unknown server ${id}.`)
      }
      state.pendingServerId = id
    })
    return { pendingServerId: id }
  }
}

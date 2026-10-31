import { randomUUID } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { createConnection } from 'node:net'
import { join } from 'node:path'
import type { ServerEntry, ServerStatus, ServersDatResult } from '@shared/types'
import { readNbt, writeNbt, type NbtCompound } from '../minecraft/nbt'
import {
  expectedFrameLength,
  handshakePacket,
  offlineStatus,
  parseServerStatus,
  statusJsonFromFrame,
  statusRequestPacket
} from '../minecraft/server-status'
import { mergeServerEntries, readServersDat, serversFrom, writeServersDat } from '../minecraft/servers-dat'
import type { InstanceService } from './instance-service'
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

/** `servers.dat`, which is where the game keeps the servers people add in it. */
const SERVERS_DAT = 'servers.dat'

export class ServerService {
  constructor(
    private readonly state: StateStore,
    private readonly instances: InstanceService,
    private readonly warn: (message: string) => void
  ) {}

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

  /**
   * Pings a server the way the game's own multiplayer screen does: handshake,
   * status request, one JSON answer.
   *
   * A refused connection, a timeout and a server that answers with something
   * that is not a status response are all reported as offline with the reason,
   * rather than as an error the UI has to interpret: not being able to reach a
   * server is the normal case for a server list, not an exceptional one.
   */
  ping(input: { id?: string | null; host?: string; port?: number }, timeoutMs = 5000): Promise<ServerStatus> {
    const saved = input.id ? this.state.snapshot().servers.find((server) => server.id === input.id) : undefined
    const host = saved?.host ?? input.host ?? ''
    const port = saved?.port ?? input.port ?? 25565
    if (!HOST_PATTERN.test(host)) {
      return Promise.resolve(offlineStatus({ host, port, error: `${host} is not an address.` }))
    }

    return new Promise<ServerStatus>((resolve) => {
      let settled = false
      let received = Buffer.alloc(0)
      let sentAt = 0
      const chunks: Buffer[] = []

      const finish = (status: ServerStatus): void => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        socket.destroy()
        resolve(status)
      }

      const socket = createConnection({ host, port })
      const timer = setTimeout(
        () => finish(offlineStatus({ host, port, error: `No answer within ${timeoutMs / 1000}s.` })),
        timeoutMs
      )

      socket.once('connect', () => {
        socket.write(handshakePacket(host, port))
        sentAt = Date.now()
        socket.write(statusRequestPacket())
      })

      socket.on('data', (chunk: Buffer) => {
        chunks.push(chunk)
        received = Buffer.concat([received, chunk])
        const needed = expectedFrameLength(received)
        if (needed === null || received.length < needed) return
        const json = statusJsonFromFrame(received)
        if (json === null) {
          finish(offlineStatus({ host, port, error: 'Answered with something that is not a status.' }))
          return
        }
        try {
          finish(
            parseServerStatus(JSON.parse(json), {
              host,
              port,
              latencyMs: sentAt === 0 ? null : Date.now() - sentAt
            })
          )
        } catch (error) {
          finish(offlineStatus({ host, port, error: `Unreadable status: ${(error as Error).message}` }))
        }
      })

      socket.once('error', (error) => {
        const code = (error as NodeJS.ErrnoException).code
        const reason =
          code === 'ECONNREFUSED'
            ? 'Nothing is listening there.'
            : code === 'ENOTFOUND'
              ? 'That address does not resolve.'
              : code === 'ETIMEDOUT'
                ? 'The connection timed out.'
                : error.message
        finish(offlineStatus({ host, port, error: reason }))
      })

      socket.once('close', () => {
        finish(offlineStatus({ host, port, error: 'The server closed the connection first.' }))
      })
    })
  }

  /**
   * Adds the launcher's servers to the game's own `servers.dat`, keeping every
   * server that was added in the game itself.
   */
  syncGameServers(input: { instanceId?: string | null } = {}): ServersDatResult {
    const instance = this.instances.require(input.instanceId)
    const path = join(instance.dir, SERVERS_DAT)
    const existing = readExisting(path)
    if (existing.problem) {
      throw new Error(
        `${path} is not a server list this launcher can read (${existing.problem}). ` +
          `Rename it and sync again rather than losing the servers in it.`
      )
    }

    const merged = mergeServerEntries(existing.entries, this.state.snapshot().servers)
    writeFileSync(path, writeNbt({ name: '', root: writeServersDat(merged.entries) }))

    const result: ServersDatResult = {
      instanceId: instance.id,
      path,
      added: merged.added,
      kept: existing.entries.length,
      total: merged.entries.length,
      servers: serversFrom(merged.entries),
      problem: null
    }
    this.warn(
      `Wrote ${result.added} server(s) into the game's own list at ${path}, which already held ` +
        `${result.kept} that are still there.`
    )
    return result
  }

  /** The game's own list, as it is on disk right now. */
  gameServers(input: { instanceId?: string | null } = {}): ServersDatResult {
    const instance = this.instances.require(input.instanceId)
    const path = join(instance.dir, SERVERS_DAT)
    const existing = readExisting(path)
    return {
      instanceId: instance.id,
      path,
      added: 0,
      kept: existing.entries.length,
      total: existing.entries.length,
      servers: serversFrom(existing.entries),
      problem: existing.problem
    }
  }

  /**
   * The game's servers added to the launcher's, by address.
   *
   * This is the other direction of the sync, and it is how a server someone
   * added while playing turns up in the launcher's queue-join list.
   */
  importGameServers(input: { instanceId?: string | null } = {}): { added: number; servers: ServerEntry[] } {
    const instance = this.instances.require(input.instanceId)
    const path = join(instance.dir, SERVERS_DAT)
    const existing = readExisting(path)
    if (existing.problem) {
      throw new Error(`Could not read ${path}: ${existing.problem}`)
    }

    let added = 0
    const imported: ServerEntry[] = []
    for (const server of serversFrom(existing.entries)) {
      const address = `${server.host}:${server.port}`
      const known = this.state
        .snapshot()
        .servers.some((entry) => `${entry.host}:${entry.port}` === address)
      if (known) continue
      imported.push(
        this.add({ name: server.name, host: server.host, port: server.port })
      )
      added += 1
    }
    if (added > 0) {
      this.warn(`Imported ${added} server(s) from ${instance.name}'s own list.`)
    }
    return { added, servers: imported }
  }
}

/**
 * The existing `servers.dat`, or the reason it could not be read.
 *
 * A file this launcher cannot read is never written over: the game's own list is
 * the one that matters, and replacing something unreadable would throw away
 * servers someone added while playing. The caller decides — a read reports the
 * problem, a write refuses.
 */
function readExisting(path: string): { entries: NbtCompound[]; problem: string | null } {
  if (!existsSync(path)) return { entries: [], problem: null }
  try {
    return { entries: readServersDat(readNbt(readFileSync(path)).root).entries, problem: null }
  } catch (error) {
    return { entries: [], problem: (error as Error).message }
  }
}

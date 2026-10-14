import type { GameServer } from '@shared/types'
import type { NbtCompound, NbtValue } from './nbt'

/**
 * The game's own server list.
 *
 * `servers.dat` is a compound holding one list, and each entry is a compound
 * with a `name` and an `ip` — the address with `:port` appended when the port is
 * not the default. It is written by the game whenever someone adds a server in
 * the multiplayer screen, which is why this file is *merged* with rather than
 * replaced by the launcher's list: the servers somebody added while playing are
 * in there and nowhere else.
 *
 * Entries are kept as the compounds they were read as, so an untouched server
 * keeps every field the game or another launcher put in it — the icon, whether
 * it accepts textures, whether it is hidden — instead of being rewritten down to
 * the two fields this launcher happens to understand.
 */

export const DEFAULT_SERVER_PORT = 25565

/** Splits the game's `ip` back into a host and a port. */
export function splitAddress(address: string): { host: string; port: number } {
  const trimmed = address.trim()
  // IPv6 is written in brackets when a port follows it; without them, the colons
  // are the address itself.
  const bracketed = /^\[([^\]]+)\](?::(\d+))?$/.exec(trimmed)
  if (bracketed) {
    return { host: bracketed[1], port: bracketed[2] ? Number(bracketed[2]) : DEFAULT_SERVER_PORT }
  }
  // More than one colon and no brackets is a bare IPv6 address, not an address
  // with a port: the game writes `[host]:port` when it means both.
  if ((trimmed.match(/:/g) ?? []).length > 1) return { host: trimmed, port: DEFAULT_SERVER_PORT }
  const index = trimmed.lastIndexOf(':')
  if (index === -1) return { host: trimmed, port: DEFAULT_SERVER_PORT }
  const port = Number(trimmed.slice(index + 1))
  if (!Number.isInteger(port) || port < 1 || port > 65535) return { host: trimmed, port: DEFAULT_SERVER_PORT }
  return { host: trimmed.slice(0, index), port }
}

/** What the game writes for a server: the port only when it is not the default. */
export function joinAddress(host: string, port: number): string {
  return port === DEFAULT_SERVER_PORT ? host : `${host}:${port}`
}

function readString(compound: NbtCompound, key: string): string | null {
  const value = compound[key]
  return value && value.tag === 'string' && value.value !== '' ? value.value : null
}

/** The servers in a `servers.dat`, skipping anything that is not one. */
export function readServersDat(root: NbtCompound): { entries: NbtCompound[]; servers: GameServer[] } {
  const list = root.servers
  if (!list || list.tag !== 'list') return { entries: [], servers: [] }
  const entries: NbtCompound[] = []
  for (const item of list.items) {
    if (item.tag === 'compound' && readString(item.value, 'ip')) entries.push(item.value)
  }
  return { entries, servers: serversFrom(entries) }
}

/** The servers a list of entries describes, in file order. */
export function serversFrom(entries: NbtCompound[]): GameServer[] {
  const servers: GameServer[] = []
  for (const entry of entries) {
    const address = readString(entry, 'ip')
    if (!address) continue
    const { host, port } = splitAddress(address)
    servers.push({
      name: readString(entry, 'name') ?? host,
      host,
      port,
      address,
      players: readPlayers(entry)
    })
  }
  return servers
}

/** The player names a status ping wrote into an entry, if it is that shape. */
function readPlayers(compound: NbtCompound): string[] {
  const players = compound.players
  if (!players || players.tag !== 'list') return []
  const names: string[] = []
  for (const item of players.items) {
    if (item.tag !== 'compound') continue
    const name = readString(item.value, 'name')
    if (name) names.push(name)
  }
  return names
}

export function serverCompound(input: { name: string; host: string; port: number }): NbtCompound {
  return {
    name: { tag: 'string', value: input.name },
    ip: { tag: 'string', value: joinAddress(input.host, input.port) }
  }
}

/**
 * The launcher's servers added to the game's, by address.
 *
 * An address the game already has is *renamed* to what the launcher calls it
 * rather than added twice, and a server only the game knows is left exactly as
 * it was. The count returned is how many arrived.
 */
export function mergeServerEntries(
  existing: NbtCompound[],
  incoming: { name: string; host: string; port: number }[]
): { entries: NbtCompound[]; added: number } {
  const entries = existing.map((entry) => ({ ...entry }))
  let added = 0

  for (const server of incoming) {
    const address = joinAddress(server.host, server.port)
    const match = entries.find((entry) => readString(entry, 'ip') === address)
    if (match) {
      match.name = { tag: 'string', value: server.name }
      continue
    }
    entries.push(serverCompound(server))
    added += 1
  }

  return { entries, added }
}

/** The whole file: one list of compounds. */
export function writeServersDat(entries: NbtCompound[]): NbtCompound {
  const items: NbtValue[] = entries.map((entry) => ({ tag: 'compound', value: entry }))
  return { servers: { tag: 'list', element: 'compound', items } }
}

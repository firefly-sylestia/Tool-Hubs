import type { ServerStatus } from './types'

/**
 * How a ping reads, in one line.
 *
 * Shared rather than written twice: the main process logs it and the list shows
 * it, and two copies of "version · players · ms" drift the first time one of
 * them gains a field.
 */
export function describeStatus(status: ServerStatus): string {
  if (!status.online) return status.error ?? 'offline'
  const parts: string[] = []
  if (status.version) parts.push(status.version)
  if (status.players) parts.push(`${status.players.online}/${status.players.max} players`)
  if (status.latencyMs !== null) parts.push(`${status.latencyMs} ms`)
  return parts.join(' · ') || 'online'
}

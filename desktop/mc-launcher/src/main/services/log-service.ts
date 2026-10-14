import type { LogLine } from '@shared/types'
import type { ServiceRegistry } from './registry'

const MAX_LINES = 2000

/** Keeps the most recent launcher and game output for the log view. */
export class LogService {
  private readonly lines: LogLine[] = []

  constructor(private readonly registry: ServiceRegistry) {}

  append(stream: LogLine['stream'], line: string): void {
    const entry: LogLine = { stream, line, at: new Date().toISOString() }
    this.lines.push(entry)
    if (this.lines.length > MAX_LINES) this.lines.splice(0, this.lines.length - MAX_LINES)
    this.registry.emit({ service: 'log', event: 'line', payload: entry })
  }

  recent(limit = 500): LogLine[] {
    const count = Math.max(1, Math.min(MAX_LINES, Number(limit) || 500))
    return this.lines.slice(-count)
  }

  clear(): { cleared: number } {
    const cleared = this.lines.length
    this.lines.length = 0
    return { cleared }
  }
}

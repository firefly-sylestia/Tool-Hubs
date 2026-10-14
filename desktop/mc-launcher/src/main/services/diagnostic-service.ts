import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import type { CrashFile, InstanceDiagnostics } from '@shared/types'
import { diagnose, isCrashReport, sortReports, summarizeReport } from '../minecraft/crash-report'
import type { InstanceService } from './instance-service'

/**
 * What the game left behind when it went wrong.
 *
 * The launcher's own log holds what the launcher did; these are the files the
 * *game* writes — `logs/latest.log` and a `crash-reports/crash-*.txt` per crash
 * — read all the way back and turned into something worth reading. Nothing here
 * writes anything: a crash report is evidence, and the one thing that must not
 * happen to it is being replaced.
 */

/** How much of `latest.log` is read: the end holds the crash, not the start. */
const LOG_TAIL_LINES = 600
/** Crash reports older than this many are left on disk but not listed. */
const MAX_REPORTS = 25

export class DiagnosticService {
  constructor(private readonly instances: InstanceService) {}

  /** The newest crash, its findings, and the tail of the log that goes with it. */
  report(input: { instanceId?: string | null; name?: string | null } = {}): InstanceDiagnostics {
    const instance = this.instances.require(input.instanceId)
    const crashDir = join(instance.dir, 'crash-reports')
    const logPath = join(instance.dir, 'logs', 'latest.log')

    const crashes = this.crashes(crashDir, input.name ?? null)
    const latestLog = this.logTail(logPath)
    const selected = crashes.find((crash) => crash.selected) ?? crashes[0] ?? null

    // A crash report is the better evidence when there is one; otherwise the log
    // is all there is, and its own findings are the answer.
    const findings = selected
      ? selected.findings
      : latestLog.exists
        ? diagnose(latestLog.text)
        : []

    return {
      instanceId: instance.id,
      instanceDir: instance.dir,
      crashDir,
      logPath,
      crashes,
      latestLog: {
        path: logPath,
        exists: latestLog.exists,
        bytes: latestLog.bytes,
        lines: latestLog.lines,
        findings: latestLog.exists ? diagnose(latestLog.text) : []
      },
      findings,
      source: selected ? selected.name : latestLog.exists ? 'logs/latest.log' : null
    }
  }

  private crashes(dir: string, only: string | null): CrashFile[] {
    if (!existsSync(dir)) return []
    let names: string[] = []
    try {
      names = readdirSync(dir).filter((name) => isCrashReport(name))
    } catch {
      return []
    }

    const files: CrashFile[] = []
    for (const name of sortReports(names.map((entry) => ({ name: entry }))).map((entry) => entry.name)) {
      const path = join(dir, name)
      try {
        const text = readFileSync(path, 'utf8')
        const summary = summarizeReport(text)
        files.push({
          name,
          path,
          bytes: statSync(path).size,
          at: statSync(path).mtime.toISOString(),
          description: summary.description,
          exception: summary.exception,
          at_line: summary.at,
          minecraftVersion: summary.minecraftVersion,
          javaVersion: summary.javaVersion,
          mods: summary.mods.length,
          findings: diagnose(text),
          selected: only ? name === only : files.length === 0
        })
      } catch {
        // A report that cannot be read is not worth failing the whole view over;
        // it simply does not appear.
      }
      if (files.length >= MAX_REPORTS) break
    }
    return files
  }

  private logTail(path: string): {
    exists: boolean
    bytes: number
    lines: number
    text: string
  } {
    if (!existsSync(path)) return { exists: false, bytes: 0, lines: 0, text: '' }
    try {
      const bytes = statSync(path).size
      const all = readFileSync(path, 'utf8').split(/\r?\n/)
      const tail = all.slice(-LOG_TAIL_LINES)
      return { exists: true, bytes, lines: all.length, text: tail.join('\n') }
    } catch {
      return { exists: false, bytes: 0, lines: 0, text: '' }
    }
  }
}

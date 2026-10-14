import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import {
  expandJavaDirectory,
  javaCandidates,
  javaExecutableName,
  parseJavaMajorVersion,
  selectJava,
  type JavaCandidate
} from '../minecraft/java'
import type { AppEnvironment } from './env'

export interface ResolvedJava {
  path: string
  major: number | null
  /** Where it was found, so the UI can say why it picked that one. */
  source: string
}

interface ProbedJava extends ResolvedJava {
  onDisk: boolean
}

/**
 * Finds a Java runtime and remembers what it found.
 *
 * Running `java -version` costs about a tenth of a second per candidate, so
 * results are cached for the life of the process; the alternative is paying
 * that on every launch and every settings paint.
 */
export class JavaService {
  private readonly majors = new Map<string, number | null>()

  constructor(
    private readonly env: AppEnvironment,
    private readonly warn: (message: string) => void
  ) {}

  /**
   * The runtime to launch with: the one named in Settings if there is one,
   * otherwise the best candidate on this machine.
   */
  async resolve(input: {
    instanceDir: string
    component?: string | null
    requiredMajor: number | null
    explicitPath?: string | null
  }): Promise<ResolvedJava> {
    if (input.explicitPath && input.explicitPath.trim() !== '') {
      const path = input.explicitPath.trim()
      return { path, major: await this.majorOf(path), source: 'the java path set in Settings' }
    }

    const probed = await this.probe(input)
    const chosen = selectJava(
      probed.map((candidate) => ({ path: candidate.path, major: candidate.major })),
      input.requiredMajor
    )
    const match = chosen ? probed.find((candidate) => candidate.path === chosen.path) : undefined
    if (!match) {
      throw new Error(this.missingJavaMessage(input))
    }
    this.warn(
      `Using java ${match.major ?? 'unknown version'} from ${match.source} for ${input.requiredMajor ?? 'any'} required.`
    )
    return { path: match.path, major: match.major, source: match.source }
  }

  /** Every runtime that answers, for the Settings page to offer. */
  async detect(): Promise<ResolvedJava[]> {
    const probed = await this.probe({ instanceDir: '' })
    return probed
      .filter((candidate) => candidate.major !== null)
      .sort((a, b) => (b.major ?? 0) - (a.major ?? 0))
      .map(({ path, major, source }) => ({ path, major, source }))
  }

  private async probe(input: { instanceDir: string; component?: string | null }): Promise<ProbedJava[]> {
    const found: ProbedJava[] = []
    const seen = new Set<string>()

    const push = (path: string, source: string, knownMajor: number | null): void => {
      if (seen.has(path)) return
      seen.add(path)
      found.push({ path, source, major: knownMajor, onDisk: existsSync(path) })
    }

    for (const candidate of this.candidates(input)) {
      const expanded = expandJavaDirectory(candidate, this.env.platform)
      if (expanded.length > 0) {
        for (const entry of expanded) push(entry.path, entry.source, entry.knownMajor)
        continue
      }
      if (existsSync(candidate.path)) push(candidate.path, candidate.source, candidate.knownMajor)
    }

    // Whatever `java` resolves to on PATH is still worth trying; it is the only
    // candidate that cannot be checked by looking, so it is probed like the rest.
    push(javaExecutableName(this.env.platform), 'PATH', null)

    const resolved: ProbedJava[] = []
    for (const candidate of found) {
      const major = candidate.major ?? (await this.majorOf(candidate.path))
      // A path that exists is a real file even if its version output is odd; the
      // bare `java` from PATH has to answer to count.
      if (candidate.onDisk || major !== null) resolved.push({ ...candidate, major })
    }
    return resolved
  }

  private candidates(input: { instanceDir: string; component?: string | null }): JavaCandidate[] {
    return javaCandidates({
      platform: this.env.platform,
      instanceDir: input.instanceDir,
      component: input.component ?? null,
      env: process.env,
      homeDir: homedir()
    })
  }

  private async majorOf(path: string): Promise<number | null> {
    const cached = this.majors.get(path)
    if (cached !== undefined) return cached
    const output = await runVersion(path)
    const major = output === null ? null : parseJavaMajorVersion(output)
    this.majors.set(path, major)
    return major
  }

  private missingJavaMessage(input: { instanceDir: string; requiredMajor: number | null }): string {
    const looked = this.candidates(input)
      .map((candidate) => candidate.source)
      .filter((source, index, all) => all.indexOf(source) === index)
    const wanted = input.requiredMajor === null ? 'a JDK' : `a JDK ${input.requiredMajor} or newer`
    return (
      `No Java runtime found. Looked at ${looked.join(', ')}, and for java on PATH. ` +
      `Install ${wanted}, or set the path to java in Settings — the official launcher's ` +
      `own runtime in ${join(input.instanceDir, 'runtime')} is used automatically when it is there.`
    )
  }
}

/** Spawns `java -version`, or null when the binary cannot be started at all. */
function startJava(path: string): import('node:child_process').ChildProcess | null {
  try {
    return spawn(path, ['-version'], { stdio: ['ignore', 'pipe', 'pipe'] })
  } catch {
    return null
  }
}

/** Runs `java -version`, which writes its version line to stderr. */
export function runVersion(path: string, timeoutMs = 5000): Promise<string | null> {
  return new Promise<string | null>((resolve) => {
    let settled = false
    const finish = (value: string | null): void => {
      if (settled) return
      settled = true
      resolve(value)
    }

    const child = startJava(path)
    if (!child) {
      finish(null)
      return
    }

    let output = ''
    child.stdout?.on('data', (chunk: Buffer) => {
      output += chunk.toString('utf8')
    })
    child.stderr?.on('data', (chunk: Buffer) => {
      output += chunk.toString('utf8')
    })
    child.once('error', () => finish(null))
    child.once('close', () => finish(output))

    const timer = setTimeout(() => {
      child.kill('SIGKILL')
      finish(output.length > 0 ? output : null)
    }, timeoutMs)
    timer.unref()
  })
}

import { spawn } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, symlinkSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { fetchJson, downloadAll, type DownloadProgress } from '../minecraft/download'
import {
  expandJavaDirectory,
  javaCandidates,
  javaExecutableName,
  parseJavaMajorVersion,
  runtimeCandidate,
  runtimePlatformDir,
  RUNTIME_COMPONENT_MAJORS,
  selectJava,
  type JavaCandidate
} from '../minecraft/java'
import {
  JAVA_RUNTIME_INDEX_URL,
  findRuntimeManifest,
  listRuntimeComponents,
  parseRuntimeManifest,
  runtimeDirFor,
  runtimeOsKey
} from '../minecraft/java-runtime'
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

  /**
   * Resolves a runtime, installing Mojang's own when the machine has none.
   *
   * This is what the official launcher does: rather than telling someone to go
   * and install a JDK, the runtime the version asks for is fetched into that
   * game directory, where it stays for that instance. The download is a source
   * build of OpenJDK from Mojang, every file verified against the sha1 the
   * manifest declares.
   */
  async ensure(input: {
    instanceDir: string
    component?: string | null
    requiredMajor: number | null
    explicitPath?: string | null
    onProgress?: (progress: number, message: string) => void
  }): Promise<ResolvedJava> {
    try {
      return await this.resolve(input)
    } catch (error) {
      if (!input.component) throw error
      const installed = await this.installRuntime({
        instanceDir: input.instanceDir,
        component: input.component,
        onProgress: input.onProgress
      })
      this.warn(
        `Installed ${input.component} (Java ${installed.major ?? 'unknown'}) into ${input.instanceDir}.`
      )
      return installed
    }
  }

  /**
   * Downloads one of Mojang's runtimes into a game directory.
   *
   * The raw copy of each file is used rather than the LZMA one: the compressed
   * copy would need an LZMA decoder, and the file is verified against the same
   * sha1 either way. Executables are marked executable, which is what makes
   * `bin/java` runnable on anything that is not Windows.
   */
  async installRuntime(input: {
    instanceDir: string
    component: string
    onProgress?: (progress: number, message: string) => void
  }): Promise<ResolvedJava & { files: number; bytes: number; reused: number }> {
    const os = runtimeOsKey(this.env.platform, this.env.arch)
    if (!os) {
      throw new Error(`Mojang publishes no Java runtime for ${this.env.platform}/${this.env.arch}.`)
    }

    const index = await fetchJson<unknown>(JAVA_RUNTIME_INDEX_URL, { retries: 2 })
    const entry = findRuntimeManifest(index, { os, component: input.component })
    if (!entry) {
      const available = listRuntimeComponents(index, os).join(', ')
      throw new Error(
        `Mojang does not publish the ${input.component} runtime for ${os}. Available: ${available}.`
      )
    }

    const report = input.onProgress ?? (() => {})
    report(0.05, `Reading Mojang's manifest for ${input.component} ${entry.name}`)
    const manifest = parseRuntimeManifest(await fetchJson<unknown>(entry.manifestUrl, { retries: 2 }))
    const dir = runtimeDirFor({
      instanceDir: input.instanceDir,
      component: input.component,
      platformDir: runtimePlatformDir(this.env.platform)
    })

    mkdirSync(dir, { recursive: true })
    for (const directory of manifest.directories) mkdirSync(join(dir, directory), { recursive: true })

    report(
      0.1,
      `Downloading Java ${entry.name} (${(manifest.bytes / 1048576).toFixed(0)} MB) from Mojang`
    )
    const result = await downloadAll(
      manifest.filesToDownload.map((file) => ({
        url: file.url!,
        dest: join(dir, file.path),
        sha1: file.sha1,
        size: file.size,
        label: file.path
      })),
      {
        onProgress: (progress: DownloadProgress) => {
          const fraction =
            progress.bytesTotal > 0 ? progress.bytesDone / progress.bytesTotal : progress.completed / progress.total
          report(
            0.1 + fraction * 0.85,
            `Java runtime: ${progress.completed}/${progress.total} files`
          )
        }
      }
    )

    for (const file of manifest.filesToDownload) {
      if (!file.executable || this.env.platform === 'win32') continue
      try {
        chmodSync(join(dir, file.path), 0o755)
      } catch {
        // A runtime on a filesystem that cannot hold the bit still runs for the
        // user who unpacked it; failing the whole install over it would not.
      }
    }
    for (const link of manifest.links) {
      if (!link.target) continue
      const target = join(dir, link.path)
      if (existsSync(target)) continue
      try {
        mkdirSync(dirname(target), { recursive: true })
        symlinkSync(link.target, target)
      } catch {
        // Symlinks need privileges on Windows; the runtime's own files are there
        // either way, and this link is an extra path to one of them.
      }
    }

    const javaPath = join(dir, 'bin', javaExecutableName(this.env.platform))
    if (!existsSync(javaPath)) {
      throw new Error(
        `The ${input.component} runtime unpacked to ${dir} but has no ${javaExecutableName(this.env.platform)} in bin/.`
      )
    }
    const major = await this.majorOf(javaPath)
    report(1, `Java ${major ?? entry.name} is ready`)
    return {
      path: javaPath,
      major,
      source: `Mojang's ${input.component} runtime (${entry.name})`,
      files: manifest.filesToDownload.length,
      bytes: result.bytes,
      reused: result.reused
    }
  }

  /** The runtimes already unpacked inside a game directory. */
  installedRuntimes(instanceDir: string): { component: string; path: string }[] {
    const found: { component: string; path: string }[] = []
    if (!instanceDir) return found
    for (const component of Object.keys(RUNTIME_COMPONENT_MAJORS)) {
      const candidate = runtimeCandidate(instanceDir, component, this.env.platform)
      if (candidate && existsSync(candidate.path)) {
        found.push({ component, path: candidate.path })
      }
    }
    return found
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
      // A major read from a path is a guess, and a stale one: a component's name
      // does not change when the Java version behind it does, so the map above
      // calling java-runtime-epsilon 21 was wrong the moment Mojang shipped 25
      // under it. Whatever is on disk is asked directly instead.
      const major = candidate.onDisk ? await this.majorOf(candidate.path) : candidate.major
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
    const wanted = input.requiredMajor === null ? 'a JDK' : `Java ${input.requiredMajor}`
    return (
      `This instance needs ${wanted} and none was found on this machine. ` +
      `Install ${wanted}, set the path to java in Settings, or let the launcher download ` +
      `Mojang's own runtime — that is what the Launch button does when nothing is found.`
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

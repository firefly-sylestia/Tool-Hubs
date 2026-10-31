import { spawn, type ChildProcess } from 'node:child_process'
import type { LaunchOutcome, LaunchPlan, LogFinding, Profile } from '@shared/types'
import type { Readable } from 'node:stream'
import { inferJavaMajor } from '../minecraft/java'
import { type LaunchTarget, buildLaunchPlan } from '../minecraft/launch-plan'
import { ruleContextFor } from '../minecraft/rules'
import { resolveVersionChain } from '../minecraft/version-json'
import type { AppEnvironment } from './env'
import type { DiagnosticService } from './diagnostic-service'
import type { InstallService } from './install-service'
import type { JavaService } from './java-service'
import type { LogService } from './log-service'
import type { ServerService } from './server-service'
import type { StateStore } from './state-store'
import type { TaskService } from './task-service'

/** How much of the game's own output is kept to explain a launch that failed. */
const OUTPUT_TAIL_LINES = 40
/**
 * Under this, the game cannot have been played: it crashed on the way up. The
 * official launcher treats a quick exit as a failure for the same reason — a
 * session that ends in four seconds is a problem, not someone stopping.
 */
const CRASH_BEFORE_SECONDS = 20

export interface LaunchStatus {
  running: boolean
  profileId: string | null
  pid: number | null
}

export class LaunchService {
  private child: ChildProcess | null = null

  constructor(
    private readonly state: StateStore,
    private readonly env: AppEnvironment,
    private readonly log: LogService,
    private readonly tasks: TaskService,
    private readonly servers: ServerService,
    private readonly java: JavaService,
    private readonly install: InstallService,
    /** The game's own crash reports and log, read when a launch fails. */
    private readonly diagnostics: DiagnosticService
  ) {}

  /**
   * Builds the command without running anything, so the UI can show it.
   *
   * The instance supplies the game directory and the version; the profile only
   * supplies an identity. Java is resolved here rather than assumed to be on
   * PATH, using the version json's required major version to pick between the
   * runtimes the machine has.
   */
  async plan(profileId: string, serverId?: string | null, instanceId?: string | null): Promise<LaunchPlan> {
    const snapshot = this.state.snapshot()
    const profile = snapshot.profiles.find((candidate) => candidate.id === profileId)
    if (!profile) throw new Error(`Unknown profile ${profileId}.`)

    const targetId = instanceId ?? snapshot.selectedInstanceId
    const instance = snapshot.instances.find((candidate) => candidate.id === targetId)
    if (!instance) {
      throw new Error('No instance is selected. Create one, or import an installation you already have.')
    }
    if (!instance.versionId) {
      throw new Error(`Instance "${instance.name}" has no version yet. Install one, then launch.`)
    }

    const resolved = resolveVersionChain(this.env.sharedDir, instance.versionId)
    const component =
      resolved.chain.map((version) => version.javaVersion?.component).find((value) => typeof value === 'string') ??
      null
    // A version json from before 1.14 names no Java at all. Reading it off the
    // release is what keeps 1.12.2 off Java 25, which it cannot start on.
    const requiredMajor = resolved.merged.javaMajorVersion ?? inferJavaMajor(instance.versionId)
    // Nothing installed on this machine is not a dead end: Mojang's own runtime
    // for this version is downloaded, exactly as the official launcher does.
    const java = await this.java.ensure({
      // The shared game folder, so a runtime Mojang's own download provides is
      // fetched once for the launcher rather than once per instance.
      instanceDir: this.env.sharedDir,
      component,
      requiredMajor,
      // This instance's own choice first: 1.12.2 wants Java 8 on a machine whose
      // other instances want 21, and the launcher's default cannot serve both.
      explicitPath: instance.javaPath ?? snapshot.settings.javaPath,
      onProgress: (progress, message) => this.log.append('stdout', `${Math.round(progress * 100)}% ${message}`)
    })

    return buildLaunchPlan({
      // The instance's folder is where mods, worlds and options live; the
      // version's own files come from the launcher's shared game folder.
      gameDir: instance.dir,
      sharedDir: this.env.sharedDir,
      instanceId: instance.id,
      profile,
      versionId: instance.versionId,
      settings: snapshot.settings,
      connect: this.resolveTarget(serverId ?? snapshot.pendingServerId),
      ctx: ruleContextFor(this.env.platform, this.env.arch),
      launcherName: this.env.launcherName,
      launcherVersion: this.env.launcherVersion,
      javaPath: java.path,
      extraJvmArgs: instance.jvmArgs ?? [],
      maxMemoryMb: instance.maxMemoryMb ?? null
    })
  }

  async start(
    profileId: string,
    serverId?: string | null,
    instanceId?: string | null
  ): Promise<{ plan: LaunchPlan; pid: number }> {
    if (this.child) throw new Error('A game process is already running. Stop it before launching again.')

    return this.tasks.run('Launch', async (report) => {
      report(0.1, 'Checking the installation')
      const instanceIdToCheck = instanceId ?? this.state.snapshot().selectedInstanceId
      const completion = await this.install.complete({
        instanceId: instanceIdToCheck,
        report: (progress, message) => report(0.1 + progress * 0.5, message)
      })
      if (completion.changed) {
        this.log.append(
          'stdout',
          `Completed ${completion.versionId}: ${completion.refreshed} file(s) put right, ` +
            `${formatMegabytes(completion.bytes)} fetched` +
            (completion.adopted > 0 ? `, ${completion.adopted} reused from another installation` : '') +
            (completion.nativesExtracted > 0
              ? `, ${completion.nativesExtracted} native libraries unpacked`
              : '')
        )
      }

      report(0.6, 'Finding a Java runtime and building the command')
      const plan = await this.plan(profileId, serverId, instanceId)
      const profile = this.requireProfile(profileId)

      report(0.75, `Starting ${plan.javaPath}`)
      this.log.append('stdout', `Launching ${profile.name} on ${plan.versionId} (java: ${plan.javaPath})`)
      if (plan.connect) {
        this.log.append('stdout', `Joining ${plan.connect.host}:${plan.connect.port} after start-up`)
      }

      const pid = await this.spawnPlan(plan)
      report(1, `Running with pid ${pid}`)
      return { plan, pid }
    })
  }

  stop(): { stopped: boolean } {
    if (!this.child) return { stopped: false }
    this.log.append('stdout', 'Stopping the game process')
    this.child.kill('SIGTERM')
    return { stopped: true }
  }

  status(): LaunchStatus {
    return {
      running: this.child !== null,
      profileId: this.state.snapshot().runningProfileId,
      pid: this.child?.pid ?? null
    }
  }

  /**
   * Forgets the last outcome, which is what dismissing the panel on Play means.
   *
   * Only a launch that has already finished can be forgotten: the panel and the
   * running state are different things, and clearing one must never look like
   * stopping the game.
   */
  dismissOutcome(): { cleared: boolean } {
    if (!this.state.snapshot().lastLaunch) return { cleared: false }
    this.state.update((state) => {
      state.lastLaunch = null
    })
    return { cleared: true }
  }

  private requireProfile(profileId: string): Profile {
    const profile = this.state.snapshot().profiles.find((candidate) => candidate.id === profileId)
    if (!profile) throw new Error(`Unknown profile ${profileId}.`)
    return profile
  }

  private resolveTarget(serverId: string | null): LaunchTarget | null {
    if (!serverId) return null
    const server = this.servers.list().find((candidate) => candidate.id === serverId)
    if (!server) throw new Error(`Unknown server ${serverId}.`)
    return { host: server.host, port: server.port }
  }

  private spawnPlan(plan: LaunchPlan): Promise<number> {
    return new Promise<number>((resolve, reject) => {
      const startedAt = new Date()
      // The game's own output, kept as it arrives, so a launch that dies on the
      // way up can be quoted instead of leaving an empty window behind.
      const tail: string[] = []
      const keep = (line: string): void => {
        tail.push(line)
        if (tail.length > OUTPUT_TAIL_LINES) tail.shift()
      }

      const child = spawn(plan.javaPath, plan.args, {
        cwd: plan.cwd,
        env: process.env,
        stdio: ['ignore', 'pipe', 'pipe']
      })
      this.child = child

      child.once('error', (error) => {
        this.child = null
        reject(new Error(explainSpawnFailure(error, plan)))
      })

      child.once('spawn', () => {
        this.state.update((state) => {
          state.runningProfileId = plan.profileId
        })
        resolve(child.pid ?? -1)
      })

      if (child.stdout) {
        pipeLines(child.stdout, (line) => {
          keep(line)
          this.log.append('stdout', line)
        })
      }
      if (child.stderr) {
        pipeLines(child.stderr, (line) => {
          keep(line)
          this.log.append('stderr', line)
        })
      }

      child.once('exit', (code, signal) => {
        this.child = null
        const finishedAt = new Date()
        const seconds = Math.max(
          0,
          Math.round((finishedAt.getTime() - startedAt.getTime()) / 1000)
        )
        const detail = signal ? `signal ${signal}` : `exit code ${code ?? 'unknown'}`
        // A game that exited on its own before anyone could play it failed, and
        // saying so is the whole point of keeping this. A long session that was
        // closed is not a failure, whatever the code says.
        const failed = signal !== null || (code ?? 0) !== 0 || seconds < CRASH_BEFORE_SECONDS
        this.log.append(
          failed ? 'stderr' : 'stdout',
          `Game process finished (${detail}) after ${seconds}s`
        )

        let findings: LogFinding[] = []
        let logPath = ''
        if (failed && plan.instanceId) {
          try {
            const report = this.diagnostics.report({ instanceId: plan.instanceId })
            findings = report.findings
            logPath = report.logPath
          } catch (error) {
            // An instance removed while its game was running has nothing left to
            // read; the outcome is still worth recording without it.
            this.log.append('stderr', `Could not read the game's own log: ${(error as Error).message}`)
          }
        }
        if (findings.length > 0) {
          this.log.append('stderr', `What the game reported: ${findings.map((found) => found.title).join('; ')}`)
        }

        this.state.update((state) => {
          state.runningProfileId = null
          const profile = state.profiles.find((candidate) => candidate.id === plan.profileId)
          if (profile) profile.lastPlayedAt = finishedAt.toISOString()
          const instance = state.instances.find((candidate) => candidate.id === plan.instanceId)
          if (instance) instance.lastPlayedAt = finishedAt.toISOString()
          const outcome: LaunchOutcome = {
            instanceId: plan.instanceId ?? '',
            instanceName: instance?.name ?? 'the instance',
            profileName: profile?.name ?? 'the profile',
            versionId: plan.versionId,
            startedAt: startedAt.toISOString(),
            finishedAt: finishedAt.toISOString(),
            seconds,
            exitCode: code,
            signal,
            failed,
            tail,
            findings,
            logPath
          }
          state.lastLaunch = outcome
        })
      })
    })
  }
}

function formatMegabytes(bytes: number): string {
  if (bytes <= 0) return '0 MB'
  return `${(bytes / 1048576).toFixed(1)} MB`
}

function explainSpawnFailure(error: Error, plan: LaunchPlan): string {
  const code = (error as NodeJS.ErrnoException).code
  if (code === 'ENOENT') {
    // A resolved runtime that answered a moment ago and is gone now, or a plan
    // that was built with no java path at all and fell back to the bare name.
    const searched =
      plan.javaPath.includes('/') || plan.javaPath.includes('\\')
        ? 'It was found and then could not be started, so it may have been moved or removed.'
        : 'It is not on PATH.'
    return (
      `Java could not be started at "${plan.javaPath}". ${searched} ` +
      `Set the path to java in Settings, or install a JDK 17 or newer.`
    )
  }
  return `Java could not be started: ${error.message}`
}

/**
 * Splits a process stream into lines. A partial trailing line is held until the
 * newline arrives so the log shows whole lines only.
 */
export function pipeLines(stream: Readable, onLine: (line: string) => void): void {
  let buffer = ''
  stream.setEncoding('utf8')
  stream.on('data', (chunk: string) => {
    buffer += chunk
    const parts = buffer.split(/\r?\n/)
    buffer = parts.pop() ?? ''
    for (const part of parts) {
      if (part.length > 0) onLine(part)
    }
  })
  stream.on('end', () => {
    if (buffer.length > 0) onLine(buffer)
    buffer = ''
  })
}

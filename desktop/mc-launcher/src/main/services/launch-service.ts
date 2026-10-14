import { spawn, type ChildProcess } from 'node:child_process'
import type { LaunchPlan, Profile } from '@shared/types'
import type { Readable } from 'node:stream'
import { type LaunchTarget, buildLaunchPlan } from '../minecraft/launch-plan'
import { ruleContextFor } from '../minecraft/rules'
import { resolveVersionChain } from '../minecraft/version-json'
import type { AppEnvironment } from './env'
import type { JavaService } from './java-service'
import type { LogService } from './log-service'
import type { ServerService } from './server-service'
import type { StateStore } from './state-store'
import type { TaskService } from './task-service'

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
    private readonly java: JavaService
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

    const resolved = resolveVersionChain(instance.dir, instance.versionId)
    const java = await this.java.resolve({
      instanceDir: instance.dir,
      component:
        resolved.chain.map((version) => version.javaVersion?.component).find((value) => typeof value === 'string') ??
        null,
      requiredMajor: resolved.merged.javaMajorVersion,
      explicitPath: snapshot.settings.javaPath
    })

    return buildLaunchPlan({
      gameDir: instance.dir,
      instanceId: instance.id,
      profile,
      versionId: instance.versionId,
      settings: snapshot.settings,
      connect: this.resolveTarget(serverId ?? snapshot.pendingServerId),
      ctx: ruleContextFor(this.env.platform, this.env.arch),
      launcherName: this.env.launcherName,
      launcherVersion: this.env.launcherVersion,
      javaPath: java.path
    })
  }

  async start(
    profileId: string,
    serverId?: string | null,
    instanceId?: string | null
  ): Promise<{ plan: LaunchPlan; pid: number }> {
    if (this.child) throw new Error('A game process is already running. Stop it before launching again.')

    return this.tasks.run('Launch', async (report) => {
      report(0.2, 'Finding a Java runtime and building the command')
      const plan = await this.plan(profileId, serverId, instanceId)
      const profile = this.requireProfile(profileId)

      report(0.5, `Starting ${plan.javaPath}`)
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

      if (child.stdout) pipeLines(child.stdout, (line) => this.log.append('stdout', line))
      if (child.stderr) pipeLines(child.stderr, (line) => this.log.append('stderr', line))

      child.once('exit', (code, signal) => {
        this.child = null
        const detail = signal ? `signal ${signal}` : `exit code ${code ?? 'unknown'}`
        this.log.append('stdout', `Game process finished (${detail})`)
        this.state.update((state) => {
          state.runningProfileId = null
          const profile = state.profiles.find((candidate) => candidate.id === plan.profileId)
          if (profile) profile.lastPlayedAt = new Date().toISOString()
          const instance = state.instances.find((candidate) => candidate.id === plan.instanceId)
          if (instance) instance.lastPlayedAt = new Date().toISOString()
        })
      })
    })
  }
}

function explainSpawnFailure(error: Error, plan: LaunchPlan): string {
  const code = (error as NodeJS.ErrnoException).code
  if (code === 'ENOENT') {
    return (
      `Java could not be started at "${plan.javaPath}", even though it answered earlier. ` +
      `Set an explicit java path in Settings, or install a JDK 17 or newer.`
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

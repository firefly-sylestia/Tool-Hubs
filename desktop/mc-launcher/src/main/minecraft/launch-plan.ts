import { existsSync } from 'node:fs'
import { delimiter, join } from 'node:path'
import type { LaunchPlan, LauncherSettings, Profile } from '@shared/types'
import type { ArgumentValues } from './arguments'
import { flattenArguments, splitLegacyArguments, substituteAll, unresolvedPlaceholders } from './arguments'
import { classpathString, resolveLibraries } from './classpath'
import type { RuleContext } from './rules'
import { resolveVersionChain } from './version-json'

export interface LaunchTarget {
  host: string
  port: number
}

export interface LaunchPlanInput {
  gameDir: string
  profile: Profile
  versionId: string
  settings: LauncherSettings
  connect: LaunchTarget | null
  ctx: RuleContext
  launcherName?: string
  launcherVersion?: string
  /** The instance this launch belongs to, recorded on the plan for the log. */
  instanceId?: string | null
  /**
   * The java executable to run, already resolved by the launcher. Falling back
   * to `settings.javaPath` or the bare name `java` is only for callers that
   * have not looked for a runtime themselves.
   */
  javaPath?: string | null
  /**
   * Extra JVM arguments for this instance, from its own settings. They are
   * added after the version json's arguments and before the memory limit, so an
   * explicit memory setting still wins.
   */
  extraJvmArgs?: string[]
  /**
   * The instance's own `-Xmx`, when it has one. Falling back to
   * `settings.maxMemoryMb` is what "use the launcher's default" means.
   */
  maxMemoryMb?: number | null
}

/**
 * The version that introduced the quick-play join arguments.
 */
export function supportsQuickPlay(versionId: string): boolean {
  const match = versionId.match(/^(\d+)\.(\d+)/)
  if (!match) return false
  const major = Number(match[1])
  const minor = Number(match[2])
  return major > 1 || (major === 1 && minor >= 20)
}

/**
 * Builds the full java command for a local profile running an installation that
 * already exists on disk.
 *
 * No session is created here. The game receives a local identity (`--username`
 * and the offline UUID) and a placeholder access token; servers configured with
 * `online-mode=false` do not validate it, and online-mode servers will reject
 * the join, which is expected.
 */
export function buildLaunchPlan(input: LaunchPlanInput): LaunchPlan {
  const { gameDir, profile, versionId, settings, connect, ctx } = input
  const resolved = resolveVersionChain(gameDir, versionId)
  const merged = resolved.merged

  if (!merged.mainClass) {
    throw new Error(`Version ${versionId} declares no main class; the installation looks incomplete.`)
  }
  if (!merged.clientJar) {
    throw new Error(
      `No client jar found for ${versionId} or anything it inherits from. ` +
        `Expected a file such as ${join(resolved.dir, `${versionId}.jar`)}. ` +
        `Install this version from the Instances page, or complete it with the ` +
        `official launcher, then rescan.`
    )
  }

  const librariesDir = join(gameDir, 'libraries')
  const assetsDir = join(gameDir, 'assets')
  const { classpath, natives } = resolveLibraries(merged.libraries, ctx)

  const nativesDir = join(resolved.dir, `${versionId}-natives`)
  if (natives.length > 0 && !existsSync(nativesDir)) {
    throw new Error(
      `Version ${versionId} needs native libraries, but ${nativesDir} does not exist. ` +
        `Install this version from the Instances page — the installer unpacks ` +
        `natives — or run the version once with the official launcher, then rescan.`
    )
  }
  const useNativesDir = existsSync(nativesDir) ? nativesDir : null

  const values: ArgumentValues = {
    auth_player_name: profile.name,
    // No session exists for a local profile; offline-mode servers ignore this.
    auth_access_token: '0',
    auth_session: '0',
    auth_uuid: profile.uuid.replace(/-/g, ''),
    auth_xuid: '',
    clientid: '',
    user_type: 'legacy',
    user_properties: '[]',
    version_name: versionId,
    version_type: 'release',
    game_directory: gameDir,
    assets_root: assetsDir,
    game_assets: assetsDir,
    assets_index_name: merged.assetsIndex ?? '',
    natives_directory: useNativesDir ?? '',
    library_directory: librariesDir,
    classpath_separator: delimiter,
    classpath: classpathString(classpath, librariesDir, merged.clientJar, delimiter),
    launcher_name: input.launcherName ?? 'mc-launcher',
    launcher_version: input.launcherVersion ?? '0.1.0',
    resolution_width: String(settings.width),
    resolution_height: String(settings.height)
  }

  const jvmArgs = merged.jvmArguments.length > 0 ? flattenArguments(merged.jvmArguments, ctx) : []
  const gameArgs =
    merged.gameArguments.length > 0
      ? flattenArguments(merged.gameArguments, ctx)
      : merged.minecraftArguments
        ? splitLegacyArguments(merged.minecraftArguments)
        : []

  if (gameArgs.length === 0) {
    throw new Error(`Version ${versionId} declares no game arguments; the installation looks incomplete.`)
  }

  const resolvedGameArgs = substituteAll(gameArgs, values)
  if (connect) {
    const address = `${connect.host}:${connect.port}`
    if (supportsQuickPlay(versionId)) {
      resolvedGameArgs.push('--quickPlayMultiplayer', address)
    } else {
      resolvedGameArgs.push('--server', connect.host, '--port', String(connect.port))
    }
  }

  const resolvedJvmArgs = substituteAll(jvmArgs, values)
  const hasClasspathFlag = resolvedJvmArgs.includes('-cp') || resolvedJvmArgs.includes('-classpath')
  const hasLibraryPath = resolvedJvmArgs.some((arg) => arg.startsWith('-Djava.library.path='))

  if (useNativesDir && !hasLibraryPath) {
    resolvedJvmArgs.push(`-Djava.library.path=${useNativesDir}`)
  }
  for (const extra of input.extraJvmArgs ?? []) {
    if (extra.trim() !== '') resolvedJvmArgs.push(extra.trim())
  }
  // Appended last so an explicit setting wins over a value baked into the
  // version json, and over this instance's own arguments. The instance's own
  // limit wins over the launcher's default, which is the point of having one.
  const maxMemoryMb = input.maxMemoryMb ?? settings.maxMemoryMb
  resolvedJvmArgs.push(`-Xmx${maxMemoryMb}m`)
  if (!hasClasspathFlag) {
    resolvedJvmArgs.push('-cp', values.classpath)
  }

  const args = [...resolvedJvmArgs, merged.mainClass, ...resolvedGameArgs]
  const unresolved = unresolvedPlaceholders(args)
  if (unresolved.length > 0) {
    throw new Error(
      `Version ${versionId} uses launch placeholders this launcher does not provide: ${unresolved.join(', ')}`
    )
  }

  return {
    profileId: profile.id,
    instanceId: input.instanceId ?? null,
    versionId,
    javaPath:
      input.javaPath && input.javaPath.trim() !== ''
        ? input.javaPath
        : settings.javaPath && settings.javaPath.trim() !== ''
          ? settings.javaPath
          : 'java',
    args,
    cwd: gameDir,
    connect
  }
}

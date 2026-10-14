/**
 * End-to-end check of the installer against the live metadata services:
 *   npm run netcheck
 *
 * `selfcheck` proves the logic with fakes; this proves the URLs, the parsing
 * and the download path against Mojang's and Fabric's real endpoints, by
 * fetching a real version json and downloading real files with real hashes.
 *
 * It needs the network, so it is a diagnostic and not part of `npm run verify`,
 * and it keeps its downloads small — the client jar is the one big file, and
 * `--fast` skips it.
 *
 * `--launch` adds the one check that cannot be small: it installs a whole real
 * version, hides the machine's own Java so the runtime has to be downloaded, and
 * then starts the game with the command the launcher builds. That is a few
 * hundred megabytes and a couple of minutes, which is why it is opt-in, and why
 * CI does not run it.
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { downloadAll, fetchJson } from '../src/main/minecraft/download'
import { buildLaunchPlan } from '../src/main/minecraft/launch-plan'
import { offlineUuid } from '../src/main/minecraft/offline-uuid'
import { resolveVersionChain } from '../src/main/minecraft/version-json'
import { nativesDirFor } from '../src/main/minecraft/install-plan'
import { InstallationService } from '../src/main/services/installation-service'
import { InstallService } from '../src/main/services/install-service'
import { InstanceService } from '../src/main/services/instance-service'
import { ServiceRegistry } from '../src/main/services/registry'
import { ServerService } from '../src/main/services/server-service'
import { describeStatus } from '../src/shared/server-text'
import { SettingsService } from '../src/main/services/settings-service'
import { StateStore, defaultState } from '../src/main/services/state-store'
import { TaskService } from '../src/main/services/task-service'
import { planAssetObjects, planVersionFiles } from '../src/main/minecraft/install-plan'
import {
  loaderListUrl,
  loaderProfileUrl,
  parseLoaderProfile,
  parseLoaderVersions
} from '../src/main/minecraft/loader'
import { ruleContextFor } from '../src/main/minecraft/rules'
import {
  VERSION_MANIFEST_URL,
  findManifestVersion,
  parseVersionManifest
} from '../src/main/minecraft/version-manifest'
import type { VersionJson } from '../src/main/minecraft/version-json'
import { extractZip, isNativeLibraryEntry } from '../src/main/minecraft/zip'
import {
  bestVersion,
  parseProjectDetail,
  parseSearchResponse,
  parseVersionList,
  primaryFile,
  projectUrl,
  projectVersionsUrl,
  requiredDependencies,
  searchUrl,
  userAgent
} from '../src/main/minecraft/modrinth'
import { readModMetadataFromFile } from '../src/main/minecraft/mod-metadata'
import { parseJavaMajorVersion } from '../src/main/minecraft/java'
import { JavaService, runVersion } from '../src/main/services/java-service'

const fast = process.argv.includes('--fast')
const launch = process.argv.includes('--launch')
const root = mkdtempSync(join(tmpdir(), 'mc-launcher-netcheck-'))
const failures: string[] = []
let downloaded = 0

function report(step: string, detail: string): void {
  console.log(`  ok   ${step} — ${detail}`)
}

function fail(step: string, error: unknown): void {
  const message = (error as Error).message
  failures.push(`${step}: ${message}`)
  console.log(`  FAIL ${step} — ${message}`)
}

async function step(name: string, run: () => Promise<string>): Promise<void> {
  try {
    report(name, await run())
  } catch (error) {
    fail(name, error)
  }
}

const ctx = ruleContextFor(process.platform, process.arch, {})

/** One asset object the install wrote, so a launch check can delete it again. */
function assetSample(objectsDir: string): string | null {
  try {
    for (const prefix of readdirSync(objectsDir)) {
      for (const name of readdirSync(join(objectsDir, prefix))) {
        return join(objectsDir, prefix, name)
      }
    }
  } catch {
    // No assets at all, which the caller reports as a failure of its own.
  }
  return null
}

interface GameRun {
  code: number | null
  /** True when it was still running when the timeout ran out. */
  timedOut: boolean
  seconds: string
  output: string
  lines: string[]
}

/**
 * Starts the game with the command the launcher built and waits for it to stop.
 *
 * Nothing here is a fake: the java binary, the arguments and the working
 * directory are exactly what `launch.start` hands to `spawn`.
 */
function startGame(
  plan: { javaPath: string; args: string[]; cwd: string },
  timeoutMs = 120_000
): Promise<GameRun> {
  return new Promise((resolve) => {
    const startedAt = Date.now()
    const lines: string[] = []
    let output = ''
    let timedOut = false
    const settle = (code: number | null): void => {
      clearTimeout(timer)
      resolve({ code: timedOut ? null : code, timedOut, seconds: ((Date.now() - startedAt) / 1000).toFixed(0), output, lines })
    }
    const collect = (chunk: Buffer): void => {
      const text = chunk.toString('utf8')
      output += text
      for (const line of text.split(/\r?\n/)) {
        if (line.trim() !== '') lines.push(line.trim())
      }
    }

    const child = spawn(plan.javaPath, plan.args, { cwd: plan.cwd, stdio: ['ignore', 'pipe', 'pipe'] })
    child.stdout?.on('data', collect)
    child.stderr?.on('data', collect)
    const timer = setTimeout(() => {
      timedOut = true
      child.kill('SIGKILL')
    }, timeoutMs)

    child.once('error', (error) => {
      lines.push(error.message)
      output += error.message
      settle(null)
    })
    child.once('exit', (code) => settle(code))
  })
}

/** The line a marker was found on, so a failure says what actually happened. */
function firstLineWith(text: string, marker: string): string {
  const needle = marker.toLowerCase()
  const line = text.split(/\r?\n/).find((candidate) => candidate.toLowerCase().includes(needle)) ?? ''
  return line.trim().slice(0, 200)
}

await step('read the version manifest', async () => {
  const manifest = parseVersionManifest(await fetchJson<unknown>(VERSION_MANIFEST_URL))
  const release = manifest.latest.release
  if (!release) throw new Error('the manifest names no latest release')
  if (!findManifestVersion(manifest, release)) throw new Error(`${release} is not in the manifest's own list`)
  return `${manifest.versions.length} versions, latest release ${release}`
})

const manifest = parseVersionManifest(await fetchJson<unknown>(VERSION_MANIFEST_URL))
const releaseId = manifest.latest.release!
const entry = findManifestVersion(manifest, releaseId)!
const versionJson = await fetchJson<VersionJson>(entry.url)
const plan = planVersionFiles(root, versionJson, ctx)
const assetIndexUrl = versionJson.assetIndex?.url

console.log(
  `  --  ${releaseId}: ${versionJson.libraries?.length ?? 0} libraries declared, ` +
    `${plan.files.length} files allowed on ${ctx.os}/${ctx.arch}, ${plan.nativeJars.length} native jars`
)

await step('fetch the asset index and plan its objects', async () => {
  if (!assetIndexUrl) throw new Error('the version json declares no asset index')
  const index = await fetchJson<{ objects: Record<string, { hash: string; size: number }> }>(assetIndexUrl)
  const objects = planAssetObjects(root, index)
  if (objects.length === 0) throw new Error('the asset index lists no objects')
  return `${objects.length} objects, e.g. ${objects[0].dest.slice(root.length + 1)}`
})

await step('download real libraries and assets, verifying every hash', async () => {
  const index = await fetchJson<{ objects: Record<string, { hash: string; size: number }> }>(assetIndexUrl!)
  const small = <T extends { size?: number }>(files: T[]): T[] =>
    files.filter((file) => (file.size ?? 0) < 400_000).slice(0, 3)
  const batch = [...small(plan.files), ...small(planAssetObjects(root, index))]
  if (batch.length < 2) throw new Error('found nothing small enough to fetch')

  const result = await downloadAll(
    batch.map((file) => ({ ...file, dest: file.dest })),
    { onProgress: () => {} }
  )
  downloaded += result.bytes
  return `${result.downloaded} files, ${(result.bytes / 1024).toFixed(0)} KB, all hashes verified`
})

await step('download and unpack a real native library', async () => {
  // Modern versions put their natives on the classpath directly and declare no
  // `natives` classifiers at all, so a version from the era that did is what
  // actually exercises unpacking.
  const legacyId = ['1.16.5', '1.12.2', '1.7.10'].find((id) => findManifestVersion(manifest, id))
  if (!legacyId) return 'skipped: no version from that era is in the manifest'
  const legacyPlan = planVersionFiles(
    root,
    await fetchJson<VersionJson>(findManifestVersion(manifest, legacyId)!.url),
    ctx
  )
  const jar = legacyPlan.nativeJars[0]
  if (!jar) throw new Error(`${legacyId} declares no native jars on ${ctx.os}/${ctx.arch} either`)
  const result = await downloadAll(
    [{ url: jar.url, dest: jar.path, sha1: jar.sha1, size: jar.size, label: jar.label }],
    {}
  )
  downloaded += result.bytes
  const nativesDir = join(root, 'natives')
  const written = extractZip(readFileSync(jar.path), nativesDir, {
    exclude: jar.exclude,
    filter: (name) => isNativeLibraryEntry(name, ctx.os)
  })
  if (written === 0) throw new Error(`${jar.label} held no ${ctx.os} libraries`)
  return `${written} libraries unpacked from ${legacyId}'s ${jar.label}`
})

await step('download the client jar', async () => {
  if (fast) return 'skipped (--fast)'
  const client = plan.files.find((file) => file.label.endsWith('client jar'))
  if (!client) throw new Error('the plan contains no client jar')
  const result = await downloadAll([client], {})
  downloaded += result.bytes
  return `${(statSync(client.dest).size / 1048576).toFixed(1)} MB at ${client.dest}`
})

await step('read Fabric metadata and install its profile', async () => {
  const loaders = parseLoaderVersions(await fetchJson<unknown>(loaderListUrl('fabric')))
  const newest = loaders.find((loader) => loader.stable)
  if (!newest) throw new Error('Fabric lists no stable loader')
  const profile = parseLoaderProfile(
    await fetchJson<unknown>(loaderProfileUrl('fabric', releaseId, newest.version)),
    { kind: 'fabric', gameVersion: releaseId, loaderVersion: newest.version }
  )
  const loaderPlan = planVersionFiles(root, profile, ctx)
  if (loaderPlan.files.length === 0) throw new Error('the Fabric profile plans no files')
  const smallest = loaderPlan.files.reduce((a, b) => ((a.size ?? 0) <= (b.size ?? 0) ? a : b))
  const result = await downloadAll([smallest], {})
  downloaded += result.bytes
  return `${profile.id}: ${loaderPlan.files.length} files, fetched ${smallest.label}`
})

await step('read Quilt metadata and build its profile', async () => {
  const loaders = parseLoaderVersions(await fetchJson<unknown>(loaderListUrl('quilt')))
  const newest = loaders[0]
  const profile = parseLoaderProfile(
    await fetchJson<unknown>(loaderProfileUrl('quilt', releaseId, newest.version)),
    { kind: 'quilt', gameVersion: releaseId, loaderVersion: newest.version }
  )
  return `${profile.id} builds against ${releaseId}`
})

await step('search Modrinth for a mod that fits a real version', async () => {
  const headers = { 'User-Agent': userAgent('mc-launcher', 'netcheck') }
  const search = parseSearchResponse(
    await fetchJson<unknown>(
      searchUrl({ query: 'sodium', kind: 'mod', gameVersion: releaseId, loader: 'fabric', limit: 5 }),
      { headers }
    )
  )
  if (search.hits.length === 0) throw new Error(`no results for sodium on ${releaseId}`)

  const hit = search.hits[0]
  const versions = parseVersionList(
    await fetchJson<unknown>(
      projectVersionsUrl({ projectIdOrSlug: hit.projectId, gameVersion: releaseId, loader: 'fabric' }),
      { headers }
    )
  )
  const best = bestVersion(versions, { gameVersion: releaseId, loader: 'fabric' })
  if (!best) throw new Error(`${hit.title} has no version for ${releaseId}`)
  const file = primaryFile(best)
  if (!file) throw new Error(`${best.name} lists no file`)
  if (!file.sha1 && !file.sha512) throw new Error(`${file.filename} arrives with no hash to check`)

  const result = await downloadAll([{ url: file.url, dest: join(root, file.filename), sha1: file.sha1, sha512: file.sha512, size: file.size }], {})
  downloaded += result.bytes
  const metadata = readModMetadataFromFile(join(root, file.filename))
  return (
    `${hit.title} ${best.versionNumber} (${hit.downloads.toLocaleString()} downloads) — ` +
    `${file.filename} verified, reads as ${metadata?.name ?? 'no metadata'}`
  )
})

await step('resolve a required dependency chain the way an install does', async () => {
  const headers = { 'User-Agent': userAgent('mc-launcher', 'netcheck') }
  const versions = parseVersionList(
    await fetchJson<unknown>(
      projectVersionsUrl({ projectIdOrSlug: 'fabric-api', gameVersion: releaseId, loader: 'fabric' }),
      { headers }
    )
  )
  const best = bestVersion(versions, { gameVersion: releaseId, loader: 'fabric' })
  if (!best) throw new Error(`Fabric API has no version for ${releaseId}`)
  const required = requiredDependencies(best, { installedProjectIds: [] })
  return `${best.name}: ${required.length} required dependency(ies)` + (required[0]?.projectId ? ` (e.g. ${required[0].projectId})` : '')
})

await step('ping a real server the way the server list does', async () => {
  // The two packets every server list speaks, against a server that is up more
  // often than not. A refusal here is the server's, not the protocol's, so the
  // check reports what it answered rather than only pass or fail.
  const registry = new ServiceRegistry()
  const pingRoot = join(root, 'ping')
  mkdirSync(pingRoot, { recursive: true })
  const pingEnv = {
    userDataDir: pingRoot,
    defaultInstancesDir: join(pingRoot, 'instances'),
    defaultGameDir: join(pingRoot, 'game'),
    launcherName: 'mc-launcher',
    launcherVersion: 'netcheck',
    platform: process.platform,
    arch: process.arch
  }
  const store = new StateStore(join(pingRoot, 'state.json'), defaultState(pingEnv.defaultInstancesDir), registry)
  const pingInstances = new InstanceService(
    store,
    pingEnv,
    new InstallationService(store, () => {}),
    new SettingsService(store),
    () => {}
  )
  const pingServers = new ServerService(store, pingInstances, () => {})
  const status = await pingServers.ping({ host: 'mc.hypixel.net', port: 25565 }, 8000)
  if (!status.online) return `mc.hypixel.net is not answering right now: ${status.error}`
  if (!status.version && !status.players) throw new Error(`a status with neither a version nor players: ${JSON.stringify(status)}`)
  return `mc.hypixel.net answered in ${status.latencyMs} ms — ${describeStatus(status)}`
})

await step('read a project page the way the store shows it', async () => {
  const headers = { 'User-Agent': userAgent('mc-launcher', 'netcheck') }
  const detail = parseProjectDetail(await fetchJson<unknown>(projectUrl('sodium'), { headers }))
  if (!detail) throw new Error('Modrinth returned a project page that could not be read')
  if (detail.body.length === 0) throw new Error(`${detail.title} has no description body`)
  return (
    `${detail.title} (${detail.slug}, ${detail.projectType}) — ${detail.categories.length} categories, ` +
    `${detail.license ?? 'no licence'}, ${detail.galleryUrls.length} gallery image(s), ` +
    `${detail.links.map((link) => link.label).join('/') || 'no links'}`
  )
})

await step("download Mojang's own Java runtime and run it", async () => {
  const ce = new JavaService(
    {
      userDataDir: root,
      defaultInstancesDir: join(root, 'instances'),
      defaultGameDir: join(root, 'game'),
      launcherName: 'mc-launcher',
      launcherVersion: 'netcheck',
      platform: process.platform,
      arch: process.arch
    },
    () => {}
  )
  const instanceDir = join(root, 'java-instance')
  const installed = await ce.installRuntime({ instanceDir, component: 'java-runtime-epsilon' })
  const output = await runVersion(installed.path)
  if (output === null) throw new Error(`the downloaded runtime at ${installed.path} did not answer`)
  const major = parseJavaMajorVersion(output)
  if (major === null) throw new Error(`could not read a version out of: ${output.split('\n')[0]}`)
  downloaded += installed.bytes
  const again = await ce.resolve({ instanceDir, component: 'java-runtime-epsilon', requiredMajor: major })
  if (!again.path.includes('runtime')) throw new Error(`the resolver did not pick the runtime it installed: ${again.path}`)
  return (
    `Java ${major} from ${installed.files} files (${(installed.bytes / 1048576).toFixed(0)} MB), ` +
    `runs, and is then found automatically`
  )
})

if (launch) {
  await step('install a whole real version, repair it, and start it', async () => {
    const launchRoot = join(root, 'launch')
    const instanceDir = join(launchRoot, 'instance')
    mkdirSync(instanceDir, { recursive: true })

    // The same graph the app builds, so this is the install the app would do.
    const registry = new ServiceRegistry()
    const launchEnv = {
      userDataDir: launchRoot,
      defaultInstancesDir: join(launchRoot, 'instances'),
      defaultGameDir: instanceDir,
      launcherName: 'mc-launcher',
      launcherVersion: 'netcheck',
      platform: process.platform,
      arch: process.arch
    }
    const store = new StateStore(
      join(launchRoot, 'state.json'),
      defaultState(launchEnv.defaultInstancesDir),
      registry
    )
    const installations = new InstallationService(store, () => {})
    const instances = new InstanceService(store, launchEnv, installations, new SettingsService(store), () => {})
    const tasks = new TaskService(store, registry)
    const installer = new InstallService(launchEnv, tasks, installations, instances, () => {})
    const instance = instances.create({ name: 'Launch check', dir: instanceDir, source: 'created' })

    // A version that still ships natives, still spells its arguments the legacy
    // way, and does not name its Java: the three things most likely to be skipped
    // by a launcher that runs one modern version and calls it done.
    const versionId = ['1.12.2', '1.16.5', '1.20.1'].find((id) => findManifestVersion(manifest, id))
    if (!versionId) throw new Error('the manifest has no version from that era any more')
    const installed = await installer.install({ versionId, loader: 'vanilla', instanceId: instance.id })
    downloaded += installed.bytes
    // A fresh game directory has nothing to reuse, so anything counted as reused
    // would be a file the installer decided not to check.
    if (installed.reused !== 0) {
      throw new Error(`${installed.reused} file(s) were called reused in an empty game directory`)
    }
    if (installed.files < 100) throw new Error(`only ${installed.files} files were planned for ${versionId}`)

    // What a launch sends when the machine has been tidied up: a jar deleted to
    // free space, an asset lost to a half-finished copy. The launcher is expected
    // to notice and put them back rather than fail.
    const resolved = resolveVersionChain(instanceDir, installed.targetId)
    const clientJar = resolved.merged.clientJar
    if (!clientJar) throw new Error('the installed version has no client jar to delete')
    const assetIndex = resolved.merged.assetsIndex ?? 'legacy'
    const assetDir = join(instanceDir, 'assets', 'objects')
    const asset = assetSample(assetDir)
    rmSync(clientJar, { force: true })
    rmSync(nativesDirFor(instanceDir, installed.targetId), { recursive: true, force: true })
    if (asset) rmSync(asset, { force: true })

    const completion = await installer.complete({ instanceId: instance.id })
    if (!completion.changed) throw new Error('a deleted client jar was not seen as missing')
    if (!existsSync(clientJar)) throw new Error(`the client jar was not fetched again to ${clientJar}`)
    if (asset && !existsSync(asset)) throw new Error('the deleted asset was not fetched again')
    if (completion.nativesExtracted === 0) throw new Error('the deleted natives were not unpacked again')

    // A second pass has nothing to do, which is what keeps a launch from walking
    // the whole tree and re-hashing it.
    const second = await installer.complete({ instanceId: instance.id })
    if (second.changed || second.refreshed !== 0) {
      throw new Error(`a repaired installation was still reported as ${second.refreshed} file(s) short`)
    }

    const javaService = new JavaService(launchEnv, () => {})
    // Pretend this machine has no Java at all. Blanking PATH and JAVA_HOME is
    // what makes the launcher's own download the only runtime that can answer,
    // which is the path a Windows machine with no JDK takes.
    const savedPath = process.env.PATH
    const savedHome = process.env.JAVA_HOME
    process.env.PATH = ''
    delete process.env.JAVA_HOME
    let java: { path: string; major: number | null }
    try {
      java = await javaService.ensure({
        instanceDir,
        component: undefined,
        requiredMajor: resolved.merged.javaMajorVersion,
        onProgress: () => {}
      })
    } finally {
      process.env.PATH = savedPath
      if (savedHome === undefined) delete process.env.JAVA_HOME
      else process.env.JAVA_HOME = savedHome
    }
    const plan = buildLaunchPlan({
      gameDir: instanceDir,
      profile: {
        id: 'netcheck',
        name: 'Netcheck',
        uuid: offlineUuid('Netcheck'),
        createdAt: new Date(0).toISOString(),
        lastPlayedAt: null
      },
      versionId: installed.targetId,
      settings: { instancesDir: launchRoot, javaPath: null, maxMemoryMb: 2048, width: 854, height: 480 },
      connect: null,
      ctx,
      launcherName: 'mc-launcher',
      launcherVersion: 'netcheck',
      javaPath: java.path
    })

    const run = await startGame(plan)
    // Everything that means the launcher got it wrong rather than the machine:
    // a main class the classpath does not have, a class the libraries are
    // missing, a native this launcher never unpacked, a Java too old for the
    // class files it is being handed, or a command line the JVM does not accept.
    const broken = [
      'Could not find or load main class',
      'NoClassDefFoundError',
      'ClassNotFoundException',
      'UnsupportedClassVersionError',
      'ClassFormatError',
      'Error occurred during initialization of VM',
      'Unrecognized option',
      'no lwjgl in java.library.path'
    ].find((marker) => run.output.includes(marker))
    if (broken) {
      throw new Error(`the game's own code did not load — ${firstLineWith(run.output, broken)}`)
    }
    if (run.timedOut) {
      // A game that is still running after two minutes is a game that started.
      return (
        `${versionId} installed (${installed.files} files), repaired after deleting three, ` +
        `Java ${java.major ?? '?'} downloaded with no java on PATH, and the game kept running ` +
        `for ${run.seconds}s (asset index ${assetIndex}, logging ${run.lines.length} lines)`
      )
    }
    // Past that, the furthest this can go is the window itself: a machine with
    // no display stops the game in its graphics code — GLFW with no display, or,
    // as here, the X11 client libraries that LWJGL's own native links against.
    // Either way the game's code ran, which is what this step is for.
    const reached = ['lwjgl', 'glfw', 'display', 'x11', 'x cursor', 'opengl', 'pixel format'].find((marker) =>
      run.output.toLowerCase().includes(marker)
    )
    if (!reached) {
      throw new Error(
        `the JVM stopped before the game's window code ran (exit ${run.code}): ` +
          `${run.lines.slice(-4).join(' | ') || 'no output'}`
      )
    }
    return (
      `${versionId} installed (${installed.files} files), repaired after deleting three, ` +
      `Java ${java.major ?? '?'} downloaded with no java on PATH, and the game reached its ` +
      `window: ${firstLineWith(run.output, reached)} (no display in this environment) · ` +
      `asset index ${assetIndex}`
    )
  })
}

rmSync(root, { recursive: true, force: true })

console.log(
  `\n${failures.length === 0 ? 'all steps passed' : `${failures.length} step(s) failed`} · ` +
    `${(downloaded / 1048576).toFixed(1)} MB downloaded`
)
if (failures.length > 0) {
  for (const failure of failures) console.log(`  - ${failure}`)
  process.exit(1)
}

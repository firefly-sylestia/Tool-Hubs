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
 */
import { mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { downloadAll, fetchJson } from '../src/main/minecraft/download'
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

rmSync(root, { recursive: true, force: true })

console.log(
  `\n${failures.length === 0 ? 'all steps passed' : `${failures.length} step(s) failed`} · ` +
    `${(downloaded / 1048576).toFixed(1)} MB downloaded`
)
if (failures.length > 0) {
  for (const failure of failures) console.log(`  - ${failure}`)
  process.exit(1)
}

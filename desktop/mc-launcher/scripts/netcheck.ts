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

rmSync(root, { recursive: true, force: true })

console.log(
  `\n${failures.length === 0 ? 'all steps passed' : `${failures.length} step(s) failed`} · ` +
    `${(downloaded / 1048576).toFixed(1)} MB downloaded`
)
if (failures.length > 0) {
  for (const failure of failures) console.log(`  - ${failure}`)
  process.exit(1)
}

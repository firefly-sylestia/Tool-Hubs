/**
 * Verification for the launcher's pure logic, runnable under plain Node:
 *   npm run selfcheck
 *
 * It builds a fake installation on disk with the same shapes the game ships,
 * then exercises version resolution, rule evaluation, classpath building,
 * argument substitution, profile/server validation and the state store. No
 * Electron, no network, no game files.
 */
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { createHash } from 'node:crypto'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { deflateRawSync } from 'node:zlib'
import { buildLaunchPlan, supportsQuickPlay } from '../src/main/minecraft/launch-plan'
import { offlineUuid, offlineUuidDashless } from '../src/main/minecraft/offline-uuid'
import { ruleContextFor, rulesAllow } from '../src/main/minecraft/rules'
import { resolveVersionChain, type Library, type VersionJson } from '../src/main/minecraft/version-json'
import { classpathString, mavenPath, resolveLibraries } from '../src/main/minecraft/classpath'
import { flattenArguments, substitute, unresolvedPlaceholders } from '../src/main/minecraft/arguments'
import { downloadAll, downloadFile, isFileValid, sha1OfFile } from '../src/main/minecraft/download'
import {
  mergePlans,
  nativesDirFor,
  planAssetObjects,
  planVersionFiles
} from '../src/main/minecraft/install-plan'
import {
  loaderListUrl,
  loaderProfileUrl,
  parseLoaderProfile,
  parseLoaderVersions
} from '../src/main/minecraft/loader'
import {
  filterManifestVersions,
  findManifestVersion,
  parseVersionManifest
} from '../src/main/minecraft/version-manifest'
import { extractZip, isNativeLibraryEntry, listZipEntries } from '../src/main/minecraft/zip'
import {
  detectModpackFormat,
  instancePathFor,
  parseCurseForgeManifest,
  parseModrinthIndex,
  parseLoaderId
} from '../src/main/minecraft/modpack'
import {
  javaCandidates,
  parseJavaMajorVersion,
  runtimeCandidate,
  selectJava
} from '../src/main/minecraft/java'
import { splitResourceName, resourceFileName, isResourceFile } from '../src/main/minecraft/resources'
import { AtomicJsonStore } from '../src/main/services/atomic-store'
import { InstanceService, slugFor } from '../src/main/services/instance-service'
import { InstallationService } from '../src/main/services/installation-service'
import { InstallService } from '../src/main/services/install-service'
import { ServiceRegistry } from '../src/main/services/registry'
import { StateStore, defaultState, migrateState } from '../src/main/services/state-store'
import { ProfileService } from '../src/main/services/profile-service'
import { ServerService } from '../src/main/services/server-service'
import { SettingsService } from '../src/main/services/settings-service'
import { TaskService } from '../src/main/services/task-service'
import { pipeLines } from '../src/main/services/launch-service'
import type { Profile } from '../src/shared/types'

let passed = 0
const failures: string[] = []

function check(name: string, run: () => void): void {
  try {
    run()
    passed += 1
    console.log(`  ok   ${name}`)
  } catch (error) {
    failures.push(`${name}: ${(error as Error).message}`)
    console.log(`  FAIL ${name}\n       ${(error as Error).message}`)
  }
}

function expect(condition: boolean, message: string): void {
  if (!condition) throw new Error(message)
}

function expectEqual<T>(actual: T, expected: T, label: string): void {
  const a = JSON.stringify(actual)
  const b = JSON.stringify(expected)
  if (a !== b) throw new Error(`${label}: expected ${b}, got ${a}`)
}

async function acheck(name: string, run: () => Promise<void>): Promise<void> {
  try {
    await run()
    passed += 1
    console.log(`  ok   ${name}`)
  } catch (error) {
    failures.push(`${name}: ${(error as Error).message}`)
    console.log(`  FAIL ${name}\n       ${(error as Error).message}`)
  }
}

async function expectRejects(run: () => Promise<unknown>, includes: string): Promise<void> {
  try {
    await run()
  } catch (error) {
    const message = (error as Error).message
    if (!message.includes(includes)) {
      throw new Error(`error message "${message}" does not include "${includes}"`)
    }
    return
  }
  throw new Error(`expected a failure including "${includes}", but nothing was thrown`)
}

function expectThrows(run: () => unknown, includes: string): void {
  try {
    run()
  } catch (error) {
    const message = (error as Error).message
    if (!message.includes(includes)) {
      throw new Error(`error message "${message}" does not include "${includes}"`)
    }
    return
  }
  throw new Error('expected a failure, but the call succeeded')
}

const root = mkdtempSync(join(tmpdir(), 'mc-launcher-check-'))
const gameDir = join(root, 'minecraft')
const stateDir = join(root, 'state')

const vanillaJson = {
  id: '1.21.4',
  type: 'release',
  mainClass: 'net.minecraft.client.main.Main',
  assetIndex: { id: '19' },
  javaVersion: { majorVersion: 21 },
  arguments: {
    game: [
      '--username',
      '${auth_player_name}',
      '--version',
      '${version_name}',
      '--gameDir',
      '${game_directory}',
      '--assetsDir',
      '${assets_root}',
      '--assetIndex',
      '${assets_index_name}',
      '--uuid',
      '${auth_uuid}',
      '--accessToken',
      '${auth_access_token}',
      '--userType',
      '${user_type}',
      '--versionType',
      '${version_type}',
      '--width',
      '${resolution_width}',
      '--height',
      '${resolution_height}'
    ],
    jvm: [
      '-Djava.library.path=${natives_directory}',
      '-Djna.tmpdir=${natives_directory}',
      '-cp',
      '${classpath}',
      '-Dminecraft.launcher.brand=${launcher_name}'
    ]
  },
  libraries: [
    { name: 'org.lwjgl:lwjgl:3.3.3', natives: { linux: 'natives-linux' }, downloads: { artifact: { path: 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar' }, classifiers: { 'natives-linux': { path: 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar' } } } },
    { name: 'org.lwjgl:lwjgl:3.3.3', downloads: {} },
    { name: 'com.mojang:brigadier:1.0.18', downloads: { artifact: { path: 'com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar' } } },
    {
      name: 'windows-only:tool:1.0',
      rules: [{ action: 'allow', os: { name: 'windows' } }],
      downloads: { artifact: { path: 'windows-only/tool/1.0/tool-1.0.jar' } }
    },
    {
      name: 'any-but-osx:tool:2.0',
      rules: [{ action: 'allow' }, { action: 'disallow', os: { name: 'osx' } }],
      downloads: { artifact: { path: 'any-but-osx/tool/2.0/tool-2.0.jar' } }
    }
  ]
}

const forgeJson = {
  id: '1.21.4-forge',
  inheritsFrom: '1.21.4',
  mainClass: 'cpw.mods.bootstraplauncher.BootstrapLauncher',
  arguments: {
    game: ['--launchTarget', 'forge_client'],
    jvm: ['-DlegacyClassPath=ignored']
  },
  libraries: [{ name: 'net.minecraftforge:forge:1.21.4-54.0.0', downloads: { artifact: { path: 'net/minecraftforge/forge/1.21.4-54.0.0/forge-1.21.4-54.0.0.jar' } } }]
}

const legacyJson = {
  id: '1.19.4',
  type: 'release',
  mainClass: 'net.minecraft.client.main.Main',
  assets: '3',
  javaVersion: { majorVersion: 17 },
  arguments: {
    game: ['--username', '${auth_player_name}', '--uuid', '${auth_uuid}', '--accessToken', '${auth_access_token}'],
    jvm: ['-cp', '${classpath}']
  },
  libraries: []
}

function writeVersion(id: string, json: unknown, withJar = true, withNatives = false): void {
  const dir = join(gameDir, 'versions', id)
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, `${id}.json`), JSON.stringify(json, null, 2))
  if (withJar) writeFileSync(join(dir, `${id}.jar`), 'not a real jar')
  if (withNatives) mkdirSync(join(dir, `${id}-natives`), { recursive: true })
}

writeVersion('1.21.4', vanillaJson, true, true)
// A real loader profile gets its own <id>-natives directory from the launcher
// that installed it, even though the native libraries are inherited.
writeVersion('1.21.4-forge', forgeJson, false, true)
writeVersion('1.19.4', legacyJson, true, false)
mkdirSync(join(gameDir, 'libraries', 'org', 'lwjgl', 'lwjgl', '3.3.3'), { recursive: true })
mkdirSync(join(gameDir, 'assets', 'indexes'), { recursive: true })

const ctx = ruleContextFor('linux', 'x64')
const profile: Profile = {
  id: 'profile-1',
  name: 'Steve',
  uuid: offlineUuid('Steve'),
  createdAt: new Date().toISOString(),
  lastPlayedAt: null
}

console.log('\noffline UUID')
check('is an RFC 4122 version 3 UUID', () => {
  const uuid = offlineUuid('Steve')
  expectEqual(uuid.length, 36, 'length')
  expectEqual(uuid.split('-').map((part) => part.length), [8, 4, 4, 4, 12], 'grouping')
  expectEqual(uuid[14], '3', 'version nibble')
  expect(['8', '9', 'a', 'b'].includes(uuid[19]), `variant nibble is ${uuid[19]}`)
})
check('matches the documented MD5 derivation', () => {
  const digest = Uint8Array.from(createHash('md5').update('OfflinePlayer:Steve', 'utf8').digest())
  digest[6] = (digest[6] & 0x0f) | 0x30
  digest[8] = (digest[8] & 0x3f) | 0x80
  const hex = Buffer.from(digest).toString('hex')
  const expected = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  expectEqual(offlineUuid('Steve'), expected, 'uuid')
})
check('is stable and name dependent', () => {
  expectEqual(offlineUuid('Steve'), offlineUuid('Steve'), 'repeat')
  expect(offlineUuid('Steve') !== offlineUuid('Alex'), 'different names must differ')
  expectEqual(offlineUuidDashless('Steve').length, 32, 'dashless length')
})

console.log('\nrules')
check('empty rules include the entry', () => {
  expect(rulesAllow(undefined, ctx), 'undefined rules')
  expect(rulesAllow([], ctx), 'empty rules')
})
check('a windows-only allow rule excludes it on linux', () => {
  expect(!rulesAllow([{ action: 'allow', os: { name: 'windows' } }], ctx), 'windows only')
})
check('the last matching rule wins', () => {
  expect(
    !rulesAllow([{ action: 'allow' }, { action: 'disallow', os: { name: 'linux' } }], ctx),
    'a later disallow must exclude the entry'
  )
  expect(
    rulesAllow([{ action: 'disallow' }, { action: 'allow', os: { name: 'linux' } }], ctx),
    'a later allow must include the entry'
  )
  expect(
    !rulesAllow([{ action: 'allow', os: { name: 'windows' } }, { action: 'disallow' }], ctx),
    'a non-matching allow followed by a matching disallow excludes the entry'
  )
})

console.log('\nversion resolution')
check('reads the chain child first', () => {
  const resolved = resolveVersionChain(gameDir, '1.21.4-forge')
  expectEqual(resolved.chain.map((version) => version.id), ['1.21.4-forge', '1.21.4'], 'chain')
})
check('inherits mainClass, assets and java version when the child omits them', () => {
  const resolved = resolveVersionChain(gameDir, '1.21.4-forge')
  expectEqual(resolved.merged.mainClass, 'cpw.mods.bootstraplauncher.BootstrapLauncher', 'mainClass')
  expectEqual(resolved.merged.assetsIndex, '19', 'assetsIndex')
  expectEqual(resolved.merged.javaMajorVersion, 21, 'javaMajorVersion')
})
check('concatenates libraries child first', () => {
  const resolved = resolveVersionChain(gameDir, '1.21.4-forge')
  expectEqual(resolved.merged.libraries[0].name, 'net.minecraftforge:forge:1.21.4-54.0.0', 'first library')
  expect(resolved.merged.libraries.length === 1 + vanillaJson.libraries.length, 'library count')
})
check('falls back to the parent client jar when the child has none', () => {
  const resolved = resolveVersionChain(gameDir, '1.21.4-forge')
  expectEqual(resolved.merged.clientJar, join(gameDir, 'versions', '1.21.4', '1.21.4.jar'), 'client jar')
})
check('uses the child client jar when it ships one', () => {
  const resolved = resolveVersionChain(gameDir, '1.21.4')
  expectEqual(resolved.merged.clientJar, join(gameDir, 'versions', '1.21.4', '1.21.4.jar'), 'client jar')
})
check('reports a missing version with the expected path', () => {
  expectThrows(() => resolveVersionChain(gameDir, '1.99.9'), 'is not installed')
})

console.log('\nclasspath')
check('maven coordinates convert to a repository path', () => {
  expectEqual(mavenPath('com.mojang:brigadier:1.0.18'), 'com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar', 'artifact')
  expectEqual(
    mavenPath('org.lwjgl:lwjgl:3.3.3:natives-linux'),
    'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar',
    'classifier'
  )
})
check('drops rule-excluded libraries and keeps duplicates out', () => {
  const resolved = resolveVersionChain(gameDir, '1.21.4')
  const { classpath } = resolveLibraries(resolved.merged.libraries, ctx)
  const names = classpath.map((entry) => entry.name)
  expect(!names.includes('windows-only:tool:1.0'), 'windows-only library must be dropped on linux')
  expect(names.includes('any-but-osx:tool:2.0'), 'cross-platform library must be kept')
  expectEqual(names.filter((name) => name === 'org.lwjgl:lwjgl:3.3.3').length, 1, 'duplicate entries')
})
check('separates native libraries from the classpath', () => {
  const resolved = resolveVersionChain(gameDir, '1.21.4')
  const { natives, classpath } = resolveLibraries(resolved.merged.libraries, ctx)
  expectEqual(natives.length, 1, 'natives')
  expectEqual(natives[0].relativePath, 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar', 'native path')
  expect(classpath.every((entry) => !entry.native), 'classpath must not hold natives')
})
check('joins the classpath with the platform separator', () => {
  const resolved = resolveVersionChain(gameDir, '1.21.4')
  const { classpath } = resolveLibraries(resolved.merged.libraries, ctx)
  const joined = classpathString(classpath, '/libraries', '/client.jar', ':')
  expect(joined.startsWith('/client.jar:'), 'the client jar comes first')
  expect(joined.includes('/libraries/com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar'), 'library path')
})

console.log('\narguments')
check('substitutes known placeholders only', () => {
  expectEqual(substitute('--name ${auth_player_name}', { auth_player_name: 'Steve' }), '--name Steve', 'substitution')
  expectEqual(substitute('${nope}', {}), '${nope}', 'unknown placeholder is preserved')
})
check('drops arguments whose rules do not match', () => {
  const values = flattenArguments(
    ['always', { rules: [{ action: 'allow', os: { name: 'windows' } }], value: 'windows-only' }],
    ctx
  )
  expectEqual(values, ['always'], 'flattened')
})
check('reports unresolved placeholders', () => {
  expectEqual(unresolvedPlaceholders(['ok', '--x', '${left}']), ['left'], 'unresolved')
})

console.log('\nlaunch plan')
check('builds a complete offline command', () => {
  const plan = buildLaunchPlan({
    gameDir,
    profile,
    versionId: '1.21.4',
    settings: { instancesDir: gameDir, javaPath: null, maxMemoryMb: 2048, width: 1024, height: 768 },
    connect: null,
    ctx
  })
  expectEqual(plan.javaPath, 'java', 'java path')
  expectEqual(plan.cwd, gameDir, 'cwd')
  expectEqual(unresolvedPlaceholders(plan.args), [], 'no placeholder may survive')
  expect(plan.args.includes('--username'), '--username present')
  expectEqual(plan.args[plan.args.indexOf('--username') + 1], 'Steve', 'username value')
  expectEqual(plan.args[plan.args.indexOf('--uuid') + 1], offlineUuidDashless('Steve'), 'uuid value')
  expectEqual(plan.args[plan.args.indexOf('--accessToken') + 1], '0', 'no session token is invented')
  expectEqual(plan.args[plan.args.indexOf('--userType') + 1], 'legacy', 'user type')
  expectEqual(plan.args[plan.args.indexOf('--width') + 1], '1024', 'width')
  expect(plan.args.includes('-Xmx2048m'), 'memory setting')
  expect(plan.args.includes('net.minecraft.client.main.Main'), 'main class')
  expectEqual(plan.args.slice(-2), ['--height', '768'], 'the game arguments end the command')
  expect(
    plan.args.indexOf('net.minecraft.client.main.Main') > plan.args.indexOf('-cp'),
    'the main class follows the jvm arguments'
  )
  expect(plan.args.some((arg) => arg.startsWith('-Djava.library.path=')), 'library path')
})
check('appends the loader arguments after the inherited ones', () => {
  const plan = buildLaunchPlan({
    gameDir,
    profile,
    versionId: '1.21.4-forge',
    settings: { instancesDir: gameDir, javaPath: '/usr/bin/java', maxMemoryMb: 4096, width: 1280, height: 720 },
    connect: null,
    ctx
  })
  expectEqual(plan.javaPath, '/usr/bin/java', 'explicit java path')
  expect(plan.args.includes('cpw.mods.bootstraplauncher.BootstrapLauncher'), 'loader main class')
  expect(plan.args.includes('forge_client'), 'loader game argument')
})
check('uses quick play for 1.20 and newer, and the legacy flags below', () => {
  expect(supportsQuickPlay('1.21.4'), '1.21.4 supports quick play')
  expect(!supportsQuickPlay('1.19.4'), '1.19.4 does not')
  expect(!supportsQuickPlay('custom-build'), 'unknown ids are treated as legacy')
  const modern = buildLaunchPlan({
    gameDir,
    profile,
    versionId: '1.21.4',
    settings: { instancesDir: gameDir, javaPath: null, maxMemoryMb: 2048, width: 1280, height: 720 },
    connect: { host: '127.0.0.1', port: 25566 },
    ctx
  })
  expectEqual(modern.args.slice(-2), ['--quickPlayMultiplayer', '127.0.0.1:25566'], 'quick play args')
  const legacy = buildLaunchPlan({
    gameDir,
    profile,
    versionId: '1.19.4',
    settings: { instancesDir: gameDir, javaPath: null, maxMemoryMb: 2048, width: 1280, height: 720 },
    connect: { host: '127.0.0.1', port: 25566 },
    ctx
  })
  expectEqual(legacy.args.slice(-4), ['--server', '127.0.0.1', '--port', '25566'], 'legacy join args')
})
check('fails with an actionable message when natives are missing', () => {
  const broken = { ...vanillaJson, id: '1.21.4-nolibs' }
  writeVersion('1.21.4-nolibs', broken, true, false)
  expectThrows(
    () =>
      buildLaunchPlan({
        gameDir,
        profile,
        versionId: '1.21.4-nolibs',
        settings: { instancesDir: gameDir, javaPath: null, maxMemoryMb: 2048, width: 1280, height: 720 },
        connect: null,
        ctx
      }),
    'Install this version from the Installed versions page'
  )
})

console.log('\nlog plumbing')
{
  const seen: string[] = []
  const stream = Readable.from(['first\nsec', 'ond\nthird', '\n'])
  pipeLines(stream, (line) => seen.push(line))
  await new Promise((resolve) => stream.on('end', resolve))
  check('splits a stream into complete lines', () => {
    expectEqual(seen, ['first', 'second', 'third'], 'lines')
  })
}

console.log('\nservices')
const registry = new ServiceRegistry()
const environment = {
  userDataDir: stateDir,
  defaultInstancesDir: join(root, 'instances'),
  defaultGameDir: gameDir,
  launcherName: 'mc-launcher',
  launcherVersion: '0.0.0',
  platform: 'linux' as NodeJS.Platform,
  arch: 'x64'
}
const state = new StateStore(
  join(stateDir, 'launcher-state.json'),
  defaultState(environment.defaultInstancesDir),
  registry
)
const profiles = new ProfileService(state)
const servers = new ServerService(state)
const settings = new SettingsService(state)
const installations = new InstallationService(state, () => {})
const instances = new InstanceService(state, environment, installations, settings, () => {})
const tasks = new TaskService(state, registry)
const installer = new InstallService(environment, tasks, installations, instances, () => {})

console.log('\ninstances')
const instance = instances.create({ name: 'Imported', dir: gameDir, source: 'imported' })
check('an instance owns the directory it plays in', () => {
  expectEqual(instances.selected()?.id, instance.id, 'a new instance is selected')
  expectEqual(instance.dir, gameDir, 'it keeps the directory it was given')
  expectEqual(instance.source, 'imported', 'and remembers where it came from')
  expect(
    existsSync(join(gameDir, 'mods')) && existsSync(join(gameDir, 'shaderpacks')),
    'its resource folders are created'
  )
})
check('names instance folders after the instance without collisions', () => {
  expectEqual(slugFor('My Pack 1.21', []), 'my-pack-1-21', 'slug')
  expectEqual(slugFor('My Pack 1.21', ['my-pack-1-21']), 'my-pack-1-21-2', 'second one')
  expectEqual(slugFor('!!!', []), 'instance', 'nothing usable left')
})
check('refuses duplicate and unusable instance names', () => {
  expectThrows(() => instances.create({ name: 'Imported' }), 'already exists')
  expectThrows(() => instances.create({ name: 'bad/name' }), 'cannot contain')
  expectThrows(() => instances.create({ name: '' }), '1-48 characters')
})

console.log('\nresources')
check('classifies, toggles and refuses the wrong kind of file', () => {
  const mods = join(gameDir, 'mods')
  writeFileSync(join(mods, 'sodium.jar'), 'mod')
  writeFileSync(join(mods, 'old-mod.zip'), 'mod')
  writeFileSync(join(mods, 'notes.txt'), 'not a mod')
  writeFileSync(join(mods, '.DS_Store'), 'not a mod')

  let resources = instances.resources(instance.id)
  expectEqual(resources.entries.mod.map((entry) => entry.title), ['old-mod.zip', 'sodium.jar'], 'only mods are listed')

  resources = instances.setResourceEnabled({ instanceId: instance.id, kind: 'mod', name: 'sodium.jar', enabled: false })
  const disabled = resources.entries.mod.find((entry) => entry.title === 'sodium.jar')!
  expectEqual(disabled.enabled, false, 'it is off')
  expect(existsSync(join(mods, 'sodium.jar.disabled')), 'the rename is what turns it off')
  expectEqual(resources.entries.mod.map((entry) => entry.enabled), [true, false], 'enabled ones sort first')

  resources = instances.setResourceEnabled({
    instanceId: instance.id,
    kind: 'mod',
    name: 'sodium.jar.disabled',
    enabled: true
  })
  expect(existsSync(join(mods, 'sodium.jar')), 'and back on again')
  expect(!existsSync(join(mods, 'sodium.jar.disabled')), 'the suffixed copy is gone')

  const added = instances.addResources({
    instanceId: instance.id,
    kind: 'resourcepack',
    paths: [join(mods, 'sodium.jar'), join(mods, 'old-mod.zip')]
  })
  expectEqual(added.added, ['old-mod.zip'], 'only the zip belongs in resourcepacks')
  expect(added.skipped[0].includes('did you mean to add it to mods'), 'and the jar is refused with the reason')

  const removed = instances.removeResource({ instanceId: instance.id, kind: 'mod', name: 'old-mod.zip' })
  expectEqual(removed.entries.mod.map((entry) => entry.title), ['sodium.jar'], 'removed')
})

console.log('\nstate and services')
check('creates a profile with an offline UUID', () => {
  const created = profiles.create({ name: 'Alex' })
  expectEqual(created.uuid, offlineUuid('Alex'), 'uuid')
  expectEqual(state.snapshot().selectedProfileId, created.id, 'selected profile')
})
check('rejects invalid and duplicate profile names', () => {
  expectThrows(() => profiles.create({ name: 'ab' }), '3-16 characters')
  expectThrows(() => profiles.create({ name: 'bad name' }), '3-16 characters')
  expectThrows(() => profiles.create({ name: 'alex' }), 'already exists')
})
check('regenerates the UUID when the name changes', () => {
  const [first] = profiles.list()
  const updated = profiles.update(first.id, { name: 'AlexTwo' })
  expectEqual(updated.uuid, offlineUuid('AlexTwo'), 'uuid after rename')
})
check('validates server entries', () => {
  const server = servers.add({ name: 'LAN', host: '127.0.0.1', port: 25565 })
  expectEqual(server.port, 25565, 'default port')
  expectThrows(() => servers.add({ name: 'bad', host: 'http://x', port: 25565 }), 'letters, numbers, dots and dashes')
  expectThrows(() => servers.add({ name: 'bad', host: 'x.test', port: 70000 }), 'between 1 and 65535')
  servers.setPending(server.id)
  expectEqual(state.snapshot().pendingServerId, server.id, 'pending server')
})
check('validates settings', () => {
  expectThrows(() => settings.update({ maxMemoryMb: 10 }), 'between 512 and 65536')
  expectThrows(() => settings.update({ instancesDir: 'relative/path' }), 'absolute path')
  const updated = settings.update({ maxMemoryMb: 6144, javaPath: null })
  expectEqual(updated.maxMemoryMb, 6144, 'memory')
})
check('persists state through the store', () => {
  const reopened = new StateStore(join(stateDir, 'launcher-state.json'), defaultState('/tmp/other'), registry)
  expectEqual(reopened.snapshot().profiles.length, profiles.list().length, 'profiles survived')
  expectEqual(reopened.snapshot().settings.maxMemoryMb, 6144, 'settings survived')
  expectEqual(reopened.snapshot().settings.instancesDir, environment.defaultInstancesDir, 'instances dir survived')
  expectEqual(reopened.snapshot().settings.javaPath, null, 'null java path survived')
})
check('falls back to defaults for a corrupt state file', () => {
  const file = join(stateDir, 'corrupt.json')
  writeFileSync(file, '{ this is not json')
  const warnings: string[] = []
  const store = new AtomicJsonStore(file, { ok: true }, (message) => warnings.push(message))
  expectEqual(store.read(), { ok: true }, 'defaults')
  expect(warnings.length === 1, 'a warning is reported')
  expect(readFileSync(`${file}.corrupt`, 'utf8').startsWith('{ this is not json'), 'the bad file is preserved')
})
check('writes state atomically', () => {
  const file = join(stateDir, 'atomic.json')
  const store = new AtomicJsonStore(file, { count: 0 })
  store.write({ count: 7 })
  expectEqual(store.read(), { count: 7 }, 'round trip')
})

/** Builds a zip in memory so the readers can be checked without an archive on disk. */
function buildZip(files: { name: string; contents: string; store?: boolean }[]): Buffer {
  const parts: Buffer[] = []
  const central: Buffer[] = []
  let offset = 0
  for (const file of files) {
    const raw = Buffer.from(file.contents, 'utf8')
    const data = file.store ? raw : deflateRawSync(raw)
    const name = Buffer.from(file.name, 'utf8')
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(file.store ? 0 : 8, 8)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(raw.length, 22)
    local.writeUInt16LE(name.length, 26)
    parts.push(local, name, data)

    const entry = Buffer.alloc(46)
    entry.writeUInt32LE(0x02014b50, 0)
    entry.writeUInt16LE(20, 4)
    entry.writeUInt16LE(20, 6)
    entry.writeUInt16LE(file.store ? 0 : 8, 10)
    entry.writeUInt32LE(data.length, 20)
    entry.writeUInt32LE(raw.length, 24)
    entry.writeUInt16LE(name.length, 28)
    entry.writeUInt32LE(offset, 42)
    central.push(entry, name)
    offset += local.length + name.length + data.length
  }
  const directory = Buffer.concat(central)
  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(files.length, 8)
  eocd.writeUInt16LE(files.length, 10)
  eocd.writeUInt32LE(directory.length, 12)
  eocd.writeUInt32LE(offset, 16)
  return Buffer.concat([...parts, directory, eocd])
}

console.log('\nzip reading')
{
  const archive = buildZip([
    { name: 'META-INF/MANIFEST.MF', contents: 'Manifest-Version: 1.0\n', store: true },
    { name: 'lib/linux/libglfw.so', contents: 'native-for-linux' },
    { name: 'lib/windows/glfw.dll', contents: 'native-for-windows' },
    { name: 'META-INF/notes.txt', contents: 'excluded by a version json' }
  ])

  check('lists stored and deflated entries and inflates them', () => {
    const entries = listZipEntries(archive)
    expectEqual(
      entries.map((entry) => entry.name),
      ['META-INF/MANIFEST.MF', 'lib/linux/libglfw.so', 'lib/windows/glfw.dll', 'META-INF/notes.txt'],
      'entry names in order'
    )
    const dest = join(root, 'natives')
    const written = extractZip(archive, dest, {
      filter: (name) => isNativeLibraryEntry(name, 'linux')
    })
    expectEqual(written, 1, 'only the linux library was written')
    expectEqual(readFileSync(join(dest, 'lib', 'linux', 'libglfw.so'), 'utf8'), 'native-for-linux', 'inflated contents')
  })

  check('honours extract.exclude and cannot be walked out of its destination', () => {
    const dest = join(root, 'natives-filtered')
    const written = extractZip(archive, dest, { exclude: ['META-INF/'] })
    expectEqual(written, 2, 'the two libraries were written')
    expect(!existsSync(join(dest, 'META-INF', 'notes.txt')), 'the excluded prefix was skipped')
    const escaping = buildZip([{ name: '../escaped.txt', contents: 'nope' }])
    expectThrows(() => extractZip(escaping, join(root, 'escapes')), 'outside')
  })

  check('refuses a truncated archive, the way a part-downloaded zip looks', () => {
    expectThrows(() => listZipEntries(archive.subarray(0, archive.length - 24)), 'partially downloaded')
  })
}

console.log('\ninstall planning')
const planCtx = ruleContextFor('linux', 'x64', {})
const planDir = '/games/.minecraft'
const vanillaLike: VersionJson = {
  id: '1.21.4',
  downloads: {
    client: { path: '1.21.4/1.21.4.jar', url: 'https://piston/1.21.4.jar', sha1: 'aa11', size: 100 }
  },
  libraries: [
    {
      name: 'org.example:common:1.0',
      downloads: {
        artifact: { path: 'org/example/common/1.0/common-1.0.jar', url: 'https://libraries/common.jar', sha1: 'bb22', size: 10 }
      }
    },
    {
      name: 'org.example:windows-only:1.0',
      downloads: { artifact: { path: 'org/example/windows-only/1.0/win-1.0.jar', url: 'https://libraries/win.jar' } },
      rules: [{ action: 'allow', os: { name: 'windows' } }]
    },
    {
      name: 'org.lwjgl:lwjgl:3.3.3',
      natives: { linux: 'natives-linux' },
      extract: { exclude: ['META-INF/'] },
      downloads: {
        artifact: { path: 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar', url: 'https://libraries/lwjgl.jar' },
        classifiers: {
          'natives-linux': {
            path: 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar',
            url: 'https://libraries/lwjgl-natives.jar',
            sha1: 'cc33'
          }
        }
      }
    },
    { name: 'org.example:repo-declared:2.0', url: 'https://maven.example/releases' }
  ]
}

check('plans the client jar, the libraries this platform may load, and the natives', () => {
  const plan = planVersionFiles(planDir, vanillaLike, planCtx)
  const jars = plan.files.map((file) => file.dest)
  expect(jars.includes(join(planDir, 'versions', '1.21.4', '1.21.4.jar')), 'client jar planned')
  expect(
    jars.includes(join(planDir, 'libraries', 'org', 'example', 'common', '1.0', 'common-1.0.jar')),
    'library planned at its maven path'
  )
  expect(
    jars.every((dest) => !dest.includes('windows-only')),
    'a windows-only library is not planned on linux'
  )
  const declared = plan.files.find((file) => file.label === 'org.example:repo-declared:2.0')
  expectEqual(
    declared?.url,
    'https://maven.example/releases/org/example/repo-declared/2.0/repo-declared-2.0.jar',
    'a library with no download of its own is fetched from its own repository'
  )
  expectEqual(plan.nativeJars.length, 1, 'one native jar')
  expectEqual(plan.nativeJars[0].sha1, 'cc33', 'the native jar keeps its checksum')
  expectEqual(plan.nativeJars[0].exclude, ['META-INF/'], 'the exclusion list travels with it')
})

check('lets a loader win over its base version for the same destination', () => {
  const child: VersionJson = {
    id: 'fabric-loader-0.16.9-1.21.4',
    libraries: [
      {
        name: 'org.example:common:1.0',
        downloads: { artifact: { path: 'org/example/common/1.0/common-1.0.jar', url: 'https://loader/common.jar' } }
      }
    ]
  }
  const parent: VersionJson = {
    id: '1.21.4',
    libraries: [
      {
        name: 'org.example:common:1.0',
        downloads: { artifact: { path: 'org/example/common/1.0/common-1.0.jar', url: 'https://vanilla/common.jar' } }
      },
      {
        name: 'org.example:base-only:1.0',
        downloads: { artifact: { path: 'org/example/base-only/1.0/base-only-1.0.jar', url: 'https://vanilla/base.jar' } }
      }
    ]
  }
  const merged = mergePlans([
    planVersionFiles(planDir, child, planCtx),
    planVersionFiles(planDir, parent, planCtx)
  ])
  const common = merged.files.find((file) => file.dest.endsWith('common-1.0.jar'))
  expectEqual(common?.url, 'https://loader/common.jar', 'the child plan wins')
  expect(
    merged.files.some((file) => file.dest.endsWith('base-only-1.0.jar')),
    'the base version still contributes what the loader does not repeat'
  )
  expectEqual(merged.files.length, 2, 'no duplicate destination is planned twice')
})

check('merges a library listed twice instead of letting the first listing win', () => {
  // 1.16.5 spells lwjgl-glfw:3.2.2 out twice: one entry with the artifact, one
  // with the natives. Taking the first and skipping the rest lost the native
  // library, and the game died at runtime with an unloadable native.
  const libraries = [
    {
      name: 'org.lwjgl:lwjgl-glfw:3.2.2',
      downloads: {
        artifact: {
          path: 'org/lwjgl/lwjgl-glfw/3.2.2/lwjgl-glfw-3.2.2.jar',
          url: 'https://libraries/glfw.jar'
        }
      },
      rules: [{ action: 'allow' }, { action: 'disallow', os: { name: 'osx' } }]
    },
    {
      name: 'org.lwjgl:lwjgl-glfw:3.2.2',
      natives: { linux: 'natives-linux', windows: 'natives-windows' },
      downloads: {
        classifiers: {
          'natives-linux': {
            path: 'org/lwjgl/lwjgl-glfw/3.2.2/lwjgl-glfw-3.2.2-natives-linux.jar',
            url: 'https://libraries/glfw-natives.jar'
          }
        }
      },
      rules: [{ action: 'allow' }, { action: 'disallow', os: { name: 'osx' } }]
    },
    {
      name: 'org.example:mac-only:1.0',
      natives: { osx: 'natives-osx' },
      downloads: { classifiers: { 'natives-osx': { path: 'mac/only.jar' } } },
      rules: [{ action: 'allow', os: { name: 'osx' } }]
    }
  ] as Library[]

  const resolved = resolveLibraries(libraries, planCtx)
  expectEqual(resolved.classpath.length, 1, 'one classpath entry for the repeated coordinate')
  expectEqual(
    resolved.classpath[0].relativePath,
    'org/lwjgl/lwjgl-glfw/3.2.2/lwjgl-glfw-3.2.2.jar',
    'the artifact comes from the listing that has one'
  )
  expectEqual(
    resolved.classpath[0].url,
    'https://libraries/glfw.jar',
    'and keeps its own download url'
  )
  expectEqual(resolved.natives.length, 1, 'the natives come from the other listing')
  expectEqual(
    resolved.natives[0].relativePath,
    'org/lwjgl/lwjgl-glfw/3.2.2/lwjgl-glfw-3.2.2-natives-linux.jar',
    'natives-linux for linux'
  )
  expect(
    !resolved.natives.some((entry) => entry.relativePath === 'mac/only.jar'),
    'a mac-only listing stays off linux'
  )
})

check('addresses every asset by its own hash', () => {
  const files = planAssetObjects(planDir, {
    objects: {
      'minecraft/sounds/click.ogg': { hash: 'aabbcc', size: 5 },
      'icons/icon_16x16.png': { hash: 'ddeeff', size: 9 }
    }
  })
  expectEqual(files.length, 2, 'one file per object')
  expectEqual(
    files[0].dest,
    join(planDir, 'assets', 'objects', 'aa', 'aabbcc'),
    'the first two hex digits are a directory'
  )
  expectEqual(files[0].url, 'https://resources.download.minecraft.net/aa/aabbcc', 'the object host')
  expectEqual(files[0].sha1, 'aabbcc', 'the hash is the checksum as well as the path')
})

check('reads the version manifest, filters it and finds a version by id', () => {
  const manifest = parseVersionManifest({
    latest: { release: '1.21.4', snapshot: '1.21.5-pre1' },
    versions: [
      { id: '1.21.5-pre1', type: 'snapshot', url: 'https://meta/s.json', releaseTime: '2026-10-01T00:00:00+00:00' },
      { id: '1.21.4', type: 'release', url: 'https://meta/r.json', releaseTime: '2025-12-03T00:00:00+00:00' },
      { id: 'rd-132211', type: 'old_alpha', url: 'https://meta/a.json' }
    ]
  })
  expectEqual(manifest.latest.release, '1.21.4', 'the latest release')
  expectEqual(filterManifestVersions(manifest).map((v) => v.id), ['1.21.4'], 'releases only by default')
  expectEqual(filterManifestVersions(manifest, { type: 'all' }).length, 3, 'everything') 
  expectEqual(filterManifestVersions(manifest, { type: 'all', search: 'pre' }).map((v) => v.id), ['1.21.5-pre1'], 'search')
  expectEqual(findManifestVersion(manifest, '1.21.4')?.url, 'https://meta/r.json', 'the json url')
  expectEqual(findManifestVersion(manifest, 'nope'), undefined, 'an unknown id')
  expectThrows(() => parseVersionManifest({}), 'lists no versions')
})

check('reads loader metadata and refuses a profile that is not what was asked for', () => {
  expectEqual(loaderListUrl('fabric'), 'https://meta.fabricmc.net/v2/versions/loader', 'fabric list url')
  expectEqual(
    loaderProfileUrl('quilt', '1.21.4', '0.27.1'),
    'https://meta.quiltmc.org/v3/versions/loader/1.21.4/0.27.1/profile/json',
    'quilt profile url'
  )
  const fabric = parseLoaderVersions([
    { version: '0.16.9', stable: true },
    { version: '0.17.0-beta.1', stable: false }
  ])
  expectEqual(fabric.map((v) => v.version), ['0.16.9', '0.17.0-beta.1'], 'fabric versions')
  const quilt = parseLoaderVersions([{ version: '0.27.1' }, { version: '0.28.0-beta.1' }])
  expectEqual(
    quilt.map((v) => v.stable),
    [true, false],
    'quilt publishes no flag, so a pre-release suffix decides'
  )
  const profile = parseLoaderProfile(
    {
      id: 'fabric-loader-0.16.9-1.21.4',
      inheritsFrom: '1.21.4',
      mainClass: 'net.fabricmc.loader.impl.launch.knot.KnotClient',
      libraries: [{ name: 'org.example:loader:1.0' }]
    },
    { kind: 'fabric', gameVersion: '1.21.4', loaderVersion: '0.16.9' }
  )
  expectEqual(profile.id, 'fabric-loader-0.16.9-1.21.4', 'the profile id')
  const unnamed = parseLoaderProfile(
    { inheritsFrom: '1.21.4', mainClass: 'X', libraries: [{ name: 'a:b:1' }] },
    { kind: 'quilt', gameVersion: '1.21.4', loaderVersion: '0.27.1' }
  )
  expectEqual(unnamed.id, 'quilt-loader-0.27.1-1.21.4', 'an id is built when the profile has none')
  expectThrows(
    () =>
      parseLoaderProfile(
        { inheritsFrom: '1.20.1', mainClass: 'X', libraries: [{ name: 'a:b:1' }] },
        { kind: 'fabric', gameVersion: '1.21.4', loaderVersion: '0.16.9' }
      ),
    'inherits from'
  )
  expectThrows(
    () =>
      parseLoaderProfile(
        { inheritsFrom: '1.21.4', libraries: [{ name: 'a:b:1' }] },
        { kind: 'fabric', gameVersion: '1.21.4', loaderVersion: '0.16.9' }
      ),
    'no main class'
  )
  expectThrows(
    () =>
      parseLoaderProfile(
        { inheritsFrom: '1.21.4', mainClass: 'X', libraries: [] },
        { kind: 'fabric', gameVersion: '1.21.4', loaderVersion: '0.16.9' }
      ),
    'lists no libraries'
  )
})

console.log('\ndownloads')
const downloadDir = join(root, 'downloads')
const hashOf = (contents: Buffer): string => createHash('sha1').update(contents).digest('hex')

await acheck('keeps a file only when its hash matches', async () => {
  const body = Buffer.from('jar-bytes')
  const dest = join(downloadDir, 'a.jar')
  const result = await downloadFile(
    { url: 'https://example/a.jar', dest, sha1: hashOf(body) },
    { fetchImpl: async () => new Response(body, { status: 200 }) }
  )
  expect(!result.reused, 'it was fetched, not reused')
  expectEqual(await sha1OfFile(dest), hashOf(body), 'the file hashes to what was declared')
  expect(!existsSync(`${dest}.part`), 'the part file is gone once the real file is in place')
})

await acheck('does not even ask for a file whose hash already matches', async () => {
  let requests = 0
  await downloadFile(
    { url: 'https://example/a.jar', dest: join(downloadDir, 'a.jar'), sha1: hashOf(Buffer.from('jar-bytes')) },
    { fetchImpl: async () => {
      requests += 1
      return new Response('nope', { status: 200 })
    } }
  )
  expectEqual(requests, 0, 'no request was made')
})

await acheck('resumes a part file with a range request', async () => {
  const whole = Buffer.from('0123456789')
  const dest = join(downloadDir, 'resumed.bin')
  writeFileSync(`${dest}.part`, whole.subarray(0, 4))
  let range: string | undefined
  const result = await downloadFile(
    { url: 'https://example/b.bin', dest, sha1: hashOf(whole) },
    {
      fetchImpl: async (_url, init) => {
        range = init?.headers?.Range
        return new Response(whole.subarray(4), { status: 206 })
      }
    }
  )
  expectEqual(range, 'bytes=4-', 'the range asked for from the partial size')
  expectEqual(readFileSync(dest, 'utf8'), '0123456789', 'the file is whole')
  expect(!result.reused, 'it was fetched')
})

await acheck('starts over when the server ignores the range and answers 200', async () => {
  const whole = Buffer.from('abcdefghij')
  const dest = join(downloadDir, 'ignored.bin')
  writeFileSync(`${dest}.part`, Buffer.from('XXXX'))
  await downloadFile(
    { url: 'https://example/c.bin', dest, sha1: hashOf(whole) },
    { fetchImpl: async () => new Response(whole, { status: 200 }) }
  )
  expectEqual(readFileSync(dest, 'utf8'), 'abcdefghij', 'the junk prefix was thrown away')
})

await acheck('never renames a file whose hash does not match, and keeps the bytes', async () => {
  const dest = join(downloadDir, 'tampered.jar')
  await expectRejects(
    () =>
      downloadFile(
        { url: 'https://example/d.jar', dest, sha1: 'deadbeef' },
        { fetchImpl: async () => new Response(Buffer.from('tampered'), { status: 200 }) }
      ),
    'Checksum mismatch'
  )
  expect(!existsSync(dest), 'nothing was renamed into place')
  expectEqual(readFileSync(`${dest}.part`, 'utf8'), 'tampered', 'the bytes stay for a resume')
})

await acheck('retries a failed request before giving up', async () => {
  const body = Buffer.from('eventually')
  const dest = join(downloadDir, 'retry.bin')
  let attempts = 0
  await downloadFile(
    { url: 'https://example/e.bin', dest, sha1: hashOf(body) },
    {
      fetchImpl: async () => {
        attempts += 1
        if (attempts < 3) throw new Error('connection reset')
        return new Response(body, { status: 200 })
      }
    }
  )
  expectEqual(attempts, 3, 'attempts made')
  expectEqual(readFileSync(dest, 'utf8'), 'eventually', 'the file arrived')
})

await acheck('reports byte progress across a batch and counts what it reused', async () => {
  const batchDir = join(downloadDir, 'batch')
  mkdirSync(batchDir, { recursive: true })
  const first = Buffer.from('one')
  const second = Buffer.from('two-two')
  writeFileSync(join(batchDir, 'two.bin'), second)
  const bodies = new Map([
    ['https://example/one.bin', first],
    ['https://example/two.bin', second]
  ])
  const seen: number[] = []
  const report = await downloadAll(
    [
      { url: 'https://example/one.bin', dest: join(batchDir, 'one.bin'), sha1: hashOf(first), size: first.length },
      { url: 'https://example/two.bin', dest: join(batchDir, 'two.bin'), sha1: hashOf(second), size: second.length }
    ],
    {
      concurrency: 2,
      fetchImpl: async (url) =>
        new Response(bodies.get(url) ?? 'missing', { status: bodies.has(url) ? 200 : 404 }),
      onProgress: (progress) => seen.push(progress.bytesDone)
    }
  )
  expectEqual(report.downloaded, 1, 'one file was fetched')
  expectEqual(report.reused, 1, 'one was already there')
  expectEqual(report.bytes, 10, 'progress counts the reused file too')
  expectEqual(seen[seen.length - 1], 10, 'progress finished at the total')
  expect(await isFileValid(join(batchDir, 'one.bin'), hashOf(first)), 'the fetched file verifies')
})

console.log('\nimporting what is already on disk')
{
  const importRoot = join(root, 'import')

  check('copies a single version folder into the selected instance', () => {
    const source = join(importRoot, 'downloaded', 'Imported-1.0')
    mkdirSync(source, { recursive: true })
    writeFileSync(
      join(source, 'Imported-1.0.json'),
      JSON.stringify({ id: 'Imported-1.0', mainClass: 'net.minecraft.client.main.Main' })
    )
    const result = instances.importFrom(source)
    expectEqual(result.versionCount, 1, 'one version was copied')
    expect(
      existsSync(join(gameDir, 'versions', 'Imported-1.0', 'Imported-1.0.json')),
      'the json landed under versions/'
    )
  })

  check('uses a game directory in place rather than copying gigabytes', () => {
    const other = join(importRoot, 'other-game')
    mkdirSync(join(other, 'versions', '1.21.4'), { recursive: true })
    writeFileSync(
      join(other, 'versions', '1.21.4', '1.21.4.json'),
      JSON.stringify({
        id: '1.21.4',
        downloads: { client: { path: '1.21.4/1.21.4.jar', url: 'https://piston/client.jar' } }
      })
    )
    mkdirSync(join(other, 'libraries'))
    const result = instances.importFrom(other)
    expectEqual(result.instance.dir, other, 'the instance keeps the directory it was given')
    expectEqual(result.instance.source, 'imported', 'marked as imported')
    expectEqual(result.instance.versionId, '1.21.4', 'and starts on the version that is there')
    expect(
      result.message.includes('no assets'),
      `the message names what is still missing (got: ${result.message})`
    )
  })

  check('refuses a folder that is neither, and says what it wanted', () => {
    const empty = join(importRoot, 'empty')
    mkdirSync(empty, { recursive: true })
    expectThrows(() => instances.importFrom(empty), 'expected a game directory')
    expectThrows(() => instances.importFrom(join(importRoot, 'absent')), 'does not exist')
  })
}

console.log('\nmodpack archives')
{
  check('tells the two pack formats apart, and by their index only', () => {
    expectEqual(detectModpackFormat(['modrinth.index.json', 'overrides/']), 'modrinth', 'modrinth')
    expectEqual(detectModpackFormat(['manifest.json', 'mods/a.jar']), 'curseforge', 'curseforge')
    expectEqual(detectModpackFormat(['mods/a.jar']), null, 'neither')
  })

  check('reads a Modrinth index into something installable', () => {
    const plan = parseModrinthIndex({
      name: 'Fabulously Optimized',
      versionId: '5.0.0',
      dependencies: { minecraft: '1.21.4', 'fabric-loader': '0.16.9' },
      files: [
        {
          path: 'mods/sodium.jar',
          downloads: ['https://cdn.example/sodium.jar'],
          hashes: { sha1: 'aa', sha512: 'bb' },
          fileSize: 1234
        },
        { path: 'mods/client-only.jar', downloads: ['https://cdn.example/x.jar'], env: { client: 'unsupported' } },
        { path: 'mods/no-url.jar' }
      ]
    })
    expectEqual(plan.summary.gameVersion, '1.21.4', 'the game version it asks for')
    expectEqual(plan.summary.loader, 'fabric', 'the loader it asks for')
    expectEqual(plan.summary.loaderVersion, '0.16.9', 'and the loader version')
    expectEqual(plan.files.map((file) => file.path), ['mods/sodium.jar'], 'only the client files with a url')
    expectEqual(plan.files[0].sha512, 'bb', 'both hashes are kept')
    expectEqual(plan.summary.downloadable, 1, 'summary count')
  })

  check('refuses a pack that needs a loader this launcher cannot install', () => {
    expectThrows(
      () => parseModrinthIndex({ dependencies: { minecraft: '1.21.4', 'neoforge-loader': '21.4.0' } }),
      'Forge and NeoForge publish installers'
    )
  })

  check('reads a CurseForge manifest and says what it cannot fetch', () => {
    expectEqual(parseLoaderId('forge-47.2.0').name, 'forge', 'loader id splitting')
    // A Forge pack is refused by name rather than half-imported: its loader has
    // an installer of its own that this launcher cannot run.
    expectThrows(
      () =>
        parseCurseForgeManifest({
          name: 'All the Mods',
          minecraft: { version: '1.20.1', modLoaders: [{ id: 'forge-47.2.0', primary: true }] }
        }),
      'Forge and NeoForge publish installers'
    )

    const fabric = parseCurseForgeManifest({
      name: 'A fabric pack',
      version: '2.0.0',
      minecraft: { version: '1.20.1', modLoaders: [{ id: 'fabric-0.15.0', primary: true }] },
      overrides: 'overrides',
      files: [
        { projectID: 238222, fileID: 123456, required: true },
        { projectID: 1, fileID: 2, required: false }
      ]
    })
    expectEqual(fabric.summary.gameVersion, '1.20.1', 'the game version')
    expectEqual(fabric.summary.loader, 'fabric', 'the loader it names')
    expectEqual(fabric.summary.loaderVersion, '0.15.0', 'and its version')
    expectEqual(fabric.unresolved, ['238222:123456'], 'required files are reported, optional ones are not')
    expectEqual(fabric.summary.downloadable, 0, 'nothing is downloadable without an api')
  })

  check('maps archive entries into the instance and nowhere else', () => {
    const plan = parseModrinthIndex({ name: 'x', dependencies: { minecraft: '1.21.4' } })
    expectEqual(instancePathFor('overrides/config/fabric.json', plan), 'config/fabric.json', 'overrides stripped')
    expectEqual(instancePathFor('modrinth.index.json', plan), null, 'the index is metadata')
    expectEqual(instancePathFor('mods/sodium.jar', plan), null, 'top-level files are the download list, not ours')
    expectEqual(instancePathFor('overrides/', plan), null, 'directories are skipped')

    const curseforge = parseCurseForgeManifest({
      minecraft: { version: '1.20.1', modLoaders: [{ id: 'fabric-0.15.0' }] },
      files: []
    })
    expectEqual(instancePathFor('mods/sodium.jar', curseforge), 'mods/sodium.jar', 'bundled mods come in')
    expectEqual(instancePathFor('saves/world/level.dat', curseforge), null, 'saves do not')
  })

  check('unpacks a renamed tree without leaving the instance', () => {
    const plan = parseModrinthIndex({ name: 'x', dependencies: { minecraft: '1.21.4' } })
    const dest = join(root, 'modpack-instance')
    const rename = (name: string): string | null => instancePathFor(name, plan)

    const pack = buildZip([
      { name: 'overrides/config/fabric.json', contents: '{"x":1}' },
      { name: 'overrides/saves/world/level.dat', contents: 'save' }
    ])
    expectEqual(
      extractZip(pack, dest, { rename }),
      2,
      'the overrides tree is written; the index is not'
    )
    expectEqual(readFileSync(join(dest, 'config', 'fabric.json'), 'utf8'), '{"x":1}', 'prefix dropped')

    // An archive is untrusted input: a pack that tries to climb out of the
    // instance is refused, not quietly written somewhere else.
    const hostile = buildZip([{ name: 'overrides/../../escape.txt', contents: 'nope' }])
    expectThrows(
      () => extractZip(hostile, join(root, 'modpack-escape'), { rename }),
      'would be written outside'
    )
    expect(!existsSync(join(root, 'escape.txt')), 'nothing escaped the destination')
  })
}

console.log('\njava')
{
  check('reads the major version out of java -version output', () => {
    expectEqual(parseJavaMajorVersion('openjdk version "17.0.9" 2023-10-17'), 17, 'modern')
    expectEqual(parseJavaMajorVersion('java version "1.8.0_392"'), 8, 'the old 1.x spelling')
    expectEqual(parseJavaMajorVersion('java version "21"'), 21, 'no minor')
    expectEqual(parseJavaMajorVersion('nothing useful here'), null, 'unrecognised')
  })

  check('prefers the smallest runtime that is new enough', () => {
    const runtimes = [
      { path: '/jdk-8/bin/java', major: 8 },
      { path: '/jdk-17/bin/java', major: 17 },
      { path: '/jdk-21/bin/java', major: 21 }
    ]
    expectEqual(selectJava(runtimes, 17)?.path, '/jdk-17/bin/java', '17 for a version that wants 17')
    expectEqual(selectJava(runtimes, 21)?.path, '/jdk-21/bin/java', 'the only one good enough')
    expectEqual(selectJava(runtimes, null)?.path, '/jdk-21/bin/java', 'newest when nothing is required')
    expectEqual(
      selectJava(runtimes, 25)?.path,
      '/jdk-21/bin/java',
      'the newest anyway rather than refusing to launch'
    )
    expectEqual(selectJava([{ path: '/unknown/bin/java', major: null }], 17)?.path, '/unknown/bin/java', 'unprobed fallback')
    expectEqual(selectJava([], 17), null, 'nothing at all')
  })

  check('looks inside the instance first, then JAVA_HOME, then the system', () => {
    const candidates = javaCandidates({
      platform: 'linux',
      instanceDir: '/instances/a',
      component: 'java-runtime-delta',
      env: { JAVA_HOME: '/usr/lib/jvm/jdk-17' },
      homeDir: '/home/steve'
    })
    expectEqual(candidates[0].path, '/instances/a/runtime/java-runtime-delta/linux/java-runtime-delta/bin/java', 'runtime first')
    expectEqual(candidates[0].knownMajor, 21, 'and it knows what a delta runtime is')
    expectEqual(candidates[1].source, 'JAVA_HOME', 'then JAVA_HOME')
    expectEqual(candidates[1].knownMajor, 17, 'read from the path')
    expect(
      candidates.some((candidate) => candidate.path === '/usr/lib/jvm'),
      'and the ordinary JDK folders'
    )
    expectEqual(runtimeCandidate('/i', '', 'linux'), null, 'no component, no runtime')
  })

  check('knows which files a resource folder will load', () => {
    expectEqual(splitResourceName('sodium.jar.disabled').title, 'sodium.jar', 'suffix stripped')
    expectEqual(resourceFileName('sodium.jar', false), 'sodium.jar.disabled', 'and added back')
    expect(isResourceFile('sodium.jar', 'mod'), 'a mod is a jar')
    expect(!isResourceFile('sodium.jar', 'resourcepack'), 'a resource pack is not a jar')
    expect(isResourceFile('pack.zip.disabled', 'resourcepack'), 'a disabled pack is still listed')
    expect(!isResourceFile('.DS_Store', 'mod'), 'finder leftovers are not mods')
    expect(!isResourceFile('download.jar.part', 'mod'), 'a part file is not a mod')
  })
}

console.log('\nstate migration')
{
  check('turns an older state file into an instance instead of forgetting it', () => {
    const legacy = defaultState('/instances')
    const profiles = [{ id: 'p', name: 'Steve', uuid: 'u', createdAt: 'now', lastPlayedAt: null }]
    const migrated = migrateState(
      {
        ...legacy,
        settings: { ...legacy.settings, gameDir: '/games/.minecraft' } as never,
        profiles: [{ ...profiles[0], versionId: '1.21.4' } as never]
      },
      { exists: () => true }
    )
    expectEqual(migrated.instances.length, 1, 'one instance is created')
    expectEqual(migrated.instances[0].dir, '/games/.minecraft', 'over the old game directory')
    expectEqual(migrated.instances[0].versionId, '1.21.4', 'taking the version from the profile')
    expectEqual(migrated.selectedInstanceId, migrated.instances[0].id, 'and selecting it')
    expect(
      (migrated.settings as { gameDir?: string }).gameDir === undefined,
      'the old setting is dropped'
    )

    const deleted = migrateState(
      {
        ...legacy,
        settings: { ...legacy.settings, gameDir: '/games/gone' } as never,
        profiles: []
      },
      { exists: () => false }
    )
    expectEqual(deleted.instances, [], 'a directory that is gone is not resurrected')
  })
}

rmSync(root, { recursive: true, force: true })

console.log(`\n${passed} checks passed, ${failures.length} failed`)
if (failures.length > 0) {
  for (const failure of failures) console.log(`  - ${failure}`)
  process.exit(1)
}

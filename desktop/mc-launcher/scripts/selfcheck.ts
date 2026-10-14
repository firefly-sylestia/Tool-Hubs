/**
 * Verification for the launcher's pure logic, runnable under plain Node:
 *   npm run selfcheck
 *
 * It builds a fake installation on disk with the same shapes the game ships,
 * then exercises version resolution, rule evaluation, classpath building,
 * argument substitution, profile/server validation and the state store. No
 * Electron, no network, no game files.
 */
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { createHash } from 'node:crypto'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { buildLaunchPlan, supportsQuickPlay } from '../src/main/minecraft/launch-plan'
import { offlineUuid, offlineUuidDashless } from '../src/main/minecraft/offline-uuid'
import { ruleContextFor, rulesAllow } from '../src/main/minecraft/rules'
import { resolveVersionChain } from '../src/main/minecraft/version-json'
import { classpathString, mavenPath, resolveLibraries } from '../src/main/minecraft/classpath'
import { flattenArguments, substitute, unresolvedPlaceholders } from '../src/main/minecraft/arguments'
import { AtomicJsonStore } from '../src/main/services/atomic-store'
import { ServiceRegistry } from '../src/main/services/registry'
import { StateStore, defaultState } from '../src/main/services/state-store'
import { ProfileService } from '../src/main/services/profile-service'
import { ServerService } from '../src/main/services/server-service'
import { SettingsService } from '../src/main/services/settings-service'
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
  gameDir: null,
  versionId: '1.21.4',
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
    settings: { gameDir, javaPath: null, maxMemoryMb: 2048, width: 1024, height: 768 },
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
    profile: { ...profile, versionId: '1.21.4-forge' },
    versionId: '1.21.4-forge',
    settings: { gameDir, javaPath: '/usr/bin/java', maxMemoryMb: 4096, width: 1280, height: 720 },
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
    settings: { gameDir, javaPath: null, maxMemoryMb: 2048, width: 1280, height: 720 },
    connect: { host: '127.0.0.1', port: 25566 },
    ctx
  })
  expectEqual(modern.args.slice(-2), ['--quickPlayMultiplayer', '127.0.0.1:25566'], 'quick play args')
  const legacy = buildLaunchPlan({
    gameDir,
    profile,
    versionId: '1.19.4',
    settings: { gameDir, javaPath: null, maxMemoryMb: 2048, width: 1280, height: 720 },
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
        profile: { ...profile, versionId: '1.21.4-nolibs' },
        versionId: '1.21.4-nolibs',
        settings: { gameDir, javaPath: null, maxMemoryMb: 2048, width: 1280, height: 720 },
        connect: null,
        ctx
      }),
    'does not download or extract game files'
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

console.log('\nstate and services')
const registry = new ServiceRegistry()
const state = new StateStore(join(stateDir, 'launcher-state.json'), defaultState(gameDir), registry)
const profiles = new ProfileService(state)
const servers = new ServerService(state)
const settings = new SettingsService(state)

check('creates a profile with an offline UUID', () => {
  const created = profiles.create({ name: 'Alex', versionId: '1.21.4' })
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
  expectThrows(() => settings.update({ gameDir: 'relative/path' }), 'absolute path')
  const updated = settings.update({ maxMemoryMb: 6144, javaPath: null })
  expectEqual(updated.maxMemoryMb, 6144, 'memory')
})
check('persists state through the store', () => {
  const reopened = new StateStore(join(stateDir, 'launcher-state.json'), defaultState('/tmp/other'), registry)
  expectEqual(reopened.snapshot().profiles.length, profiles.list().length, 'profiles survived')
  expectEqual(reopened.snapshot().settings.maxMemoryMb, 6144, 'settings survived')
  expectEqual(reopened.snapshot().settings.gameDir, gameDir, 'game dir survived')
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

rmSync(root, { recursive: true, force: true })

console.log(`\n${passed} checks passed, ${failures.length} failed`)
if (failures.length > 0) {
  for (const failure of failures) console.log(`  - ${failure}`)
  process.exit(1)
}

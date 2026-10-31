/**
 * Verification for the launcher's pure logic, runnable under plain Node:
 *   npm run selfcheck
 *
 * It builds a fake installation on disk with the same shapes the game ships,
 * then exercises version resolution, rule evaluation, classpath building,
 * argument substitution, profile/server validation and the state store. No
 * Electron, no network, no game files.
 */
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
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
  needsRefresh,
  planAssetObjects,
  planInstallation,
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
import {
  bestVersion,
  isSearchSort,
  matchesVersionRange,
  parseProjectDetail,
  parseSearchResponse,
  parseVersionList,
  primaryFile,
  projectTypeForKind,
  projectWebUrl,
  requiredDependencies,
  searchFacets,
  searchUrl,
  userAgent,
  versionsQuery
} from '../src/main/minecraft/modrinth'
import {
  forgeMinecraftRange,
  modWarnings,
  readModMetadata,
  readTomlValues,
  type ModMetadata
} from '../src/main/minecraft/mod-metadata'
import { extractZip, isNativeLibraryEntry, listZipEntries, readZipEntry } from '../src/main/minecraft/zip'
import { writeZip } from '../src/main/minecraft/zip-writer'
import { isGzipped, nbtAt, nbtScalar, readNbt, writeNbt } from '../src/main/minecraft/nbt'
import {
  backupFileName,
  describeWorld,
  detectWorldRoot,
  sanitizeWorldFolder,
  uniqueWorldFolder,
  worldsInBackup
} from '../src/main/minecraft/saves'
import { SaveService } from '../src/main/services/save-service'
import { DiagnosticService } from '../src/main/services/diagnostic-service'
import { diagnose, missingDependencies, summarizeReport } from '../src/main/minecraft/crash-report'
import {
  expectedFrameLength,
  flattenChat,
  handshakePacket,
  offlineStatus,
  parseServerStatus,
  readVarInt,
  statusJsonFromFrame,
  statusRequestPacket,
  writeVarInt
} from '../src/main/minecraft/server-status'
import {
  joinAddress,
  mergeServerEntries,
  readServersDat,
  serverCompound,
  splitAddress,
  writeServersDat
} from '../src/main/minecraft/servers-dat'
import { describeStatus } from '../src/shared/server-text'
import type { NbtValue } from '../src/main/minecraft/nbt'
import {
  detectModpackFormat,
  instancePathFor,
  parseCurseForgeManifest,
  parseModrinthIndex,
  parseLoaderId
} from '../src/main/minecraft/modpack'
import {
  inferJavaMajor,
  javaCandidates,
  javaExecutableName,
  parseJavaMajorVersion,
  runtimeCandidate,
  runtimeComponentsForMajor,
  runtimePlatformDir,
  selectJava
} from '../src/main/minecraft/java'
import { JavaService } from '../src/main/services/java-service'
import {
  findRuntimeManifest,
  listRuntimeComponents,
  parseRuntimeManifest,
  runtimeDirFor,
  runtimeOsKey
} from '../src/main/minecraft/java-runtime'
import {
  splitResourceName,
  resourceFileName,
  isResourceFile,
  folderForKind
} from '../src/main/minecraft/resources'
import {
  buildPackIndex,
  packArchivePath,
  packIncludes,
  packVersionId
} from '../src/main/minecraft/pack-export'
import { optionValue, parseOptions, patchOptions, quickOptionValues } from '../src/main/minecraft/options'
import { formatJvmArgs, parseJvmArgs } from '../src/shared/jvm-args'
import { AtomicJsonStore } from '../src/main/services/atomic-store'
import { InstanceService, slugFor } from '../src/main/services/instance-service'
import { InstallationService } from '../src/main/services/installation-service'
import { InstallService } from '../src/main/services/install-service'
import { ServiceRegistry } from '../src/main/services/registry'
import { StateStore, defaultState, migrateState } from '../src/main/services/state-store'
import { ProfileService } from '../src/main/services/profile-service'
import { ModpackService, PACK_EXTENSIONS } from '../src/main/services/modpack-service'
import { adoptFiles, adoptVersions } from '../src/main/minecraft/adopt'
import { FILE_PICKERS, pickerExtensions } from '../src/shared/file-pickers'
import { ModrinthService } from '../src/main/services/modrinth-service'
import { baseGameVersion } from '../src/main/minecraft/version-id'
import { presentInstalls, readStoreInstalls, writeStoreInstalls } from '../src/main/services/modrinth-service'
import { ServerService } from '../src/main/services/server-service'
import { SettingsService } from '../src/main/services/settings-service'
import { TaskService } from '../src/main/services/task-service'
import { pipeLines } from '../src/main/services/launch-service'
import type { Profile, StoreInstall } from '../src/shared/types'

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
    sharedDir: gameDir,
    profile,
    versionId: '1.21.4',
    settings: { instancesDir: gameDir, javaPath: null, maxMemoryMb: 2048, width: 1024, height: 768, theme: 'system' },
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
    sharedDir: gameDir,
    profile,
    versionId: '1.21.4-forge',
    settings: { instancesDir: gameDir, javaPath: '/usr/bin/java', maxMemoryMb: 4096, width: 1280, height: 720, theme: 'system' },
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
    sharedDir: gameDir,
    profile,
    versionId: '1.21.4',
    settings: { instancesDir: gameDir, javaPath: null, maxMemoryMb: 2048, width: 1280, height: 720, theme: 'system' },
    connect: { host: '127.0.0.1', port: 25566 },
    ctx
  })
  expectEqual(modern.args.slice(-2), ['--quickPlayMultiplayer', '127.0.0.1:25566'], 'quick play args')
  const legacy = buildLaunchPlan({
    gameDir,
    sharedDir: gameDir,
    profile,
    versionId: '1.19.4',
    settings: { instancesDir: gameDir, javaPath: null, maxMemoryMb: 2048, width: 1280, height: 720, theme: 'system' },
    connect: { host: '127.0.0.1', port: 25566 },
    ctx
  })
  expectEqual(legacy.args.slice(-4), ['--server', '127.0.0.1', '--port', '25566'], 'legacy join args')
})
check('lets one instance have its own memory without touching the default', () => {
  const settings = {
    instancesDir: gameDir,
    javaPath: null,
    maxMemoryMb: 2048,
    width: 1280,
    height: 720,
    theme: 'system' as const
  }
  const own = buildLaunchPlan({
    gameDir,
    sharedDir: gameDir,
    profile,
    versionId: '1.21.4',
    settings,
    connect: null,
    ctx,
    maxMemoryMb: 8192
  })
  expect(own.args.includes('-Xmx8192m'), 'the instance limit is used')
  expect(!own.args.includes('-Xmx2048m'), 'and the launcher default is not also passed')

  const inherited = buildLaunchPlan({
    gameDir,
    sharedDir: gameDir,
    profile,
    versionId: '1.21.4',
    settings,
    connect: null,
    ctx,
    maxMemoryMb: null
  })
  expect(inherited.args.includes('-Xmx2048m'), 'null falls back to the launcher default')
})
check('fails with an actionable message when natives are missing', () => {
  const broken = { ...vanillaJson, id: '1.21.4-nolibs' }
  writeVersion('1.21.4-nolibs', broken, true, false)
  expectThrows(
    () =>
      buildLaunchPlan({
        gameDir,
        sharedDir: gameDir,
        profile,
        versionId: '1.21.4-nolibs',
        settings: { instancesDir: gameDir, javaPath: null, maxMemoryMb: 2048, width: 1280, height: 720, theme: 'system' },
        connect: null,
        ctx
      }),
    'Install this version from the Instances page'
  )
})

console.log('\ngame options')
{
  check('patches options.txt without disturbing it', () => {
    const contents = ['version:4325', 'fullscreen:false', 'fov:0.0', 'lang:en_us', ''].join('\n')
    const patched = patchOptions(contents, { fullscreen: 'true', renderDistance: '16' })
    expectEqual(
      patched,
      'version:4325\nfullscreen:true\nfov:0.0\nlang:en_us\nrenderDistance:16\n',
      'the changed key stays where it was, the new one is appended'
    )
    expectEqual(patchOptions(contents, { fov: null }), 'version:4325\nfullscreen:false\nlang:en_us\n', 'removing a key')
    expectEqual(
      patchOptions(contents, {}),
      contents,
      'no changes, no rewrite beyond the trailing newline'
    )
  })

  check('reads options back, including keys it has never heard of', () => {
    const { entries } = parseOptions('fullscreen:true\nmadeUpKey:42\n\n# a comment\nbroken-line\n')
    expectEqual(
      entries.map((entry) => entry.key),
      ['fullscreen', 'madeUpKey'],
      'comments and lines without a colon are not options'
    )
    expectEqual(optionValue('guiScale:3\n', 'guiScale'), '3', 'value lookup')
    expectEqual(optionValue('guiScale:3\n', 'fov'), null, 'a key that is not there')
    expectEqual(
      quickOptionValues('fullscreen:true\nfov:70.0\n').fullscreen,
      'true',
      'the quick fields are read out of the file'
    )
    expectEqual(
      Object.keys(quickOptionValues('fullscreen:true\n')).length,
      1,
      'only the keys the file has'
    )
  })

  check('splits JVM arguments the way a shell would', () => {
    expectEqual(parseJvmArgs('-XX:+UseG1GC -Xms2G'), ['-XX:+UseG1GC', '-Xms2G'], 'plain')
    expectEqual(
      parseJvmArgs('-Dsome.option="hello world" -Xss4M'),
      ['-Dsome.option=hello world', '-Xss4M'],
      'a quoted value stays one argument'
    )
    expectEqual(parseJvmArgs('  \n -Xmx4G \n\n'), ['-Xmx4G'], 'whitespace and blank lines')
    expectEqual(parseJvmArgs(''), [], 'nothing at all')
    expectEqual(formatJvmArgs(['-Dopt=a b', '-Xmx4G']), '"-Dopt=a b"\n-Xmx4G', 'and back again, quoted')
  })

}

console.log('\nreusing files the machine already has')
{
  check('takes a file another game directory has instead of downloading it', () => {
    const sharedDir = join(root, 'adopt-game')
    const oldGame = join(root, 'adopt-old')

    const object = join(oldGame, 'assets', 'objects', 'ab', 'abcdef')
    mkdirSync(join(oldGame, 'assets', 'objects', 'ab'), { recursive: true })
    writeFileSync(object, 'asset-bytes')

    const adopted = adoptFiles({
      sharedDir,
      files: [
        {
          url: 'https://resources/ab/abcdef',
          dest: join(sharedDir, 'assets', 'objects', 'ab', 'abcdef'),
          sha1: 'abcdef',
          size: 11,
          label: 'asset abcdef'
        }
      ],
      sources: [oldGame]
    })
    expectEqual(adopted.adopted.length, 1, 'the file was taken rather than fetched')
    expectEqual(adopted.remaining.length, 0, 'and is no longer left to download')
    expectEqual(
      readFileSync(join(sharedDir, 'assets', 'objects', 'ab', 'abcdef'), 'utf8'),
      'asset-bytes',
      'the bytes are the ones it was copied from'
    )

    // The source directory is left exactly as it was: nothing is ever written
    // back to a folder somebody else owns.
    expectEqual(readFileSync(object, 'utf8'), 'asset-bytes', 'the source still has its file')
  })

  check('brings a version folder another game directory already has', () => {
    const sharedDir = join(root, 'adopt-versions-game')
    const oldGame = join(root, 'adopt-versions-old')
    mkdirSync(join(oldGame, 'versions', '1.20.1'), { recursive: true })
    writeFileSync(join(oldGame, 'versions', '1.20.1', '1.20.1.json'), JSON.stringify({ id: '1.20.1' }))
    writeFileSync(join(oldGame, 'versions', '1.20.1', '1.20.1.jar'), 'jar-bytes')
    mkdirSync(join(oldGame, 'versions', 'already-there'), { recursive: true })
    writeFileSync(join(oldGame, 'versions', 'already-there', 'already-there.json'), '{}')

    const taken = adoptVersions({ sharedDir, sources: [oldGame, join(root, 'no-such-directory')] })
    expectEqual(
      [...taken].sort(),
      ['1.20.1', 'already-there'],
      'both versions were taken, and a directory with no versions in it is skipped'
    )
    expect(
      existsSync(join(sharedDir, 'versions', '1.20.1', '1.20.1.json')) &&
        existsSync(join(sharedDir, 'versions', 'already-there', 'already-there.json')),
      'the jsons landed in the shared folder'
    )
    expectEqual(
      readFileSync(join(sharedDir, 'versions', '1.20.1', '1.20.1.jar'), 'utf8'),
      'jar-bytes',
      'and so did the jar'
    )

    // The shared folder is never a source, and a version it already has is left
    // exactly as it is: a second run takes nothing.
    writeFileSync(
      join(sharedDir, 'versions', '1.20.1', '1.20.1.json'),
      JSON.stringify({ id: '1.20.1', mine: true })
    )
    expectEqual(adoptVersions({ sharedDir, sources: [oldGame] }), [], 'nothing is taken twice')
    expect(
      readFileSync(join(sharedDir, 'versions', '1.20.1', '1.20.1.json'), 'utf8').includes('"mine"'),
      'and the copy already there is not overwritten'
    )
  })

  check('refuses a file of the wrong size and a destination outside the game folder', () => {
    const sharedDir = join(root, 'adopt-game-2')
    const oldGame = join(root, 'adopt-old-2')
    const library = join(oldGame, 'libraries', 'org', 'example', '1.0', 'example-1.0.jar')
    mkdirSync(join(oldGame, 'libraries', 'org', 'example', '1.0'), { recursive: true })
    // A half-finished download somewhere else is exactly what the size check
    // exists to catch; it is not a source of anything.
    writeFileSync(library, 'short')

    const short = adoptFiles({
      sharedDir,
      files: [
        {
          url: 'https://libraries/example-1.0.jar',
          dest: join(sharedDir, 'libraries', 'org', 'example', '1.0', 'example-1.0.jar'),
          size: 999,
          label: 'example-1.0.jar'
        }
      ],
      sources: [oldGame]
    })
    expectEqual(short.adopted.length, 0, 'the wrong size is not adopted')
    expectEqual(short.remaining.length, 1, 'and the file stays on the download list')

    const outside = adoptFiles({
      sharedDir,
      files: [{ url: 'https://example/x', dest: join(root, 'not-in-the-game-folder.txt'), label: 'outside' }],
      sources: [oldGame]
    })
    expectEqual(outside.adopted.length, 0, 'a destination outside the game folder is never adopted')
    expectEqual(outside.remaining.length, 1, 'it is left alone')
  })
}

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
  // The launcher's own game folder: what the app keeps the version files in,
  // separate from the instance folders that hold their mods and worlds.
  sharedDir: join(root, 'game'),
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
const settings = new SettingsService(state)
const installations = new InstallationService(state, environment, () => {})
const instances = new InstanceService(state, environment, installations, settings, () => {})
// The server list reads and writes the game's own `servers.dat`, which lives in
// an instance's directory, so it is built after the instances.
const servers = new ServerService(state, instances, () => {})
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
check('lets one instance keep its own Java and memory, and no other', () => {
  expectEqual(instance.javaPath, null, 'a new instance starts on the launcher default java')
  expectEqual(instance.maxMemoryMb, null, 'and on its default memory')

  const updated = instances.update(instance.id, {
    javaPath: '/usr/lib/jvm/java-8/bin/java',
    maxMemoryMb: 6144
  })
  expectEqual(updated.javaPath, '/usr/lib/jvm/java-8/bin/java', 'the runtime it was given')
  expectEqual(updated.maxMemoryMb, 6144, 'and the limit it was given')

  // A name is not a path: `java` on PATH is what leaving it empty means, and a
  // relative one would resolve against whatever the working directory happened
  // to be at launch.
  expectThrows(() => instances.update(instance.id, { javaPath: 'java' }), 'absolute path')
  expectThrows(() => instances.update(instance.id, { maxMemoryMb: 12 }), 'between 512 and 65536')
  expectEqual(instances.require(instance.id).maxMemoryMb, 6144, 'a refused value changes nothing')

  expectEqual(instances.update(instance.id, { javaPath: null }).javaPath, null, 'and back to automatic')
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

check('names what each mod really is, and flags the ones that will not load', () => {
  const mods = join(gameDir, 'mods')
  const fabricJar = (name: string) =>
    buildZip([
      {
        name: 'fabric.mod.json',
        contents: JSON.stringify({ id: 'sodium', name, version: '0.6.13', depends: { minecraft: '>=1.21.2' } })
      }
    ])
  writeFileSync(join(mods, 'sodium-0.6.13.jar'), fabricJar('Sodium'))
  writeFileSync(join(mods, 'sodium-copy.jar'), fabricJar('Sodium'))
  writeFileSync(
    join(mods, 'old-forge.jar'),
    buildZip([
      {
        name: 'META-INF/mods.toml',
        contents: '[[mods]]\nmodId="old"\ndisplayName="Old Forge Mod"\nversion="1"\n'
      }
    ])
  )

  const entries = instances.resources(instance.id).entries.mod
  const sodium = entries.find((entry) => entry.name === 'sodium-0.6.13.jar')!
  expectEqual(sodium.metadata?.name, 'Sodium', 'the name the jar declares, not the file name')
  expect(
    (sodium.metadata?.warnings ?? []).some((warning) => warning.includes('Another copy')),
    'the second copy is flagged as one the game will not load'
  )

  const forge = entries.find((entry) => entry.name === 'old-forge.jar')!
  expect(
    (forge.metadata?.warnings ?? []).some((warning) => warning.includes('cannot load')),
    'a Forge mod in a Fabric instance is flagged'
  )

  const plain = entries.find((entry) => entry.name === 'sodium.jar')!
  expectEqual(plain.metadata, null, 'a file with no metadata is still listed, with none')
})

check('an instance can set its own options and JVM arguments', () => {
  const second = instances.create({ name: 'Options test', dir: join(root, 'options-instance') })
  const before = instances.gameOptions(second.id)
  expectEqual(before.exists, false, 'the game has not written it yet')
  expectEqual(before.jvmArgs, [], 'and there are no JVM arguments')

  const saved = instances.setGameOptions({
    instanceId: second.id,
    changes: { fullscreen: 'true', renderDistance: '16' },
    jvmArgs: ['-XX:+UseG1GC']
  })
  expectEqual(saved.exists, true, 'the file is written')
  expectEqual(saved.quick.fullscreen, 'true', 'the value is there')
  expectEqual(
    instances.list().find((entry) => entry.id === second.id)?.jvmArgs,
    ['-XX:+UseG1GC'],
    'arguments kept'
  )
  expectEqual(
    readFileSync(join(root, 'options-instance', 'options.txt'), 'utf8'),
    'fullscreen:true\nrenderDistance:16\n',
    'what actually landed in the file'
  )

  const cleared = instances.setGameOptions({ instanceId: second.id, changes: { fullscreen: null } })
  expectEqual(cleared.quick.fullscreen, undefined, 'a removed key is gone')
  expectEqual(cleared.entries.map((entry) => entry.key), ['renderDistance'], 'and the rest stayed')
  instances.remove(second.id)
})

console.log('\nmod metadata')
{
  check('reads what a Fabric mod says about itself', () => {
    const metadata = readModMetadata(
      buildZip([
        {
          name: 'fabric.mod.json',
          contents: JSON.stringify({
            id: 'sodium',
            name: 'Sodium',
            version: '0.6.13',
            depends: { minecraft: '>=1.21.2' },
            authors: ['jellysquid3']
          })
        },
        { name: 'sodium.class', contents: 'binary' }
      ])
    )
    expect(metadata !== null, 'metadata was read')
    expectEqual(metadata?.loader, 'fabric', 'loader')
    expectEqual(metadata?.name, 'Sodium', 'name')
    expectEqual(metadata?.version, '0.6.13', 'version')
    expectEqual(metadata?.requires, '>=1.21.2', 'the minecraft requirement')
    expectEqual(metadata?.authors, ['jellysquid3'], 'authors')
  })

  check('reads the Forge and NeoForge toml, minecraft range included', () => {
    const toml = [
      'modLoader="javafml"',
      'loaderVersion="[47,)"',
      '[[mods]]',
      'modId="example"',
      'version="1.2.3"',
      'displayName="Example Mod"',
      '[[dependencies.example]]',
      'modId="minecraft"',
      'versionRange="[1.20.1,1.21)"',
      'mandatory=true',
      '[[dependencies.example]]',
      'modId="forge"',
      'versionRange="[47,)"',
      ''
    ].join('\n')

    const forge = readModMetadata(buildZip([{ name: 'META-INF/mods.toml', contents: toml }]))
    expectEqual(forge?.loader, 'forge', 'forge')
    expectEqual(forge?.name, 'Example Mod', 'display name')
    expectEqual(forge?.version, '1.2.3', 'version')
    expectEqual(forge?.requires, '[1.20.1,1.21)', 'the minecraft range, not the forge one')
    expectEqual(forgeMinecraftRange(toml), '[1.20.1,1.21)', 'range lookup')

    const neo = readModMetadata(buildZip([{ name: 'META-INF/neoforge.mods.toml', contents: toml }]))
    expectEqual(neo?.loader, 'neoforge', 'neoforge')
    expectEqual(readTomlValues('a = "1"\nb = 2 # comment\n').get('b'), '2', 'toml values, comments dropped')
  })

  check('reads Quilt metadata and the legacy mcmod.info', () => {
    const quilt = readModMetadata(
      buildZip([
        {
          name: 'quilt.mod.json',
          contents: JSON.stringify({
            schema_version: 1,
            quilt_loader: {
              id: 'example',
              version: '9.9.9',
              metadata: { name: 'Example Quilt Mod', version: '9.9.9' },
              depends: [{ id: 'minecraft', versions: '>=1.20' }]
            }
          })
        }
      ])
    )
    expectEqual(quilt?.loader, 'quilt', 'quilt')
    expectEqual(quilt?.name, 'Example Quilt Mod', 'name')

    const legacy = readModMetadata(
      buildZip([
        {
          name: 'mcmod.info',
          contents: JSON.stringify([{ modid: 'old', name: 'Old Mod', version: '1.0', mcversion: '1.7.10' }])
        }
      ])
    )
    expectEqual(legacy?.loader, 'forge', 'legacy forge')
    expectEqual(legacy?.legacy, true, 'marked legacy')
    expectEqual(legacy?.requires, '1.7.10', 'the game version it wants')
  })

  check('a file that is not a mod has no metadata, and that is not an error', () => {
    expectEqual(readModMetadata(buildZip([{ name: 'pack.mcmeta', contents: '{}' }])), null, 'a resource pack')
    expectEqual(readModMetadata(Buffer.from('this is not a zip')), null, 'not a zip at all')
    expectEqual(readModMetadata(buildZip([{ name: 'fabric.mod.json', contents: '{not json' }])), null, 'broken json')
  })

  check('flags a mod that cannot load in this instance, and stays quiet when unsure', () => {
    const base = { id: 'x', name: 'X', version: '1', authors: [], legacy: false }
    const forge: ModMetadata = { ...base, loader: 'forge', requires: null }
    const wrongLoader = modWarnings(forge, {
      versionId: '1.21.4',
      loader: 'fabric',
      rangeMatcher: matchesVersionRange
    })
    expectEqual(wrongLoader.length, 1, 'one warning')
    expect(wrongLoader[0].includes('cannot load'), 'the warning says it cannot load')

    const wrongVersion: ModMetadata = { ...base, loader: 'fabric', requires: '>=1.20 <1.21' }
    const mismatch = modWarnings(wrongVersion, {
      versionId: '1.21.4',
      loader: 'fabric',
      rangeMatcher: matchesVersionRange
    })
    expectEqual(mismatch.length, 1, 'a version mismatch is flagged')
    expect(mismatch[0].includes('1.21.4'), 'and names the instance version')

    const fine: ModMetadata = { ...base, loader: 'fabric', requires: '>=1.20' }
    expectEqual(
      modWarnings(fine, { versionId: '1.21.4', loader: 'fabric', rangeMatcher: matchesVersionRange }),
      [],
      'a compatible mod says nothing'
    )

    // A range this does not understand must not produce a warning about it.
    const unknown: ModMetadata = { ...base, loader: 'fabric', requires: 'an odd range' }
    expectEqual(
      modWarnings(unknown, { versionId: '1.21.4', loader: 'fabric', rangeMatcher: matchesVersionRange }),
      [],
      'unknown is silence, not a false alarm'
    )
  })
}

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

console.log('\ncrash reports')
{
  const report = [
    '---- Minecraft Crash Report ----',
    '// Uh...',
    '',
    'Time: 2026-10-14 12:00:00',
    'Description: Rendering overlay',
    '',
    'java.lang.NullPointerException: Rendering overlay',
    '\tat net.minecraft.client.renderer.LevelRenderer.render(LevelRenderer.java:1234)',
    'Caused by: java.lang.RuntimeException: Could not execute entrypoint stage',
    '\tat net.fabricmc.loader.impl.entrypoint.EntrypointUtils.invoke(EntrypointUtils.java:51)',
    'Caused by: java.lang.NoSuchMethodError: net.fabricmod.core.Helper.thing()V',
    '\tat net.fabricmod.mixin.HelperMixin.method(HelperMixin.java:42)',
    '',
    'Minecraft Version: 1.21.4',
    'Java Version: 21.0.5, Microsoft',
    '',
    '-- Mod List --',
    '\tsodium{0.6.13} [Sodium] (sodium.jar)',
    '\tfabricmod{1.0} [Fabric Mod] (fabricmod.jar)',
    ''
  ].join('\n')

  check('reads a crash report\u2019s own summary and its root cause', () => {
    const summary = summarizeReport(report)
    expectEqual(summary.description, 'Rendering overlay', 'the description')
    expectEqual(
      summary.exception,
      'java.lang.NoSuchMethodError: net.fabricmod.core.Helper.thing()V',
      'the deepest `Caused by`, not the wrapper'
    )
    expectEqual(summary.at, 'at net.fabricmod.mixin.HelperMixin.method(HelperMixin.java:42)', 'the frame that threw it')
    expectEqual(summary.minecraftVersion, '1.21.4', 'the game version')
    expectEqual(summary.javaVersion, '21.0.5, Microsoft', 'the java version')
    expectEqual(summary.mods, ['sodium', 'fabricmod'], 'the mod list')
  })

  check('names the cause instead of handing over the trace', () => {
    const findings = diagnose(report)
    expect(findings.length > 0, 'something is found')
    const incompatible = findings.find((finding) => finding.id === 'incompatible-mod')
    expect(incompatible !== undefined, 'a mod built against another version is named')
    expectEqual(incompatible?.severity, 'error', 'as an error')
    expect(
      incompatible?.hints.some((hint) => hint.includes('another game version')) === true,
      'with something to do about it'
    )
    expect(
      incompatible?.evidence.includes('NoSuchMethodError') === true,
      'and the line it was read from'
    )
  })

  check('reads the missing-dependency block a loader writes', () => {
    const forgeLike = [
      'net.minecraftforge.fml.ModLoadingException: Missing or unsupported mandatory dependencies:',
      '\tMod ID: \u2018create\u2019, Requested by: \u2018steam\u2019, Expected range: \u2018[0.5.1,)\u2019, Actual: \u2018missing\u2019',
      '\tMod ID: \u2018flywheel\u2019, Requested by: \u2018create\u2019, Expected range: \u2018*\u2019, Actual: \u2018missing\u2019'
    ].join('\n')
    expectEqual(missingDependencies(forgeLike), ['create', 'flywheel'], 'both mod ids')
    const findings = diagnose(forgeLike)
    const missing = findings.find((finding) => finding.id === 'missing-dependency')
    expect(missing !== undefined, 'the block is diagnosed')
    expect(
      missing?.hints[0].includes('create') === true && missing.hints[0].includes('flywheel') === true,
      'and the hint names what to install'
    )
  })

  check('recognises the failures that are not a mod at all', () => {
    expect(
      diagnose('java.lang.OutOfMemoryError: Java heap space').some((f) => f.id === 'out-of-memory'),
      'memory'
    )
    expect(
      diagnose('java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version').some(
        (f) => f.id === 'wrong-java'
      ),
      'the wrong java'
    )
    expect(
      diagnose('[Render thread/ERROR]: Failed to initialize GLFW, error 65542').some((f) => f.id === 'graphics'),
      'graphics'
    )
    expect(
      diagnose('java.lang.Exception: Duplicate mods found for mod id examplemod').some((f) => f.id === 'duplicate-mods'),
      'two copies of a mod'
    )
    expect(
      diagnose('org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Mixin apply failed example.mixin.Thing')
        .some((f) => f.id === 'mixin'),
      'a mixin that could not apply'
    )
    expect(
      diagnose('[Client thread/WARN]: Disconnected from server: Connection reset').some((f) => f.id === 'disconnected'),
      'a dropped connection, which is not a broken installation'
    )
  })

  check('stays quiet about a log that is not a crash', () => {
    const healthy = [
      '[12:00:00] [main/INFO]: Loading Minecraft 1.21.4 with Fabric Loader 0.16.9',
      '[12:00:00] [main/INFO]: Loading 42 mods:',
      '[12:00:01] [Render thread/INFO]: Backend library: LWJGL version 3.3.3',
      '[12:00:04] [Render thread/INFO]: Sound engine started',
      '[12:00:05] [Render thread/WARN]: Unknown recipe category: mod:thing',
      '[12:00:09] [Render thread/INFO]: Stopping!'
    ].join('\n')
    expectEqual(diagnose(healthy), [], 'no findings from a clean log')

    // A mention is not a crash: the rules look for the shapes a failure has, not
    // for the word "error" anywhere in a mod's own output.
    expectEqual(
      diagnose('[12:00:00] [main/INFO]: examplemod: no errors reported during setup').length,
      0,
      'a mod saying "error" is not a crash'
    )
  })

  await acheck('reads an instance\u2019s crash reports newest first, and its log as the fallback', async () => {
    const crashInstance = instances.create({ name: 'Crashes', dir: join(root, 'crash-game'), source: 'created' })
    const crashDir = join(crashInstance.dir, 'crash-reports')
    mkdirSync(crashDir, { recursive: true })
    writeFileSync(join(crashDir, 'crash-2026-10-13_09-00-00-client.txt'), 'java.lang.Exception: older\n')
    writeFileSync(join(crashDir, 'crash-2026-10-14_12-00-00-client.txt'), report)
    mkdirSync(join(crashInstance.dir, 'logs'), { recursive: true })
    writeFileSync(join(crashInstance.dir, 'logs', 'latest.log'), '[12:00:00] [main/INFO]: nothing wrong here')

    const diagnostics = new DiagnosticService(instances)
    const read = diagnostics.report({ instanceId: crashInstance.id })
    expectEqual(read.crashes.length, 2, 'both reports are listed')
    expectEqual(read.source, 'crash-2026-10-14_12-00-00-client.txt', 'the newest one is the source')
    expectEqual(read.crashes[0].description, 'Rendering overlay', 'with its own description')
    expect(read.findings.length > 0, 'and findings')
    expectEqual(read.latestLog.exists, true, 'the log is read too')

    const chosen = diagnostics.report({ instanceId: crashInstance.id, name: 'crash-2026-10-13_09-00-00-client.txt' })
    expectEqual(chosen.source, 'crash-2026-10-13_09-00-00-client.txt', 'a crash can be chosen by name')

    // With no crash report at all, the log is the evidence.
    rmSync(crashDir, { recursive: true, force: true })
    writeFileSync(join(crashInstance.dir, 'logs', 'latest.log'), '[12:00:00] [Render thread/ERROR]: Failed to initialize GLFW, error 65542')
    const fromLog = diagnostics.report({ instanceId: crashInstance.id })
    expectEqual(fromLog.source, 'logs/latest.log', 'the log answers when there is no report')
    expect(fromLog.findings.some((finding) => finding.id === 'graphics'), 'with its own finding')

    const empty = instances.create({ name: 'Nothing crashed', dir: join(root, 'no-crash-game'), source: 'created' })
    const nothing = diagnostics.report({ instanceId: empty.id })
    expectEqual(nothing.source, null, 'an instance with neither says so')
    expectEqual(nothing.findings, [], 'and has nothing to report')
    instances.select(instance.id)
  })
}

console.log('\nservers')
{
  check('writes and reads the varints and the two ping packets', () => {
    for (const value of [0, 1, 127, 128, 255, 300, 25565, 0x7fffffff]) {
      const bytes = writeVarInt(value)
      expectEqual(readVarInt(bytes).value, value, `varint ${value}`)
      expectEqual(readVarInt(bytes).bytes, bytes.length, `and its length, ${value}`)
    }
    expectEqual(writeVarInt(0), Buffer.from([0]), 'zero is one byte')
    expectEqual(writeVarInt(127), Buffer.from([127]), '127 still fits')
    expectEqual(writeVarInt(128), Buffer.from([0x80, 0x01]), '128 takes two')
    expectEqual(readVarInt(writeVarInt(-1)).value, 0xffffffff, 'a negative int is its unsigned self')
    expectThrows(() => readVarInt(Buffer.from([0x80, 0x80, 0x80, 0x80, 0x80, 0x01])), 'longer than five bytes')
    expectThrows(() => readVarInt(Buffer.from([0x80])), 'cut off')

    const handshake = handshakePacket('mc.example.net', 25565)
    let offset = 0
    const length = readVarInt(handshake, offset)
    expectEqual(length.value, handshake.length - length.bytes, 'the frame length is the rest of it')
    offset += length.bytes
    const id = readVarInt(handshake, offset)
    offset += id.bytes
    expectEqual(id.value, 0, 'packet id 0')
    const protocol = readVarInt(handshake, offset)
    offset += protocol.bytes
    expectEqual(protocol.value, 47, 'the protocol version a status ping sends')
    const hostLength = readVarInt(handshake, offset)
    offset += hostLength.bytes
    expectEqual(handshake.toString('utf8', offset, offset + hostLength.value), 'mc.example.net', 'the host')
    offset += hostLength.value
    expectEqual(handshake.readUInt16BE(offset), 25565, 'the port, big endian')
    offset += 2
    expectEqual(readVarInt(handshake, offset).value, 1, 'next state: status')

    const request = statusRequestPacket()
    expectEqual(request.length, 2, 'the status request is a length and an id')
  })

  check('reads a status response out of the frame it arrives in', () => {
    const payload = JSON.stringify({ version: { name: '1.21.4', protocol: 769 } })
    const bytes = Buffer.from(payload, 'utf8')
    const body = Buffer.concat([writeVarInt(0), writeVarInt(bytes.length), bytes])
    const frame = Buffer.concat([writeVarInt(body.length), body])
    expectEqual(statusJsonFromFrame(frame), payload, 'the JSON comes back out')
    expectEqual(expectedFrameLength(frame), frame.length, 'and the frame length is known')

    // A response longer than 127 bytes has a two-byte length header, which is
    // the case where a first chunk can hold half of it.
    const long = Buffer.from(JSON.stringify({ description: 'x'.repeat(300) }), 'utf8')
    const longBody = Buffer.concat([writeVarInt(0), writeVarInt(long.length), long])
    const longFrame = Buffer.concat([writeVarInt(longBody.length), longBody])
    expectEqual(expectedFrameLength(longFrame.subarray(0, 1)), null, 'a half-read length header is not guessed at')
    expectEqual(expectedFrameLength(longFrame), longFrame.length, 'and is known once it is whole')
  })

  check('flattens a server description the way the game renders it', () => {
    expectEqual(flattenChat('A Minecraft Server'), 'A Minecraft Server', 'plain text')
    expectEqual(flattenChat({ text: 'Hello ', extra: ['world'] }), 'Hello world', 'text plus extra')
    expectEqual(
      flattenChat({ text: '§aGreen§r', extra: [{ text: ' and §lbold' }] }),
      'Green and bold',
      'colour codes are stripped, not shown'
    )
    expectEqual(flattenChat({ translate: 'multiplayer.status.old' }), 'multiplayer.status.old', 'a translatable one')
    expectEqual(flattenChat(undefined), '', 'nothing to show')
  })

  check('reads a status response into what the list shows', () => {
    const status = parseServerStatus(
      {
        version: { name: 'Paper 1.21.4', protocol: 769 },
        players: { online: 3, max: 20, sample: [{ name: 'Steve', id: 'uuid' }, {}, 'junk'] },
        description: { text: 'A real server' },
        favicon: 'data:image/png;base64,AAAA'
      },
      { host: 'mc.example.net', port: 25565, latencyMs: 42 }
    )
    expectEqual(status.version, 'Paper 1.21.4', 'the version')
    expectEqual(status.players, { online: 3, max: 20, sample: [{ name: 'Steve', id: 'uuid' }] }, 'the players, without the junk')
    expectEqual(status.motd, 'A real server', 'the description')
    expectEqual(status.icon, 'data:image/png;base64,AAAA', 'the icon')
    expectEqual(describeStatus(status), 'Paper 1.21.4 · 3/20 players · 42 ms', 'and how it reads')
    expectEqual(
      describeStatus(offlineStatus({ host: 'x', port: 1, error: 'Nothing is listening there.' })),
      'Nothing is listening there.',
      'an offline server says why'
    )
  })

  check('splits and joins the addresses the game writes', () => {
    expectEqual(splitAddress('mc.example.net'), { host: 'mc.example.net', port: 25565 }, 'no port')
    expectEqual(splitAddress('mc.example.net:25566'), { host: 'mc.example.net', port: 25566 }, 'a port')
    expectEqual(splitAddress('[::1]:25566'), { host: '::1', port: 25566 }, 'bracketed IPv6')
    expectEqual(splitAddress('::1'), { host: '::1', port: 25565 }, 'bare IPv6 is not a port')
    expectEqual(splitAddress('host:notaport'), { host: 'host:notaport', port: 25565 }, 'a port that is not one')
    expectEqual(joinAddress('mc.example.net', 25565), 'mc.example.net', 'the default port is left out')
    expectEqual(joinAddress('mc.example.net', 25566), 'mc.example.net:25566', 'and a real one is kept')
  })

  check("adds the launcher's servers to the game's own list without losing any", () => {
    const first = writeServersDat([
      serverCompound({ name: 'In-game', host: 'mc.example.net', port: 25565 }),
      {
        // A server the launcher knows nothing about, with fields it does not use.
        name: { tag: 'string', value: 'Someone else\u2019s' },
        ip: { tag: 'string', value: 'other.example.net:25566' },
        icon: { tag: 'string', value: 'data:image/png;base64,BBBB' },
        acceptTextures: { tag: 'byte', value: 1 }
      }
    ])
    const existing = readServersDat(first)
    expectEqual(existing.servers.length, 2, 'both read back')
    expectEqual(existing.servers[1].port, 25566, 'including the non-default port')

    const merged = mergeServerEntries(existing.entries, [
      { name: 'Renamed', host: 'mc.example.net', port: 25565 },
      { name: 'New', host: 'new.example.net', port: 25565 }
    ])
    expectEqual(merged.added, 1, 'only the one that was not there arrives')
    expectEqual(merged.entries.length, 3, 'and nothing is lost')
    const after = readServersDat(writeServersDat(merged.entries))
    expectEqual(after.servers[0].name, 'Renamed', 'the launcher\u2019s name wins for a server it has')
    expect(
      after.entries[1].icon !== undefined && after.entries[1].acceptTextures !== undefined,
      'an untouched server keeps every field it had'
    )

    // Round tripped through the file itself, which is the shape that matters.
    const bytes = writeNbt({ name: '', root: writeServersDat(merged.entries) })
    expectEqual(readServersDat(readNbt(bytes).root).servers.length, 3, 'three servers through a real file')
  })

  await acheck('reads and writes the game\u2019s own servers.dat, and refuses one it cannot read', async () => {
    const datInstance = instances.create({ name: 'Servers', dir: join(root, 'servers-game'), source: 'created' })
    expectEqual(servers.gameServers({ instanceId: datInstance.id }).total, 0, 'no file yet')

    servers.add({ name: 'Hypixel', host: 'mc.hypixel.net', port: 25565 })
    const launcherList = servers.list().length
    const written = servers.syncGameServers({ instanceId: datInstance.id })
    expectEqual(written.added, launcherList, 'every server this launcher has is written')
    expect(existsSync(written.path), 'servers.dat exists now')
    expectEqual(written.total, launcherList, 'and the game\u2019s list is empty otherwise')

    // A server added in the game itself must survive the next sync.
    const existing = readServersDat(readNbt(readFileSync(written.path)).root)
    const gameOnly = mergeServerEntries(existing.entries, [
      { name: 'From the game', host: 'lan.example.net', port: 25565 }
    ])
    writeFileSync(written.path, writeNbt({ name: '', root: writeServersDat(gameOnly.entries) }))
    const second = servers.syncGameServers({ instanceId: datInstance.id })
    expectEqual(second.added, 0, 'nothing new the second time')
    expectEqual(second.total, launcherList + 1, 'and the game\u2019s own server is still there')
    expect(
      second.servers.some((server) => server.name === 'From the game'),
      'by name'
    )

    // A file that is not a server list is reported, never overwritten.
    writeFileSync(written.path, Buffer.from('not nbt at all'))
    expect(servers.gameServers({ instanceId: datInstance.id }).problem !== null, 'the read says what is wrong')
    expectThrows(() => servers.syncGameServers({ instanceId: datInstance.id }), 'Rename it and sync again')
    expectEqual(
      readFileSync(written.path, 'utf8'),
      'not nbt at all',
      'and the file is left exactly as it was'
    )
    servers.remove(servers.list()[0].id)
    servers.remove(servers.list()[0].id)
    instances.select(instance.id)
  })
}

console.log('\nworlds')
{
  const sample: Record<string, NbtValue> = {
    Data: {
      tag: 'compound',
      value: {
        LevelName: { tag: 'string', value: 'My World' },
        LastPlayed: { tag: 'long', value: Date.UTC(2026, 9, 1, 12) },
        GameType: { tag: 'int', value: 1 },
        hardcore: { tag: 'byte', value: 1 },
        Version: { tag: 'compound', value: { Name: { tag: 'string', value: '1.21.4' } } },
        seed: { tag: 'longArray', value: [1, 2] },
        stats: { tag: 'intArray', value: [7, 8] },
        flags: { tag: 'byteArray', value: [1, 0, 1] },
        time: { tag: 'double', value: 1.5 },
        ratio: { tag: 'float', value: 0.5 },
        daylight: { tag: 'double', value: 0.75 },
        players: { tag: 'list', element: 'string', items: [{ tag: 'string', value: 'Steve' }] },
        empty: { tag: 'list', element: 'end', items: [] }
      }
    }
  }

  check('reads back everything it writes, gzipped or not', () => {
    for (const gzipped of [true, false]) {
      const bytes = writeNbt({ name: '', root: sample, gzipped })
      expectEqual(isGzipped(bytes), gzipped, gzipped ? 'gzipped on the way out' : 'plain on the way out')
      const read = readNbt(bytes)
      expectEqual(nbtScalar(read.root, 'Data.LevelName'), 'My World', 'a string')
      expectEqual(
        nbtScalar(read.root, 'Data.LastPlayed'),
        Date.UTC(2026, 9, 1, 12),
        'a long, which is how a world says when it was played'
      )
      expectEqual(nbtScalar(read.root, 'Data.Version.Name'), '1.21.4', 'a nested compound')
      expectEqual(nbtAt(read.root, 'Data.seed'), { tag: 'longArray', value: [1, 2] }, 'a long array')
      expectEqual(nbtAt(read.root, 'Data.flags'), { tag: 'byteArray', value: [1, 0, 1] }, 'a byte array')
      expectEqual(
        nbtAt(read.root, 'Data.players'),
        { tag: 'list', element: 'string', items: [{ tag: 'string', value: 'Steve' }] },
        'a list of strings'
      )
      expectEqual(nbtAt(read.root, 'Data.empty'), { tag: 'list', element: 'end', items: [] }, 'an empty list')
      expectEqual(nbtScalar(read.root, 'Data.ratio'), 0.5, 'a float')
    }
  })

  check('says so when a level.dat is truncated', () => {
    const bytes = writeNbt({ name: '', root: sample, gzipped: false })
    expectThrows(() => readNbt(bytes.subarray(0, bytes.length - 20)), 'part-way through a value')
    expectThrows(() => readNbt(Buffer.from([1, 2, 3])), 'expected a compound')
  })

  check('describes a world from its own level.dat, and survives one it cannot read', () => {
    const data = nbtAt(sample, 'Data')
    const world = describeWorld({
      folder: 'some-folder',
      bytes: 1024,
      level: (data as { value: Record<string, NbtValue> }).value
    })
    expectEqual(world.name, 'My World', 'the name the game shows, not the folder')
    expectEqual(world.version, '1.21.4', 'the version that last wrote it')
    expectEqual(world.gameMode, 'creative', 'the game mode')
    expect(world.hardcore, 'hardcore is reported')
    expectEqual(world.lastPlayed, new Date(Date.UTC(2026, 9, 1, 12)).toISOString(), 'when it was played')

    const broken = describeWorld({ folder: 'lost', bytes: 0, level: null, problem: 'no level.dat' })
    expectEqual(broken.name, 'lost', 'a world that cannot be read still has a name')
    expectEqual(broken.problem, 'no level.dat', 'and says why')
  })

  check('keeps an imported world from landing on one that is already there', () => {
    expectEqual(uniqueWorldFolder('New World', []), 'New World', 'nothing in the way')
    expectEqual(uniqueWorldFolder('New World', ['New World']), 'New World 2', 'one already there')
    expectEqual(
      uniqueWorldFolder('New World', ['New World', 'New World 2']),
      'New World 3',
      'two already there'
    )
    expectEqual(sanitizeWorldFolder('bad/name:here'), 'bad-name-here', 'a folder name the OS accepts')
    expectEqual(sanitizeWorldFolder('...'), 'world', 'and never an empty one')
    expectEqual(backupFileName(new Date(2026, 9, 14, 15, 30, 12)), 'saves-20261014-153012.zip', 'manual')
    expectEqual(
      backupFileName(new Date(2026, 9, 14, 15, 30, 12), 'before-change'),
      'before-20261014-153012.zip',
      'automatic, and named so it reads as such later'
    )
  })

  check('finds the one world inside an archive, and refuses two', () => {
    expectEqual(detectWorldRoot(['level.dat', 'region/r.0.0.mca']), '', 'a bare world')
    expectEqual(detectWorldRoot(['My World/level.dat', 'My World/region/r.0.0.mca']), 'My World', 'inside a folder')
    expectEqual(detectWorldRoot(['a/level.dat', 'b/level.dat']), null, 'two worlds')
    expectEqual(detectWorldRoot(['a/notes.txt']), null, 'no level.dat at all')
    expectEqual(detectWorldRoot([]), null, 'nothing')
  })

  check('finds the worlds inside a backup, and nothing else', () => {
    expectEqual(
      worldsInBackup([
        'saves/My World/level.dat',
        'saves/My World/region/r.0.0.mca',
        'saves/Second/level.dat'
      ]),
      [
        { name: 'My World', prefix: 'saves/My World/' },
        { name: 'Second', prefix: 'saves/Second/' }
      ],
      'every folder with a level.dat of its own is a world'
    )
    expectEqual(
      worldsInBackup(['saves/notes.txt', 'saves/Other/region/r.0.0.mca']),
      [],
      'a folder with no level.dat of its own is not one'
    )
    expectEqual(
      worldsInBackup(['My World/level.dat', 'My World/region/r.0.0.mca']),
      [{ name: 'My World', prefix: 'My World/' }],
      'an export of a single world reads too, without the saves/ prefix'
    )
    expectEqual(worldsInBackup([]), [], 'nothing')
  })

  const zipDir = join(root, 'zip-write')

  await acheck('writes a zip that the reader reads back, streaming a file in', async () => {
    mkdirSync(zipDir, { recursive: true })
    const big = join(zipDir, 'region.mca')
    writeFileSync(big, Buffer.alloc(64 * 1024, 7))
    const dest = join(zipDir, 'out.zip')
    await writeZip(dest, [
      { name: 'world/level.dat', contents: Buffer.from('level-bytes') },
      { name: 'world/region/r.0.0.mca', path: big },
      { name: 'repeated.txt', contents: Buffer.from('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa') }
    ])

    const buffer = readFileSync(dest)
    const entries = listZipEntries(buffer)
    expectEqual(entries.map((entry) => entry.name), ['world/level.dat', 'world/region/r.0.0.mca', 'repeated.txt'], 'names and order')
    expectEqual(
      readZipEntry(buffer, entries[0]).toString('utf8'),
      'level-bytes',
      'an in-memory entry survives the round trip'
    )
    expectEqual(
      readZipEntry(buffer, entries[1]).length,
      64 * 1024,
      'a streamed file arrives whole'
    )
    expectEqual(readZipEntry(buffer, entries[1])[0], 7, 'and byte for byte')
    // Deflating costs a few bytes of its own, so content that does not get
    // smaller is stored: an 11-byte file deflates to 13 and would grow.
    expectEqual(entries[0].method, 0, 'content that does not compress is stored')
    expectEqual(entries[1].method, 0, 'a streamed file is stored, not deflated')
    expectEqual(entries[2].method, 8, 'content that does compress is deflated')
    expectEqual(listZipEntries(buffer)[2].uncompressedSize, 32, 'and the sizes are the real ones')
  })

  const saves = new SaveService(instances, () => {})
  const worldInstance = instances.create({ name: 'Worlds', dir: join(root, 'worlds-game'), source: 'created' })

  await acheck('lists, backs up, exports and imports a world', async () => {
    expectEqual((await saves.backupBeforeChange(worldInstance.id)), null, 'nothing to back up yet')

    const savesDir = join(worldInstance.dir, 'saves', 'A World')
    mkdirSync(join(savesDir, 'region'), { recursive: true })
    writeFileSync(join(savesDir, 'level.dat'), writeNbt({ name: '', root: sample }))
    writeFileSync(join(savesDir, 'region', 'r.0.0.mca'), Buffer.alloc(2048, 3))

    const listed = saves.list(worldInstance.id)
    expectEqual(listed.worlds.length, 1, 'one world')
    expectEqual(listed.worlds[0].name, 'My World', 'named by its own level.dat')
    expectEqual(listed.worlds[0].version, '1.21.4', 'and its version')
    expectEqual(listed.worlds[0].problem, null, 'readable')
    expect(listed.worlds[0].bytes > 2048, 'its size is summed from the files')

    const backup = await saves.backup({ instanceId: worldInstance.id })
    expect(existsSync(backup.path), 'the backup file is written')
    const backedUp = listZipEntries(readFileSync(backup.path))
    expect(
      backedUp.some((entry) => entry.name === 'saves/A World/level.dat'),
      'the world is inside it, under saves/'
    )
    expectEqual(saves.list(worldInstance.id).backups.length, 1, 'and the list sees it')

    const exportedDir = join(root, 'exports')
    mkdirSync(exportedDir, { recursive: true })
    const exported = await saves.exportWorld({
      instanceId: worldInstance.id,
      folder: 'A World',
      destDir: exportedDir
    })
    expectEqual(exported.files, 2, 'both files exported')
    expect(existsSync(exported.path), 'the export is a file')

    // Back in: the same world, from the archive, under a folder that is free.
    const imported = await saves.importWorld({ instanceId: worldInstance.id, source: exported.path })
    expectEqual(imported.folder, 'A World 2', 'the name that was taken gets a number')
    expect(existsSync(join(worldInstance.dir, 'saves', 'A World 2', 'level.dat')), 'the world is there')
    expectEqual(saves.list(worldInstance.id).worlds.length, 2, 'and the game will list both')

    // A world copied out of another installation is a folder, not an archive.
    const loose = join(root, 'loose-world')
    mkdirSync(loose, { recursive: true })
    writeFileSync(join(loose, 'level.dat'), writeNbt({ name: '', root: sample }))
    const fromFolder = await saves.importWorld({ instanceId: worldInstance.id, source: loose })
    expectEqual(fromFolder.folder, 'loose-world', 'the folder imports as itself')
    expectEqual(saves.list(worldInstance.id).worlds.length, 3, 'three worlds now')

    await expectRejects(
      () => saves.importWorld({ instanceId: worldInstance.id, source: join(root, 'not-a-world.txt') }),
      'does not exist'
    )

    // Creating an instance selects it; the checks after this one import into the
    // selected instance, so put the first one back.
    instances.select(instance.id)
  })

  await acheck('restores a backup beside the worlds that are there, never over them', async () => {
    const backup = await saves.backup({ instanceId: worldInstance.id })
    const before = saves.list(worldInstance.id).worlds.map((world) => world.folder)
    const restored = await saves.restoreBackup({ instanceId: worldInstance.id, path: backup.path })
    const after = saves.list(worldInstance.id).worlds

    expectEqual(restored.worlds.length, before.length, 'every world in the archive comes back')
    expectEqual(restored.skipped, 0, 'and nothing in the archive is left out')
    expect(
      restored.worlds.every((world) => !before.includes(world.folder)),
      'each one lands in a folder of its own rather than over an existing world'
    )
    expectEqual(after.length, before.length * 2, 'the worlds that were already there are still there')
    expect(after.every((world) => world.problem === null), 'and every copy of them is readable')
    expectEqual(
      [...restored.worlds.map((world) => world.name)].sort(),
      [...before].sort(),
      'the archive is read as exactly the worlds it holds'
    )

    // A file that is not a backup is refused by name, not half-restored.
    await expectRejects(
      () => saves.restoreBackup({ instanceId: worldInstance.id, path: join(root, 'not-a-world.txt') }),
      'does not exist'
    )
  })

  await acheck('backs the worlds up before an install that changes the game', async () => {
    const installerWithSaves = new InstallService(
      environment,
      tasks,
      installations,
      instances,
      () => {},
      {
        fetchImpl: async () => {
          throw new Error('offline')
        }
      },
      saves
    )
    // The install cannot get past its first request here, which is the point:
    // the worlds have to be safe before anything is written, not after.
    await expectRejects(
      () =>
        installerWithSaves.install({
          versionId: '1.12.2',
          loader: 'vanilla',
          instanceId: worldInstance.id
        }),
      'offline'
    )
    const backups = readdirSync(join(worldInstance.dir, 'backups'))
    expect(
      backups.some((name) => name.startsWith('before-')),
      'a backup was taken even though the install then failed'
    )
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

check('plans a whole chain, asset index and all, the way a launch checks it', () => {
  const game: VersionJson = {
    ...vanillaLike,
    assetIndex: { id: '19', url: 'https://piston/19.json', sha1: 'dd44', size: 500 }
  }
  const loader: VersionJson = {
    id: 'fabric-loader-0.16.9-1.21.4',
    inheritsFrom: '1.21.4',
    libraries: [
      {
        name: 'org.example:common:1.0',
        downloads: { artifact: { path: 'org/example/common/1.0/common-1.0.jar', url: 'https://loader/common.jar' } }
      }
    ]
  }
  const objects = { objects: { 'a/sound.ogg': { hash: 'aabbcc', size: 5 } } }
  const plan = planInstallation({
    gameDir: planDir,
    chain: [loader, game],
    ctx: planCtx,
    assetIndex: objects
  })
  const dests = plan.files.map((file) => file.dest)
  expect(dests.includes(join(planDir, 'versions', '1.21.4', '1.21.4.jar')), 'the game version brings the client jar')
  expect(
    dests.includes(join(planDir, 'assets', 'indexes', '19.json')),
    'the asset index itself is part of the plan'
  )
  expect(
    dests.includes(join(planDir, 'assets', 'objects', 'aa', 'aabbcc')),
    'and the objects it lists'
  )
  expectEqual(
    plan.files.find((file) => file.label === 'asset index 19')?.sha1,
    'dd44',
    'the index is verified like any other file'
  )
  expectEqual(plan.assetIndex?.id, '19', 'the index the plan was built from')
  expectEqual(plan.nativeJars.length, 1, 'the natives come through the chain as well')

  // Without the index parsed there are no objects — but the index file is still
  // planned, so a first pass over an empty game directory fetches it and the
  // next one can plan what it lists.
  const withoutIndex = planInstallation({ gameDir: planDir, chain: [loader, game], ctx: planCtx })
  expect(
    withoutIndex.files.some((file) => file.dest.endsWith(join('assets', 'indexes', '19.json'))),
    'the index is planned even when its objects are not'
  )
  expectEqual(
    withoutIndex.files.filter((file) => file.dest.startsWith(join(planDir, 'assets', 'objects'))).length,
    0,
    'and no object is invented from an index that was not read'
  )
})

check('refreshes only what is absent or the wrong size', () => {
  const files = [
    { url: 'u', dest: '/g/whole.jar', size: 100, label: 'whole' },
    { url: 'u', dest: '/g/truncated.jar', size: 100, label: 'truncated' },
    { url: 'u', dest: '/g/gone.jar', size: 100, label: 'gone' },
    { url: 'u', dest: '/g/unknown.jar', label: 'unknown size' }
  ]
  const sizes: Record<string, number> = {
    '/g/whole.jar': 100,
    // What a half-finished download leaves behind: a file, the wrong size.
    '/g/truncated.jar': 40,
    '/g/unknown.jar': 7
  }
  const stale = needsRefresh(files, (path) => (path in sizes ? sizes[path] : null)).map((file) => file.label)
  expectEqual(stale, ['truncated', 'gone'], 'a file with no declared size is taken as it is')
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
      'icons/icon_16x16.png': { hash: 'ddeeff', size: 9 },
      // What 1.12.2 and 1.20.1 really ship: two names, one hash, one file.
      'minecraft/icons/icon_16x16.png': { hash: 'ddeeff', size: 9 }
    }
  })
  expectEqual(files.length, 2, 'one file per object, not one per name')
  expectEqual(
    files[0].dest,
    join(planDir, 'assets', 'objects', 'aa', 'aabbcc'),
    'the first two hex digits are a directory'
  )
  expectEqual(files[0].url, 'https://resources.download.minecraft.net/aa/aabbcc', 'the object host')
  expectEqual(files[0].sha1, 'aabbcc', 'the hash is the checksum as well as the path')
  expectEqual(
    new Set(files.map((file) => file.dest)).size,
    files.length,
    'no destination is planned twice, which is what had two downloads racing for one file'
  )
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

console.log('\nwhat the store has installed')
{
  const storeRoot = join(root, 'store-record')
  const install = (filename: string, projectId = 'AANobbMI'): StoreInstall => ({
    projectId,
    title: 'Sodium',
    versionNumber: 'mc1.21.4-0.6.13',
    filename,
    kind: 'mod',
    at: '2026-10-14T12:00:00.000Z'
  })

  check('remembers an install, and forgets one whose file is gone', () => {
    mkdirSync(join(storeRoot, 'mods'), { recursive: true })
    expectEqual(readStoreInstalls(storeRoot), [], 'nothing recorded yet')

    writeFileSync(join(storeRoot, 'mods', 'sodium.jar'), 'jar')
    writeStoreInstalls(storeRoot, [install('sodium.jar'), install('gone.jar', 'other')])
    expectEqual(readStoreInstalls(storeRoot).length, 2, 'both are in the record')

    const present = presentInstalls(storeRoot, readStoreInstalls(storeRoot))
    expectEqual(present.map((entry) => entry.filename), ['sodium.jar'], 'only the one on disk counts')
    expectEqual(present[0].projectId, 'AANobbMI', 'and it still names its project')

    // A record nobody can read is a record with nothing in it, not a crash.
    writeFileSync(join(storeRoot, '.toolhubs', 'store.json'), '{ not json')
    expectEqual(readStoreInstalls(storeRoot), [], 'a corrupt record reads as empty')
  })

  check('drops a record entry that names no folder, instead of throwing on every read', () => {
    // An entry with no file name, or a kind that is not one of the three
    // folders, used to reach `join` as an undefined path: every later read of
    // the record threw the runtime's own "path argument" error, and the entry
    // survived each rewrite to do it again.
    const unusable = [
      { projectId: 'p', title: 'Sodium', versionNumber: '1', kind: 'mod', at: 'now' },
      { projectId: 'p', title: 'Sodium', versionNumber: '1', filename: 'sodium.jar', at: 'now' },
      {
        projectId: 'p',
        title: 'Sodium',
        versionNumber: '1',
        filename: 'sodium.jar',
        kind: 'modpack',
        at: 'now'
      }
    ]
    writeFileSync(
      join(storeRoot, '.toolhubs', 'store.json'),
      JSON.stringify({ version: 1, installs: [...unusable, install('sodium.jar')] })
    )

    const read = readStoreInstalls(storeRoot)
    expectEqual(read.map((entry) => entry.filename), ['sodium.jar'], 'only the usable one is kept')
    expectEqual(
      presentInstalls(storeRoot, [...read, ...(unusable as never[])]).map((entry) => entry.filename),
      ['sodium.jar'],
      'and a caller that hands it a bad entry back is not thrown at either'
    )
  })
}

console.log('\nresources')
{
  check('refuses a kind that has no folder rather than returning undefined', () => {
    expectEqual(folderForKind('mod'), 'mods', 'the folder a mod goes in')
    expectEqual(folderForKind('resourcepack'), 'resourcepacks', 'and a pack')
    expectEqual(folderForKind('shaderpack'), 'shaderpacks', 'and a shader')
    // The kind arrives over the service channel, where its type is not enforced;
    // naming it in the refusal is the difference between a two-second fix and a
    // hunt for a path argument that was never a path.
    expectThrows(() => folderForKind('modpack' as never), 'Unknown resource kind "modpack"')
  })
}

console.log('\nimporting what is already on disk')
{
  const importRoot = join(root, 'import')

  check('copies a single version folder into the launcher\u2019s game folder', () => {
    const source = join(importRoot, 'downloaded', 'Imported-1.0')
    mkdirSync(source, { recursive: true })
    writeFileSync(
      join(source, 'Imported-1.0.json'),
      JSON.stringify({ id: 'Imported-1.0', mainClass: 'net.minecraft.client.main.Main' })
    )
    const result = instances.importFrom(source)
    expectEqual(result.versionCount, 1, 'one version was copied')
    expect(
      existsSync(join(environment.sharedDir, 'versions', 'Imported-1.0', 'Imported-1.0.json')),
      'the json landed under versions/ in the shared game folder'
    )
    // A version is not one instance's business: the instance it happened to be
    // selected when it was imported keeps nothing of the game in it.
    expect(
      !existsSync(join(gameDir, 'versions', 'Imported-1.0')),
      'and not inside the instance'
    )
    expect(result.message.includes('every instance can play it'), 'the message says where it went')
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

console.log('\nModrinth')
{
  check('filters a search by the instance it is for', () => {
    expectEqual(
      JSON.parse(searchFacets({ kind: 'mod', gameVersion: '1.21.4', loader: 'fabric' })),
      [['project_type:mod'], ['versions:1.21.4'], ['categories:fabric']],
      'mods on fabric'
    )
    expectEqual(
      JSON.parse(searchFacets({ kind: 'shaderpack', loader: null })),
      [['project_type:shader'], ['categories:iris', 'categories:optifine', 'categories:vanilla']],
      'shaders, which load in vanilla too'
    )
    // Quilt runs Fabric mods, so a Quilt instance may be offered either.
    expectEqual(
      versionsQuery({ gameVersion: '1.21.4', loader: 'quilt' }),
      '?game_versions=%5B%221.21.4%22%5D&loaders=%5B%22quilt%22%2C%22fabric%22%5D',
      'quilt accepts fabric mods'
    )
    expectEqual(versionsQuery({}), '', 'no filters, no query string')
    expect(userAgent('mc-launcher', '0.4.0').startsWith('mc-launcher/0.4.0 ('), 'the user agent names us')
  })

  check('reads a search response and skips what it cannot use', () => {
    const search = parseSearchResponse({
      total_hits: 2,
      offset: 0,
      limit: 20,
      hits: [
        {
          project_id: 'AANobbMI',
          slug: 'sodium',
          title: 'Sodium',
          description: 'Modern rendering engine',
          project_type: 'mod',
          downloads: 12345678,
          follows: 3000,
          icon_url: 'https://cdn.example/sodium.png',
          versions: ['1.21.4', '1.21.3'],
          categories: ['fabric', 'optimization'],
          latest_version: 'abc'
        },
        { title: 'no project id, so it is skipped' }
      ]
    })
    expectEqual(search.hits.length, 1, 'the unusable hit is dropped')
    expectEqual(search.hits[0].title, 'Sodium', 'title')
    expectEqual(search.hits[0].loaders, ['fabric'], 'only loader-ish categories')
    expectEqual(search.totalHits, 2, 'the total is passed through')
  })

  check('picks the newest release that fits, and says when nothing does', () => {
    const versions = parseVersionList([
      {
        id: 'v1',
        project_id: 'p',
        name: '1.0.0',
        version_number: '1.0.0',
        version_type: 'beta',
        game_versions: ['1.21.4'],
        loaders: ['fabric'],
        date_published: '2026-01-01T00:00:00Z',
        files: [{ url: 'https://cdn/x.jar', filename: 'x.jar', primary: true, fileSize: 10 }]
      },
      {
        id: 'v2',
        project_id: 'p',
        name: '1.0.1',
        version_number: '1.0.1',
        version_type: 'release',
        game_versions: ['1.21.4'],
        loaders: ['fabric'],
        date_published: '2026-02-01T00:00:00Z',
        files: [{ url: 'https://cdn/y.jar', filename: 'y.jar', primary: true, fileSize: 20 }]
      },
      {
        id: 'v3',
        project_id: 'p',
        name: '2.0.0',
        version_number: '2.0.0',
        version_type: 'release',
        game_versions: ['1.20.1'],
        loaders: ['fabric'],
        date_published: '2026-03-01T00:00:00Z',
        files: []
      }
    ])
    expectEqual(bestVersion(versions, { gameVersion: '1.21.4', loader: 'fabric' })?.id, 'v2', 'release first')
    expectEqual(bestVersion(versions, { gameVersion: '1.21.4', loader: 'quilt' })?.id, 'v2', 'quilt accepts fabric')
    expectEqual(bestVersion(versions, { gameVersion: '1.20.1', loader: 'fabric' })?.id, 'v3', 'the other one')
    expectEqual(bestVersion(versions, { gameVersion: '1.19.2', loader: 'fabric' }), null, 'nothing fits')
    expectEqual(primaryFile(versions[1])?.filename, 'y.jar', 'the primary file')
  })

  check('follows required dependencies and leaves the rest alone', () => {
    const [version] = parseVersionList([
      {
        id: 'v',
        project_id: 'fabric-api-project',
        name: 'Mod',
        version_number: '1',
        version_type: 'release',
        game_versions: ['1.21.4'],
        loaders: ['fabric'],
        files: [],
        dependencies: [
          { project_id: 'P7dR8mSH', dependency_type: 'required' },
          { project_id: 'other', dependency_type: 'optional' },
          { project_id: 'embedded-one', dependency_type: 'embedded' },
          { project_id: 'P7dR8mSH', dependency_type: 'required', version_id: 'pinned' }
        ]
      }
    ])
    expectEqual(
      requiredDependencies(version, { installedProjectIds: [] }).map((entry) => entry.projectId),
      ['P7dR8mSH', 'P7dR8mSH'],
      'required only, exactly as the API listed them'
    )
    expectEqual(
      requiredDependencies(version, { installedProjectIds: ['p7dr8msh'] }).length,
      0,
      'a dependency that is already there is not followed again'
    )
  })

  check('sorts a search and reads a project page', () => {
    const sorted = searchUrl({ query: 'sodium', kind: 'mod', sort: 'downloads' })
    expect(sorted.includes('index=downloads'), 'the sort reaches the API')
    expect(
      !searchUrl({ query: 'sodium', kind: 'mod', sort: 'relevance' }).includes('index='),
      'relevance is the API default and is not sent'
    )
    expectEqual(isSearchSort('downloads'), true, 'a known sort')
    expectEqual(isSearchSort('whatever'), false, 'anything else')
    expectEqual(projectTypeForKind('modpack'), 'modpack', 'a pack is its own project type')
    expect(
      searchUrl({ query: '', kind: 'modpack' }).includes('project_type%3Amodpack'),
      'and the facet says so'
    )

    const detail = parseProjectDetail({
      id: 'AANobbMI',
      slug: 'sodium',
      title: 'Sodium',
      description: 'Modern rendering engine',
      project_type: 'mod',
      icon_url: 'https://cdn/sodium.png',
      downloads: 12345678,
      followers: 3000,
      updated: '2026-10-01T00:00:00Z',
      published: '2021-01-01T00:00:00Z',
      categories: ['fabric', 'optimization'],
      game_versions: ['1.21.4'],
      loaders: ['fabric'],
      body: '# Sodium\n\nA rendering engine.',
      gallery: [{ raw_url: 'https://cdn/one.png' }, { url: 'https://cdn/two.png' }, {}],
      client_side: 'required',
      server_side: 'unsupported',
      license: { name: 'LGPL-3.0' },
      source_url: 'https://github/sodium',
      issues_url: 'https://github/sodium/issues',
      wiki_url: '',
      discord_url: 'https://discord/sodium'
    })
    expectEqual(detail?.title, 'Sodium', 'title')
    expectEqual(detail?.license, 'LGPL-3.0', 'licence')
    expectEqual(detail?.galleryUrls, ['https://cdn/one.png', 'https://cdn/two.png'], 'gallery images')
    expectEqual(
      detail?.links.map((link) => link.label),
      ['Source', 'Issues', 'Discord'],
      'links, with the empty ones left out'
    )
    expectEqual(parseProjectDetail({ title: 'no id, so not usable' }), null, 'a project needs an id')
    // Modrinth answers with the licence as an object on some routes and as a
    // string on others; both are real, and one was found by asking the API.
    expectEqual(parseProjectDetail({ id: 'x', title: 'X', license: 'MIT' })?.license, 'MIT', 'a string licence')
    expectEqual(
      parseProjectDetail({ id: 'x', title: 'X', license: { id: 'lgpl-3', name: 'LGPL-3.0' } })?.license,
      'LGPL-3.0',
      'an object licence'
    )
    expectEqual(
      projectWebUrl({ projectType: 'mod', slug: 'sodium' }),
      'https://modrinth.com/mod/sodium',
      'the browser URL'
    )
  })

  check('reads the version ranges mods actually publish', () => {
    expectEqual(matchesVersionRange('>=1.20 <1.21', '1.20.4'), true, 'fabric range, inside')
    expectEqual(matchesVersionRange('>=1.20 <1.21', '1.21.4'), false, 'fabric range, past the top')
    expectEqual(matchesVersionRange('[1.20,1.20.4)', '1.20.1'), true, 'maven range, inside')
    expectEqual(matchesVersionRange('[1.20,1.20.4)', '1.20.4'), false, 'maven range, exclusive top')
    expectEqual(matchesVersionRange('[1.20,)', '1.21.4'), true, 'maven range with no top')
    expectEqual(matchesVersionRange('1.20.x', '1.20.6'), true, 'wildcard covers the patches')
    expectEqual(matchesVersionRange('1.20.x', '1.21.1'), false, 'wildcard does not cover the next minor')
    expectEqual(matchesVersionRange('~1.20.1', '1.21.4'), false, 'tilde stays in its minor series')
    expectEqual(matchesVersionRange('>=1.20', '1.21.4'), true, 'a plain lower bound')
    expectEqual(matchesVersionRange('1.21.4', '1.21.4'), true, 'an exact version')
    expectEqual(matchesVersionRange('*', '1.21.4'), true, 'anything')
    // Unknown syntax must be unknown, not "no": a wrong warning is worse than none.
    expectEqual(matchesVersionRange('>=1.20 something-else', '1.21.4'), null, 'unreadable is null')
  })
}

console.log('\nmodpack archives, and packs this launcher writes')
{
  check('tells the two pack formats apart, and by their index only', () => {
    expectEqual(detectModpackFormat(['modrinth.index.json', 'overrides/']), 'modrinth', 'modrinth')
    expectEqual(detectModpackFormat(['manifest.json', 'mods/a.jar']), 'curseforge', 'curseforge')
    expectEqual(detectModpackFormat(['mods/a.jar']), null, 'neither')
  })

  check('offers every picker the files its own importer accepts', () => {
    // The bug this exists for: a pack dialog that offered only `.jar` and
    // `.zip` hid every Modrinth pack behind "Everything".
    expectEqual(pickerExtensions('modpack'), ['mrpack', 'zip'], 'a pack is .mrpack or .zip')
    expectEqual(pickerExtensions('mod'), ['jar'], 'a mod is a jar')
    expectEqual(pickerExtensions('resourcepack'), ['zip'], 'a resource pack is a zip')
    expectEqual(pickerExtensions('shaderpack'), ['zip'], 'a shader pack is a zip')
    expectEqual(pickerExtensions('world'), ['zip'], 'a world archive is a zip')
    expectEqual(pickerExtensions('any'), [], 'an unfiltered picker filters nothing')
    for (const [kind, preset] of Object.entries(FILE_PICKERS)) {
      expect(
        preset.extensions.length > 0 && preset.label !== '' && preset.title !== '',
        `the ${kind} picker offers something and says what it is`
      )
    }
    expect(
      PACK_EXTENSIONS.every((extension) => pickerExtensions('modpack').includes(extension.replace('.', ''))),
      'the pack picker offers every extension the importer reads'
    )
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

  check('carries what makes an instance an instance, and leaves the rest behind', () => {
    expect(packIncludes('mods/sodium.jar'), 'a mod goes in')
    expect(packIncludes('resourcepacks/x.zip'), 'a resource pack goes in')
    expect(packIncludes('config/fabric.json'), 'config goes in')
    expect(packIncludes('options.txt'), 'and the game\u2019s own options')
    expect(!packIncludes('saves/world/level.dat'), 'a world does not: a pack is something you send')
    expect(!packIncludes('backups/before.zip'), 'nor a backup of one')
    expect(!packIncludes('logs/latest.log'), 'nor a log')
    expect(!packIncludes('versions/1.21.4/1.21.4.json'), 'nor the game itself')
    expect(!packIncludes('libraries/org/example/x.jar'), 'nor its libraries')
    expect(!packIncludes('assets/objects/aa'), 'nor its assets')
    expect(!packIncludes('mods/.hidden.jar'), 'and never a dot-file, which is some tool\u2019s own state')
    expectEqual(
      packArchivePath('mods\\sodium.jar'),
      'overrides/mods/sodium.jar',
      'every file it carries is written under overrides/'
    )
  })

  check('reads the game version out of a loader profile id', () => {
    expectEqual(baseGameVersion('fabric-loader-0.16.9-1.21.4'), '1.21.4', 'the game under a loader')
    expectEqual(baseGameVersion('1.21.4'), '1.21.4', 'a plain version is already the game')
    expectEqual(baseGameVersion('1.21.4-pre1'), '1.21.4-pre1', 'a pre-release keeps its suffix')
  })

  check('builds an index an importer can read', () => {
    const vanilla = buildPackIndex({
      name: 'Plain',
      versionId: '1.0.0',
      gameVersion: '1.21.4',
      loader: null,
      loaderVersion: null
    })
    expectEqual(vanilla.formatVersion, 1, 'the format it writes')
    expectEqual(
      vanilla.dependencies,
      { minecraft: '1.21.4' },
      'a vanilla instance names no loader rather than inventing one'
    )
    expectEqual(vanilla.files, [], 'the files are in the archive, not behind a made-up url')

    const modded = buildPackIndex({
      name: 'Modded',
      versionId: '2.0.0',
      gameVersion: '1.21.4',
      loader: 'fabric',
      loaderVersion: '0.16.9',
      summary: 'a pack'
    })
    expectEqual(
      modded.dependencies,
      { minecraft: '1.21.4', 'fabric-loader': '0.16.9' },
      'the loader an importer has to install first'
    )
    expectEqual(modded.summary, 'a pack', 'a summary rides along when there is one')

    expectEqual(
      packVersionId(new Date(2026, 9, 14, 15, 3)),
      '20261014-1503',
      'a pack exported without a version of its own is stamped'
    )
  })

  const modpacks = new ModpackService(installer, instances, tasks, () => {})

  check('refuses a file that is not a pack by name, before reading it as one', () => {
    // A `.txt` chosen by mistake used to be read as a zip, which reports a
    // corrupt archive rather than the file being the wrong file.
    const notAPack = join(root, 'notes.txt')
    writeFileSync(notAPack, 'this is not an archive')
    expectThrows(() => modpacks.inspect(notAPack), 'is not a modpack file')
    expectThrows(() => modpacks.inspect(join(root, 'gone.mrpack')), 'Choose the pack again')
    expectThrows(() => modpacks.inspect(''), 'needs a file')
  })

  // A store install, end to end and with no network: the service is handed its
  // own fetch, which is also what makes the progress messages below observable.
  await acheck(
    'installs a project and the dependency it needs, saying how many bytes have arrived',
    async () => {
      const bytes = Buffer.from('sodium-bytes')
      const sha1 = createHash('sha1').update(bytes).digest('hex')
      const sha512 = createHash('sha512').update(bytes).digest('hex')
      const version = (id: string, projectId: string, needs: string | null) => ({
        id,
        project_id: projectId,
        name: id,
        version_number: '1.0.0',
        version_type: 'release',
        game_versions: ['1.21.4'],
        loaders: ['fabric'],
        date_published: '2026-10-14T00:00:00.000Z',
        dependencies: needs ? [{ project_id: needs, dependency_type: 'required' }] : [],
        files: [
          {
            url: `https://cdn.example/${id}.jar`,
            filename: `${id}.jar`,
            primary: true,
            fileSize: bytes.length,
            hashes: { sha1, sha512 }
          }
        ]
      })

      const messages: string[] = []
      registry.setEmitter((event) => {
        if (event.service === 'task' && event.event === 'progress') {
          messages.push((event.payload as { message: string }).message)
        }
      })

      const modrinth = new ModrinthService(environment, instances, modpacks, tasks, () => {}, {
        fetchImpl: async (url) => {
          if (url.includes('/project/')) {
            const projectId = url.split('/project/')[1].split('/')[0]
            const list =
              projectId === 'AANobbMI'
                ? [version('sodium', 'AANobbMI', 'P7dR8mSH')]
                : [version('fabric-api', 'P7dR8mSH', null)]
            return new Response(JSON.stringify(list), { status: 200 })
          }
          if (url.includes('/version/')) {
            return new Response(JSON.stringify(version('sodium', 'AANobbMI', 'P7dR8mSH')), { status: 200 })
          }
          return new Response(bytes, { status: 200 })
        }
      })

      const target = instances.create({
        name: 'Store install',
        versionId: 'fabric-loader-0.16.9-1.21.4',
        loader: 'fabric',
        loaderVersion: '0.16.9'
      })
      const result = await modrinth.install({
        projectId: 'AANobbMI',
        kind: 'mod',
        instanceId: target.id
      })

      expectEqual(
        result.installed.map((item) => item.filename),
        ['sodium.jar', 'fabric-api.jar'],
        'the project and the dependency it requires'
      )
      expect(existsSync(join(target.dir, 'mods', 'sodium.jar')), 'the file is in the instance')
      expectEqual(
        modrinth.installed({ instanceId: target.id }).items.length,
        2,
        'and the record says both are installed'
      )
      expect(
        messages.some((message) => / of .*(B|kB|MB)$/.test(message)),
        `a progress message says how many bytes have arrived; saw ${JSON.stringify(messages)}`
      )
    }
  )

  await acheck('writes an instance out as a pack, and refuses one with nothing in it', async () => {
    const source = instances.create({
      name: 'Share me',
      versionId: 'fabric-loader-0.16.9-1.21.4',
      loader: 'fabric',
      loaderVersion: '0.16.9'
    })
    mkdirSync(join(source.dir, 'config'), { recursive: true })
    mkdirSync(join(source.dir, 'saves', 'world'), { recursive: true })
    writeFileSync(join(source.dir, 'mods', 'sodium.jar'), 'jar')
    writeFileSync(join(source.dir, 'config', 'fabric.json'), '{"x":1}')
    writeFileSync(join(source.dir, 'options.txt'), 'fov:0.5\n')
    writeFileSync(join(source.dir, 'saves', 'world', 'level.dat'), 'save')

    const destDir = join(root, 'pack-export')
    mkdirSync(destDir, { recursive: true })
    const exported = await modpacks.export({
      instanceId: source.id,
      destDir,
      name: 'Share me',
      versionId: '1.0.0'
    })
    expectEqual(exported.path, join(destDir, 'Share me.mrpack'), 'the pack is named after itself')
    expectEqual(exported.gameVersion, '1.21.4', 'the index names the game, not the loader profile')
    expectEqual(exported.loader, 'fabric', 'and the loader underneath it')

    const buffer = readFileSync(exported.path)
    const entries = listZipEntries(buffer)
    const names = entries.map((entry) => entry.name)
    expect(names.includes('modrinth.index.json'), 'the index is in the archive')
    expectEqual(
      names.filter((name) => name.startsWith('overrides/')),
      ['overrides/config/fabric.json', 'overrides/mods/sodium.jar', 'overrides/options.txt'],
      'the instance files are carried under overrides/, and its world is not one of them'
    )

    const indexEntry = entries.find((entry) => entry.name === 'modrinth.index.json')
    expect(indexEntry !== undefined, 'the index entry is readable back')
    const index = JSON.parse(readZipEntry(buffer, indexEntry!).toString('utf8'))
    expectEqual(
      index.dependencies,
      { minecraft: '1.21.4', 'fabric-loader': '0.16.9' },
      'what an importer installs before the pack'
    )
    expectEqual(index.versionId, '1.0.0', 'the pack version it was given')

    // An instance with nothing of its own to carry is a mistake to export, not
    // an empty file to hand to someone.
    const empty = instances.create({ name: 'Nothing to share' })
    await expectRejects(() => modpacks.export({ instanceId: empty.id, destDir }), 'nothing to export')
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

  check('matches a version that names no component to a runtime Mojang publishes', () => {
    expectEqual(runtimeComponentsForMajor(8)[0], 'jre-legacy', 'Java 8')
    expectEqual(runtimeComponentsForMajor(16), ['java-runtime-alpha'], 'Java 16')
    expectEqual(runtimeComponentsForMajor(17)[0], 'java-runtime-gamma', 'Java 17, current build first')
    expectEqual(runtimeComponentsForMajor(21)[0], 'java-runtime-delta', 'Java 21')
    expectEqual(runtimeComponentsForMajor(25)[0], 'java-runtime-epsilon', 'Java 25')
    expect(
      runtimeComponentsForMajor(null).length > 1,
      'a version that names neither a component nor a major still has something to try'
    )
  })

  check('reads the Java a version needs off the release when its json does not say', () => {
    expectEqual(inferJavaMajor('1.12.2'), 8, 'the 1.12 era ran on Java 8')
    expectEqual(inferJavaMajor('1.16.5'), 8, 'and so did 1.16.5')
    expectEqual(inferJavaMajor('1.17'), 16, '1.17 moved to 16')
    expectEqual(inferJavaMajor('1.19.4'), 17, '1.18 and 1.19 on 17')
    expectEqual(inferJavaMajor('1.20.1'), 17, '1.20.1 still 17')
    expectEqual(inferJavaMajor('1.20.5'), 21, '1.20.5 was the first 21')
    expectEqual(inferJavaMajor('1.21.4'), 21, '1.21 on 21')
    expectEqual(inferJavaMajor('26.3'), null, 'a modern version json names its own Java')
    expectEqual(inferJavaMajor('custom-build'), null, 'and a name that is not a version gets no guess')
    expectEqual(inferJavaMajor(null), null, 'nothing to read')
  })

  await acheck('ignores a java path that no longer answers, and says so', async () => {
    const warnings: string[] = []
    const service = new JavaService(
      {
        userDataDir: root,
        defaultInstancesDir: join(root, 'instances'),
        sharedDir: join(root, 'game'),
        defaultGameDir: gameDir,
        launcherName: 'mc-launcher',
        launcherVersion: 'selfcheck',
        platform: 'linux',
        arch: 'x64'
      },
      (message) => warnings.push(message)
    )
    // A real binary that is not a JVM: it starts, is asked for its version, and
    // answers with something that is not one. That is what a JDK that was since
    // uninstalled leaves behind in Settings.
    let chosen: string | null = null
    try {
      const resolved = await service.resolve({
        instanceDir: join(root, 'no-runtime-here'),
        component: null,
        requiredMajor: 17,
        explicitPath: process.execPath
      })
      chosen = resolved.path
    } catch {
      // A machine with no java at all failing here is also correct behaviour.
    }
    expect(chosen !== process.execPath, 'the path that does not answer is not used')
    expect(
      warnings.some((message) => message.includes('java path in Settings')),
      'and the log says why it was passed over'
    )
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

  check("reads Mojang's runtime index and picks the build to use", () => {
    const index = {
      'windows-x64': {
        'java-runtime-epsilon': [
          {
            availability: { progress: 100 },
            manifest: { url: 'https://piston-meta/old.json', sha1: 'aa' },
            version: { name: '21.0.5.11.1', released: '2024-10-15T00:00:00+00:00' }
          },
          {
            availability: { progress: 100 },
            manifest: { url: 'https://piston-meta/new.json', sha1: 'bb' },
            version: { name: '25.0.1.9.1', released: '2026-09-02T00:00:00+00:00' }
          },
          {
            availability: { progress: 40 },
            manifest: { url: 'https://piston-meta/half.json', sha1: 'cc' },
            version: { name: '26.0.0', released: '2026-10-01T00:00:00+00:00' }
          }
        ]
      }
    }
    const chosen = findRuntimeManifest(index, { os: 'windows-x64', component: 'java-runtime-epsilon' })
    expectEqual(chosen?.manifestUrl, 'https://piston-meta/new.json', 'newest finished build')
    expectEqual(chosen?.name, '25.0.1.9.1', 'and its Mojang version')
    expectEqual(findRuntimeManifest(index, { os: 'linux', component: 'java-runtime-epsilon' }), null, 'unknown platform')
    expectEqual(findRuntimeManifest(index, { os: 'windows-x64', component: 'nope' }), null, 'unknown component')
    expectEqual(
      listRuntimeComponents(index, 'windows-x64'),
      ['java-runtime-epsilon'],
      'what the platform offers'
    )

    // A component with nothing finished is still offered, because the game may
    // name one Mojang is publishing right now.
    const halfDone = findRuntimeManifest(
      { 'windows-x64': { c: [{ availability: { progress: 40 }, manifest: { url: 'u' }, version: { name: '1' } }] } },
      { os: 'windows-x64', component: 'c' }
    )
    expectEqual(halfDone?.manifestUrl, 'u', 'the unfinished build is the only one there is')
  })

  check('reads a runtime manifest and drops what cannot be used', () => {
    const manifest = parseRuntimeManifest({
      files: {
        bin: { type: 'directory' },
        'bin/java': {
          type: 'file',
          executable: true,
          downloads: { raw: { url: 'https://raw/java', sha1: 'aa', size: 1000 }, lzma: { url: 'https://lzma/java' } }
        },
        'lib/modules': { type: 'file', downloads: { raw: { url: 'https://raw/modules', size: 2000 } } },
        'lib/lzma-only': { type: 'file', downloads: { lzma: { url: 'https://lzma/only' } } },
        'legal/java.base': { type: 'link', target: 'bin/java' }
      }
    })
    expectEqual(manifest.directories, ['bin'], 'directories are listed separately')
    expectEqual(manifest.filesToDownload.map((file) => file.path), ['bin/java', 'lib/modules'], 'only raw files')
    expectEqual(manifest.links.map((file) => file.path), ['legal/java.base'], 'links too')
    expectEqual(manifest.bytes, 3000, 'the raw size is counted for progress')
    expect(manifest.filesToDownload[0].executable, 'bin/java is marked executable')
  })

  check('installs a runtime exactly where the launcher later looks for it', () => {
    for (const platform of ['linux', 'win32', 'darwin'] as NodeJS.Platform[]) {
      const dir = runtimeDirFor({
        instanceDir: '/games/instance',
        component: 'java-runtime-epsilon',
        platformDir: runtimePlatformDir(platform)
      })
      const candidate = runtimeCandidate('/games/instance', 'java-runtime-epsilon', platform)
      expectEqual(
        `${dir}/bin/${javaExecutableName(platform)}`,
        candidate?.path,
        `the installed runtime is the one discovery finds on ${platform}`
      )
    }
    expectEqual(runtimeOsKey('win32', 'x64'), 'windows-x64', 'windows')
    expectEqual(runtimeOsKey('win32', 'arm64'), 'windows-arm64', 'windows on arm')
    expectEqual(runtimeOsKey('darwin', 'arm64'), 'mac-os-arm64', 'apple silicon')
    expectEqual(runtimeOsKey('linux', 'x64'), 'linux', 'linux')
    expectEqual(runtimeOsKey('freebsd', 'x64'), null, 'a platform Mojang does not publish for')
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

  check('gives an instance from before Java and memory per instance the defaults', () => {
    const legacy = defaultState('/instances')
    const migrated = migrateState(
      {
        ...legacy,
        instances: [
          {
            id: 'i',
            name: 'Old',
            dir: '/instances/old',
            source: 'created',
            versionId: '1.21.4',
            loader: null,
            loaderVersion: null,
            createdAt: 'now',
            lastPlayedAt: null,
            jvmArgs: []
          } as never
        ],
        settings: { ...legacy.settings, theme: undefined } as never
      },
      { exists: () => true }
    )
    expectEqual(migrated.instances[0].javaPath, null, 'no runtime of its own')
    expectEqual(migrated.instances[0].maxMemoryMb, null, 'no memory limit of its own')
    expectEqual(migrated.settings.theme, 'system', 'and the theme follows the desktop, as it did')
  })
}

rmSync(root, { recursive: true, force: true })

console.log(`\n${passed} checks passed, ${failures.length} failed`)
if (failures.length > 0) {
  for (const failure of failures) console.log(`  - ${failure}`)
  process.exit(1)
}

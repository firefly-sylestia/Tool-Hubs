import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

/**
 * Finding a Java runtime.
 *
 * The launcher used to run `java` from PATH, which fails on any machine where
 * PATH has no java — the usual case on Windows. A JVM is somewhere on nearly
 * every machine that has played Minecraft, because the official launcher ships
 * one inside the game directory, and there are the ordinary install locations
 * on top.
 *
 * The list of places to look is pure and checked without spawning anything; the
 * service probes the ones that exist and reads their version.
 */

export interface JavaCandidate {
  path: string
  /** Where it came from, so an error message can say what was searched. */
  source: string
  /** Known from the path alone (a `jdk-17` directory, a runtime component); null when it must be probed. */
  knownMajor: number | null
}

export function javaExecutableName(platform: NodeJS.Platform): string {
  return platform === 'win32' ? 'java.exe' : 'java'
}

/**
 * The directory name Mojang's runtime index uses for this platform. The same
 * name is used in its index and in the path a runtime unpacks to, so a runtime
 * this launcher downloads is one the game's own launcher would recognise too.
 */
export function runtimePlatformDir(platform: NodeJS.Platform): string {
  if (platform === 'win32') return 'windows-x64'
  if (platform === 'darwin') return 'mac-os'
  return 'linux'
}

/**
 * Mojang's bundled runtimes are named by component, and the version json says
 * which component it wants. These are the majors each component ships as a
 * *hint* for describing a runtime found on disk without starting it; anything on
 * disk is asked directly before it is used, because a component's name does not
 * change when the Java behind it does — `java-runtime-epsilon` shipped 21 and
 * ships 25 under the same name.
 */
export const RUNTIME_COMPONENT_MAJORS: Record<string, number> = {
  'java-runtime-alpha': 16,
  'java-runtime-beta': 17,
  'java-runtime-gamma': 17,
  'java-runtime-gamma-snapshot': 17,
  'java-runtime-delta': 21,
  'java-runtime-epsilon': 25,
  'jre-legacy': 8,
  'java-runtime-epsilon-legacy': 8
}

/**
 * The components to try for a Java major, best first.
 *
 * A version json that names no component still says which Java it runs on (or
 * the release it is can say it for them), and Mojang publishes one runtime per
 * era. The order matters where two components ship the same major: gamma is the
 * current 17 and beta the older build of it.
 */
export function runtimeComponentsForMajor(major: number | null): string[] {
  if (major === null) return ['java-runtime-epsilon', 'java-runtime-delta', 'java-runtime-gamma', 'jre-legacy']
  if (major <= 8) return ['jre-legacy']
  if (major <= 16) return ['java-runtime-alpha']
  if (major <= 17) return ['java-runtime-gamma', 'java-runtime-beta']
  if (major <= 21) return ['java-runtime-delta', 'java-runtime-gamma']
  return ['java-runtime-epsilon', 'java-runtime-delta']
}

/**
 * The Java a game version needs when its json does not say.
 *
 * Every version json from 1.14 onward names its Java, so this only answers for
 * older ones and for loader profiles written before that field existed. Getting
 * it wrong is not cosmetic: 1.12.2 on Java 25 fails at class loading, which is
 * why the launcher stops at the newest version that Java 8 was right for rather
 * than picking whatever the machine has.
 */
export function inferJavaMajor(versionId: string | null): number | null {
  if (!versionId) return null
  const match = /^(\d+)\.(\d+)(?:\.(\d+))?/.exec(versionId)
  if (!match) return null
  const major = Number(match[1])
  const minor = Number(match[2])
  const patch = match[3] ? Number(match[3]) : 0
  // 1.x is the numbering that predates the year-based ids, and none of those
  // declare a Java version; anything else is a modern json that names one.
  if (major !== 1) return null
  if (minor < 17) return 8
  if (minor === 17) return 16
  if (minor < 20) return 17
  if (minor === 20 && patch < 5) return 17
  return 21
}

/** `<instanceDir>/runtime/<component>/<platform>/<component>/bin/java`. */
export function runtimeCandidate(instanceDir: string, component: string, platform: NodeJS.Platform): JavaCandidate | null {
  if (!component) return null
  const platformDir = runtimePlatformDir(platform)
  return {
    path: join(instanceDir, 'runtime', component, platformDir, component, 'bin', javaExecutableName(platform)),
    source: `the ${component} runtime inside the game directory`,
    knownMajor: RUNTIME_COMPONENT_MAJORS[component] ?? null
  }
}

/** `java -version` for a path that answers, or null when nothing does. */
function majorFromPath(path: string): number | null {
  const match = /(?:jdk|jre|java)[-_]?(\d+)/i.exec(path)
  if (match) {
    const value = Number(match[1])
    return value === 1 ? null : value
  }
  const component = Object.keys(RUNTIME_COMPONENT_MAJORS).find((name) => path.includes(name))
  return component ? RUNTIME_COMPONENT_MAJORS[component] : null
}

/**
 * Every place worth checking, in the order a user would expect: the runtime
 * Mojang installed for this instance, then JAVA_HOME, then the system JDKs.
 *
 * Paths that do not exist are filtered by the caller, which is what keeps this
 * function testable.
 */
export function javaCandidates(input: {
  platform: NodeJS.Platform
  instanceDir: string
  /** `javaVersion.component` from the version json, when it names one. */
  component?: string | null
  env?: NodeJS.ProcessEnv
  homeDir?: string
}): JavaCandidate[] {
  const { platform, instanceDir, component } = input
  const env = input.env ?? {}
  const candidates: JavaCandidate[] = []

  const runtime = component ? runtimeCandidate(instanceDir, component, platform) : null
  if (runtime) candidates.push(runtime)

  const javaHome = env.JAVA_HOME?.trim()
  if (javaHome) {
    candidates.push({
      path: join(javaHome, 'bin', javaExecutableName(platform)),
      source: 'JAVA_HOME',
      knownMajor: majorFromPath(javaHome)
    })
  }

  const jvmDirs =
    platform === 'win32'
      ? [
          'C:\\Program Files\\Java',
          'C:\\Program Files\\Eclipse Adoptium',
          'C:\\Program Files\\Microsoft',
          'C:\\Program Files\\Amazon Corretto',
          'C:\\Program Files (x86)\\Java'
        ]
      : platform === 'darwin'
        ? ['/Library/Java/JavaVirtualMachines']
        : ['/usr/lib/jvm', '/usr/java', '/opt/java']

  for (const dir of jvmDirs) {
    candidates.push({ path: dir, source: `the JDKs in ${dir}`, knownMajor: null })
  }

  const home = input.homeDir
  if (home) {
    const sdkman = join(home, '.sdkman', 'candidates', 'java')
    candidates.push({ path: sdkman, source: 'SDKMAN', knownMajor: null })
  }

  return candidates
}

/**
 * Expands a directory candidate into the java executables inside it: either a
 * JDK home (with bin/java) or a folder of JDK homes, one level deep.
 */
export function expandJavaDirectory(candidate: JavaCandidate, platform: NodeJS.Platform): JavaCandidate[] {
  const executable = javaExecutableName(platform)
  const direct = join(candidate.path, 'bin', executable)
  if (existsSync(direct)) {
    return [{ ...candidate, path: direct, knownMajor: candidate.knownMajor ?? majorFromPath(candidate.path) }]
  }

  let entries: string[] = []
  try {
    entries = readdirSync(candidate.path, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
  } catch {
    return []
  }

  const found: JavaCandidate[] = []
  for (const name of entries) {
    const nested = join(candidate.path, name)
    if (platform === 'darwin') {
      const macHome = join(nested, 'Contents', 'Home')
      const macJava = join(macHome, 'bin', executable)
      if (existsSync(macJava)) {
        found.push({ path: macJava, source: candidate.source, knownMajor: candidate.knownMajor ?? majorFromPath(macHome) })
      }
      continue
    }
    const directJava = join(nested, 'bin', executable)
    if (existsSync(directJava)) {
      found.push({ path: directJava, source: candidate.source, knownMajor: candidate.knownMajor ?? majorFromPath(nested) })
      continue
    }
    // SDKMAN keeps the actual JDK one level further in.
    const deeper = join(nested, 'bin', executable)
    if (deeper !== directJava && existsSync(deeper)) {
      found.push({ path: deeper, source: candidate.source, knownMajor: majorFromPath(nested) })
    }
  }
  return found
}

/** Reads the major out of `java -version` output, which writes to stderr. */
export function parseJavaMajorVersion(output: string): number | null {
  const match = /version "?(\d+)(?:\.(\d+))?/.exec(output)
  if (!match) return null
  const first = Number(match[1])
  if (first === 1 && match[2]) return Number(match[2])
  return first
}

/**
 * Picks the runtime to run with: the smallest one that is new enough, because
 * a version json that wants 17 is happiest on 17 rather than on 21. When
 * nothing is new enough the newest available is returned anyway — the game
 * usually still starts, and refusing to launch is a worse answer than trying.
 */
export function selectJava(
  candidates: { path: string; major: number | null }[],
  requiredMajor: number | null
): { path: string; major: number | null } | null {
  if (candidates.length === 0) return null
  const known = candidates.filter((candidate) => candidate.major !== null) as {
    path: string
    major: number
  }[]

  if (requiredMajor !== null) {
    const goodEnough = known
      .filter((candidate) => candidate.major >= requiredMajor)
      .sort((a, b) => a.major - b.major)
    if (goodEnough.length > 0) return goodEnough[0]
  }

  const usable = [...known].sort((a, b) => b.major - a.major)
  if (usable.length > 0) return usable[0]
  return candidates[0]
}

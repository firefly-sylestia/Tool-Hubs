/**
 * Reading a crash instead of showing it.
 *
 * A crash report and `latest.log` are the same handful of shapes every time:
 * the description line the game writes, a stack trace with a `Caused by:` chain
 * that buries the real exception a dozen frames down, and — for a modded game —
 * a mod list and a missing-dependency block. What a player needs is not the
 * trace but the two lines in it that matter and what to do about them.
 *
 * Everything here is pattern matching over text, so every rule is checkable
 * against a real report and, more importantly, against a log that is not a crash
 * at all. A diagnosis is shown with the line it came from, so a wrong one can be
 * seen to be wrong instead of quietly sending someone to reinstall their game.
 */

import type { FindingSeverity, LogFinding } from '@shared/types'

export interface CrashSummary {
  /** The description the game wrote, when it wrote one. */
  description: string | null
  /** The exception the report was actually caused by, deepest first. */
  exception: string | null
  /** Where the exception was thrown, when the trace says. */
  at: string | null
  /** Minecraft version the report names, when it names one. */
  minecraftVersion: string | null
  javaVersion: string | null
  /** Mod ids/names the report lists, when it lists any. */
  mods: string[]
}

/** Leading whitespace is significant in a stack trace; trailing space is not. */
function lines(text: string): string[] {
  return text.split(/\r?\n/).map((line) => line.replace(/\s+$/, ''))
}

function firstMatch(text: string, pattern: RegExp): string | null {
  const match = pattern.exec(text)
  return match ? match[0].trim() : null
}

/** The line of `text` a needle was found on, whole, for use as evidence. */
function lineWith(text: string, needle: string): string {
  const line = lines(text).find((candidate) => candidate.includes(needle))
  return (line ?? needle).trim().slice(0, 240)
}

/**
 * What a crash report says about itself.
 *
 * The `Caused by:` chain is read as a chain: the last one is the root cause, and
 * that is the one worth showing — the top of the trace is nearly always the same
 * "something went wrong while ticking" wrapper.
 */
export function summarizeReport(text: string): CrashSummary {
  const caused = [...text.matchAll(/^Caused by:\s*(.+)$/gm)].map((match) => match[1].trim())
  const exception =
    caused.length > 0
      ? caused[caused.length - 1]
      : firstMatch(text, /^[\w.$]+(?:Exception|Error)(?::.*)?$/m)

  // The frame under the root cause's line, which names the class that threw.
  let at: string | null = null
  if (exception) {
    const trace = lines(text)
    const index = trace.findIndex(
      (line) => line.trim().startsWith('Caused by:') && line.includes(exception)
    )
    if (index !== -1) {
      const frame = trace.slice(index + 1).find((line) => line.trim().startsWith('at '))
      at = frame ? frame.trim() : null
    }
  }

  const mods: string[] = []
  // Forge, NeoForge and Fabric all write `<id>{<version>} [<name>] (<file>)`, one
  // per line under a heading that says so. The indent is a single tab as often
  // as it is spaces, so both are accepted.
  const modList = /^[ \t]+([A-Za-z0-9_.-]+)\{[^}]*\}/gm
  if (text.includes('-- Mod List --') || text.includes('Loaded Mods:')) {
    for (const match of text.matchAll(modList)) mods.push(match[1])
  }

  return {
    description:
      firstMatch(text, /^Description:\s*(.+)$/m)?.replace(/^Description:\s*/, '') ?? null,
    exception,
    at,
    minecraftVersion:
      firstMatch(text, /Minecraft Version:\s*(.+)/)?.replace(/Minecraft Version:\s*/, '') ?? null,
    javaVersion: firstMatch(text, /Java Version:\s*(.+)/)?.replace(/Java Version:\s*/, '') ?? null,
    mods: [...new Set(mods)].slice(0, 200)
  }
}

/** A mod id out of something like `net.example.mod.mixin.SomeMixin` or `examplemod`. */
function modHintFromPackage(evidence: string): string | null {
  const match = /\b(?:at|from)\s+([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+)/.exec(evidence)
  if (!match) return null
  const parts = match[1].split('.')
  // The package root is the closest thing to a mod id in a stack frame; the
  // full package names are noise to a player.
  return parts.slice(0, 2).join('.')
}

/** Mods named in a missing-dependency block, which Forge and NeoForge both write. */
export function missingDependencies(text: string): string[] {
  const block = /Missing or unsupported mandatory dependencies:\s*\n((?:\s+.+\n?)+)/.exec(text)
  if (!block) return []
  const found: string[] = []
  for (const line of block[1].split('\n')) {
    // The mod id arrives wrapped in ordinary or typographic quotes depending on
    // the loader, and both appeared in the wild the same week.
    const match = /Mod ID:\s*['\u2018"]?([^'\u2019\u201d",]+)['\u2019\u201d"]?/.exec(line)
    if (match) found.push(match[1].trim())
  }
  return [...new Set(found)]
}

/**
 * What is wrong, from a crash report or a log.
 *
 * Rules are ordered from the ones that are certainly fatal to the ones that are
 * only worth mentioning, and the first match of each kind is reported once: a
 * log that mentions the same missing class forty times is one problem.
 */
export function diagnose(text: string): LogFinding[] {
  const findings: LogFinding[] = []
  const add = (
    id: string,
    severity: FindingSeverity,
    title: string,
    needle: string,
    hints: string[]
  ): void => {
    if (findings.some((finding) => finding.id === id)) return
    findings.push({ id, severity, title, evidence: lineWith(text, needle), hints })
  }

  if (/java\.lang\.OutOfMemoryError|Java heap space|GC overhead limit exceeded/.test(text)) {
    add(
      'out-of-memory',
      'error',
      'The game ran out of memory',
      'OutOfMemoryError',
      [
        'Raise the memory limit in this instance\u2019s options, or in Settings.',
        'A large modpack usually needs 4-6 GB; the game cannot use more than about 8 GB well.'
      ]
    )
  }

  if (/UnsupportedClassVersionError|ClassFormatError|has been compiled by a more recent version/.test(text)) {
    add(
      'wrong-java',
      'error',
      'The Java runtime is the wrong one for this version',
      'UnsupportedClassVersionError',
      [
        'This version needs a newer Java than the one it was started with.',
        'Launching again downloads Mojang\u2019s runtime for the version; a Java path set in Settings overrides that.'
      ]
    )
  }

  if (/Failed to initialize GLFW|GLFW error|Pixel format not accelerated|WGL: Failed|OpenGL is not supported/i.test(text)) {
    add(
      'graphics',
      'error',
      'The graphics driver could not open a window',
      'GLFW',
      [
        'Update the graphics driver; the game needs OpenGL 3.2 or newer.',
        'On a laptop with two GPUs, force the game onto the dedicated one.',
        'Over a remote session or a virtual machine there is often no usable acceleration.'
      ]
    )
  }

  const missing = missingDependencies(text)
  if (missing.length > 0 || /ModResolutionException|Missing or unsupported mandatory dependencies/.test(text)) {
    add(
      'missing-dependency',
      'error',
      'A mod needs another mod that is not installed',
      'Missing or unsupported mandatory dependencies',
      [
        missing.length > 0
          ? `Install: ${missing.join(', ')}.`
          : 'The loader names the missing mods in the block above.',
        'Install them from the Store for this exact game version and loader.'
      ]
    )
  }

  if (/Duplicate mods|DuplicateModsFoundException|Found duplicate mod/i.test(text)) {
    add(
      'duplicate-mods',
      'error',
      'Two copies of the same mod are installed',
      'Duplicate',
      [
        'Remove one of the copies from this instance\u2019s mods folder.',
        'A mod that ships bundled inside another one has to be removed, not renamed.'
      ]
    )
  }

  if (/Mixin apply failed|MixinTransformerError|MixinApplyError|InvalidMixinException/.test(text)) {
    const mod = modHintFromPackage(text)
    add(
      'mixin',
      'error',
      mod ? `A mod failed to patch the game (${mod})` : 'A mod failed to patch the game',
      'Mixin',
      [
        'The mod listed just above is built for a different game version or loader than this instance.',
        'Check that every mod in the instance fits its version and loader \u2014 the mods list warns about the ones that do not.'
      ]
    )
  }

  if (/NoSuchMethodError|NoSuchFieldError|AbstractMethodError/.test(text)) {
    const mod = modHintFromPackage(text)
    add(
      'incompatible-mod',
      'error',
      mod ? `A mod was built against a different version (${mod})` : 'A mod was built against a different version',
      'NoSuchMethodError',
      [
        'This is what a mod for another game version looks like when it loads.',
        'The mod file and the loader version both have to match the game version.'
      ]
    )
  }

  if (/ClassNotFoundException|NoClassDefFoundError/.test(text)) {
    add(
      'missing-class',
      'error',
      'Something the game tried to load is not there',
      'NoClassDefFoundError',
      [
        'If the class belongs to a mod, that mod is missing or is for another loader.',
        'If it is a `net.minecraft` class, the installation is incomplete \u2014 launching rebuilds it.'
      ]
    )
  }

  if (/Could not find or load main class|Failed to find Main Class|mainClass/i.test(text)) {
    add(
      'main-class',
      'error',
      'The version\u2019s main class was not found',
      'main class',
      [
        'The installation on disk does not match the version json.',
        'Launching the instance again completes it; if it keeps happening, install the version again.'
      ]
    )
  }

  if (/The game crashed whilst|Failed to start|Failed to launch/i.test(text)) {
    add(
      'game-crashed',
      'info',
      'The game reported a crash',
      'The game crashed whilst',
      ['The lines above are the report\u2019s own description of what it was doing.']
    )
  }

  if (/lost connection|Disconnected|Internal Exception|Connection reset|timed out/i.test(text) && findings.length === 0) {
    add(
      'disconnected',
      'warning',
      'The game lost its connection',
      'Disconnected',
      [
        'An offline-mode server rejects a client it cannot authenticate, which is expected with this launcher.',
        'Otherwise the server or the network dropped it; nothing is wrong with the installation.'
      ]
    )
  }

  return findings
}

/** The newest crash reports first, from the file names the game writes. */
export function sortReports<T extends { name: string }>(reports: T[]): T[] {
  return [...reports].sort((left, right) => right.name.localeCompare(left.name))
}

/** True when a file name is one the game would write a crash report under. */
export function isCrashReport(name: string): boolean {
  return /^crash-.*\.txt$/i.test(name)
}

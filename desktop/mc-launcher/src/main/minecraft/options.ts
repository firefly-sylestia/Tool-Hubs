import type { GameOptionEntry, GameOptionField } from '@shared/types'

/**
 * `options.txt`, the game's own settings file.
 *
 * It is a plain `key:value` list, one per line, and the game rewrites it the
 * moment it exits. That is exactly why it is edited as text and patched rather
 * than regenerated: the launcher does not know what a future version writes
 * there, and a settings file rewritten from a template of today's keys would
 * quietly throw away everything else. Anything this does not understand is kept,
 * in place.
 *
 * The keys people change are the ones worth showing; the rest are readable and
 * patchable without a list of them living here.
 */

export const OPTIONS_FILE = 'options.txt'

/** A few options the launcher offers directly, because they are the ones asked for. */
export const QUICK_OPTIONS: GameOptionField[] = [
  { key: 'fullscreen', label: 'Fullscreen', choices: ['true', 'false'] },
  { key: 'graphics', label: 'Graphics', choices: ['fancy', 'fast', 'fabulous'] },
  { key: 'fov', label: 'FOV', numeric: true },
  { key: 'renderDistance', label: 'Render distance (chunks)', numeric: true },
  { key: 'simulationDistance', label: 'Simulation distance (chunks)', numeric: true },
  { key: 'maxFps', label: 'Maximum FPS', numeric: true },
  { key: 'guiScale', label: 'GUI scale', numeric: true },
  { key: 'pauseOnLostFocus', label: 'Pause when unfocused', choices: ['true', 'false'] },
  { key: 'enableVsync', label: 'VSync', choices: ['true', 'false'] }
]

/** Every line, with the keys found in it. Comments and blanks are preserved. */
export function parseOptions(contents: string): { entries: GameOptionEntry[]; text: string } {
  const entries: GameOptionEntry[] = []
  for (const line of contents.split(/\r?\n/)) {
    const separator = line.indexOf(':')
    if (separator <= 0) continue
    const key = line.slice(0, separator).trim()
    if (key === '' || key.startsWith('#')) continue
    entries.push({ key, value: line.slice(separator + 1).trim() })
  }
  return { entries, text: contents }
}

export function optionValue(contents: string, key: string): string | null {
  return parseOptions(contents).entries.find((entry) => entry.key === key)?.value ?? null
}

/**
 * Sets, changes or removes keys, keeping every other line exactly where it was.
 *
 * A `null` value removes the key — that is how a setting is handed back to the
 * game's own default, which is not the same as writing an empty value.
 */
export function patchOptions(contents: string, changes: Record<string, string | null>): string {
  const remaining = new Map(Object.entries(changes))
  const hadTrailingNewline = contents.endsWith('\n') || contents === ''
  const lines = contents === '' ? [] : contents.split(/\r?\n/)
  if (hadTrailingNewline && lines.length > 0 && lines[lines.length - 1] === '') lines.pop()

  const patched: string[] = []
  for (const line of lines) {
    const separator = line.indexOf(':')
    const key = separator > 0 ? line.slice(0, separator).trim() : null
    if (key === null || !remaining.has(key)) {
      patched.push(line)
      continue
    }
    const value = remaining.get(key)
    remaining.delete(key)
    // Removed keys are dropped in place; changed ones are rewritten where they
    // were, so the file keeps the game's own order.
    if (value === null) continue
    patched.push(`${key}:${value}`)
  }

  const additions = [...remaining.entries()].filter(([, value]) => value !== null)
  for (const [key, value] of additions) patched.push(`${key}:${value}`)

  return `${patched.join('\n')}\n`
}

/** The keys the UI offers directly, read out of a file's contents. */
export function quickOptionValues(contents: string): Record<string, string> {
  const entries = parseOptions(contents).entries
  const values: Record<string, string> = {}
  for (const option of QUICK_OPTIONS) {
    const found = entries.find((entry) => entry.key === option.key)
    if (found) values[option.key] = found.value
  }
  return values
}

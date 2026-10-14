/**
 * Reading extra JVM arguments out of a text field.
 *
 * Kept in `shared` because the renderer types them and the checks run against
 * the same function: an argument list is a place where the launcher must not
 * guess, and `-Dsome.option=hello world` has to stay one argument rather than
 * becoming two.
 */

/**
 * Splits on whitespace, keeping quoted runs together. Both quote styles are
 * accepted and the quotes are dropped, which is what a shell would do.
 */
export function parseJvmArgs(text: string): string[] {
  const args: string[] = []
  let current = ''
  let quote: string | null = null
  let started = false

  for (const character of text) {
    if (quote !== null) {
      if (character === quote) quote = null
      else current += character
      continue
    }
    if (character === '"' || character === "'") {
      quote = character
      started = true
      continue
    }
    if (/\s/.test(character)) {
      if (started || current !== '') args.push(current)
      current = ''
      started = false
      continue
    }
    current += character
  }
  if (started || current !== '') args.push(current)
  return args.filter((arg) => arg.trim() !== '')
}

/** One argument per line, quoting any that contain whitespace. */
export function formatJvmArgs(args: string[]): string {
  return args
    .map((arg) => (/\s/.test(arg) ? `"${arg.replace(/"/g, '')}"` : arg))
    .join('\n')
}

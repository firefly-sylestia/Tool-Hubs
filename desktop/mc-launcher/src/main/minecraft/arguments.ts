import type { RuleContext } from './rules'
import { rulesAllow } from './rules'
import type { VersionArgument } from './version-json'

export type ArgumentValues = Record<string, string>

/** Expands conditional arguments, dropping entries whose rules do not match. */
export function flattenArguments(args: VersionArgument[] | undefined, ctx: RuleContext): string[] {
  const out: string[] = []
  for (const argument of args ?? []) {
    if (typeof argument === 'string') {
      out.push(argument)
      continue
    }
    if (!rulesAllow(argument.rules, ctx)) continue
    if (Array.isArray(argument.value)) out.push(...argument.value)
    else out.push(argument.value)
  }
  return out
}

/**
 * Pre-1.13 versions describe their arguments as a single whitespace separated
 * string. Values we substitute into it (paths, names) are not quoted by the
 * official launcher, so splitting on whitespace matches its behaviour.
 */
export function splitLegacyArguments(legacy: string): string[] {
  return legacy.split(/\s+/).filter((part) => part.length > 0)
}

export function substitute(value: string, values: ArgumentValues): string {
  return value.replace(/\$\{([^}]+)\}/g, (match, key: string) => values[key] ?? match)
}

export function substituteAll(args: string[], values: ArgumentValues): string[] {
  return args.map((arg) => substitute(arg, values))
}

/**
 * Every placeholder must be resolved before launch; a leftover `${...}` reaches
 * the game as a literal and produces a confusing failure there.
 */
export function unresolvedPlaceholders(args: string[]): string[] {
  const found = new Set<string>()
  for (const arg of args) {
    for (const match of arg.matchAll(/\$\{([^}]+)\}/g)) found.add(match[1])
  }
  return [...found]
}

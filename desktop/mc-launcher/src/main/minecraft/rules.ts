export type OsName = 'windows' | 'linux' | 'osx'

export interface RuleOs {
  name?: OsName
  arch?: string
  version?: string
}

export interface Rule {
  action: 'allow' | 'disallow'
  os?: RuleOs
  features?: Record<string, boolean>
}

export interface RuleContext {
  os: OsName
  /** Minecraft's arch token: 'x86', 'x86_64' or 'arm64'. */
  arch: string
  features: Record<string, boolean>
}

export function ruleMatches(rule: Rule, ctx: RuleContext): boolean {
  if (rule.os?.name && rule.os.name !== ctx.os) return false
  if (rule.os?.arch && rule.os.arch !== ctx.arch) return false
  if (rule.features) {
    for (const [key, expected] of Object.entries(rule.features)) {
      if ((ctx.features[key] ?? false) !== expected) return false
    }
  }
  return true
}

/**
 * Rules are evaluated in order and the last matching rule wins. An entry with
 * no rules is included; an entry whose rules exist but none match is excluded,
 * which is what makes a Windows-only library disappear on Linux.
 */
export function rulesAllow(rules: Rule[] | undefined, ctx: RuleContext): boolean {
  if (!rules || rules.length === 0) return true
  let allowed = false
  for (const rule of rules) {
    if (!ruleMatches(rule, ctx)) continue
    allowed = rule.action === 'allow'
  }
  return allowed
}

/** Maps a Node platform/arch pair onto the launcher's rule context tokens. */
export function ruleContextFor(
  platform: NodeJS.Platform,
  arch: string,
  features: Record<string, boolean> = {}
): RuleContext {
  const os: OsName = platform === 'win32' ? 'windows' : platform === 'darwin' ? 'osx' : 'linux'
  const archToken = arch === 'x64' ? 'x86_64' : arch === 'ia32' ? 'x86' : arch
  return { os, arch: archToken, features }
}

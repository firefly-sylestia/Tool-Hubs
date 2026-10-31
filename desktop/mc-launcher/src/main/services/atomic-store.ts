import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

/**
 * Small JSON store that writes through a temporary file and renames it into
 * place, so a crash mid-write cannot leave a truncated state file. A corrupt
 * file is reported and falls back to the defaults rather than crashing the app.
 */
export class AtomicJsonStore<T> {
  constructor(
    private readonly file: string,
    private readonly defaults: T,
    private readonly onWarning?: (message: string) => void
  ) {}

  read(): T {
    if (!existsSync(this.file)) return structuredClone(this.defaults)
    try {
      const parsed = JSON.parse(readFileSync(this.file, 'utf8')) as Partial<T>
      return { ...structuredClone(this.defaults), ...parsed }
    } catch (error) {
      this.onWarning?.(`State file ${this.file} is unreadable, using defaults: ${(error as Error).message}`)
      this.quarantine()
      return structuredClone(this.defaults)
    }
  }

  write(value: T): void {
    mkdirSync(dirname(this.file), { recursive: true })
    const temporary = join(dirname(this.file), `.${Date.now()}.state.tmp`)
    writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, 'utf8')
    renameSync(temporary, this.file)
  }

  /** Moves an unreadable file aside so the next write starts clean. */
  private quarantine(): void {
    try {
      renameSync(this.file, `${this.file}.corrupt`)
    } catch {
      // Nothing to preserve; the next write recreates the file.
    }
  }
}

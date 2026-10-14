import { createHash } from 'node:crypto'
import {
  createReadStream,
  createWriteStream,
  existsSync,
  mkdirSync,
  renameSync,
  rmSync,
  statSync
} from 'node:fs'
import { writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'

/**
 * Downloads the files an installation needs.
 *
 * Two rules shape this module. A file is only renamed into place once its sha1
 * matches what the version json declared, so a half-downloaded jar can never be
 * mistaken for a finished one — the installer resumes from `<name>.part` and
 * the launcher only ever sees whole, verified files. And a download that fails
 * leaves its `.part` behind on purpose, because resuming is a range request
 * away while starting over is the whole file again.
 */

export interface DownloadRequest {
  url: string
  dest: string
  sha1?: string
  size?: number
  /** Shown in progress messages; defaults to the file name. */
  label?: string
}

export type FetchLike = (url: string, init?: { headers?: Record<string, string> }) => Promise<Response>

export interface DownloadProgress {
  /** Files finished, including ones that were already present and verified. */
  completed: number
  total: number
  bytesDone: number
  /** Sum of the known file sizes; 0 when the server did not declare them. */
  bytesTotal: number
  current: string
}

export interface DownloadReport {
  downloaded: number
  reused: number
  bytes: number
}

const DEFAULT_RETRIES = 3
const DEFAULT_CONCURRENCY = 6

export function sha1OfFile(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha1')
    const stream = createReadStream(path)
    stream.on('error', reject)
    stream.on('data', (chunk) => hash.update(chunk))
    stream.on('end', () => resolve(hash.digest('hex')))
  })
}

/** A file counts as present only when its hash matches; a size check alone is not enough. */
export async function isFileValid(path: string, sha1?: string): Promise<boolean> {
  if (!existsSync(path)) return false
  if (!sha1) return true
  try {
    return (await sha1OfFile(path)) === sha1.toLowerCase()
  } catch {
    return false
  }
}

async function writeChunk(stream: import('node:fs').WriteStream, chunk: Uint8Array): Promise<void> {
  if (!stream.write(chunk)) {
    await new Promise<void>((resolve) => stream.once('drain', resolve))
  }
}

interface SingleResult {
  bytes: number
  reused: boolean
}

async function downloadOnce(
  request: DownloadRequest,
  fetchImpl: FetchLike,
  onBytes: (delta: number) => void
): Promise<SingleResult> {
  const part = `${request.dest}.part`
  let offset = existsSync(part) ? statSync(part).size : 0
  if (existsSync(request.dest)) rmSync(request.dest, { force: true })

  const headers: Record<string, string> = {}
  // Ask for the rest of the file only when there is something to resume.
  if (offset > 0) headers.Range = `bytes=${offset}-`

  const response = await fetchImpl(request.url, { headers })
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} for ${request.url}`)
  }

  // A server that ignores Range answers 200, and the body is the whole file.
  if (offset > 0 && response.status !== 206) {
    offset = 0
    rmSync(part, { force: true })
  }
  if (offset > 0) onBytes(offset)

  mkdirSync(dirname(request.dest), { recursive: true })
  const stream = createWriteStream(part, { flags: offset > 0 ? 'a' : 'w' })
  let written = 0
  try {
    if (response.body) {
      const reader = response.body.getReader()
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        if (!value) continue
        await writeChunk(stream, value)
        written += value.length
        onBytes(value.length)
      }
    } else {
      const buffer = Buffer.from(await response.arrayBuffer())
      await writeChunk(stream, buffer)
      written += buffer.length
      onBytes(buffer.length)
    }
  } finally {
    await new Promise<void>((resolve, reject) => {
      stream.end((error?: Error | null) => (error ? reject(error) : resolve()))
    })
  }

  if (request.sha1) {
    const actual = await sha1OfFile(part)
    if (actual !== request.sha1.toLowerCase()) {
      // Keep the bytes: a retry resumes rather than re-fetching, and the hash is
      // re-checked before anything is renamed into place.
      throw new Error(
        `Checksum mismatch for ${request.label ?? request.dest}: expected ${request.sha1}, got ${actual}`
      )
    }
  }

  renameSync(part, request.dest)
  return { bytes: written + offset, reused: false }
}

/**
 * Fetches one file, resuming and retrying as needed. Throws with the server's
 * reason after the last attempt; nothing is renamed into place unless the hash
 * checked out.
 */
export async function downloadFile(
  request: DownloadRequest,
  options: { fetchImpl?: FetchLike; onBytes?: (delta: number) => void; retries?: number } = {}
): Promise<SingleResult> {
  const fetchImpl = options.fetchImpl ?? ((url, init) => fetch(url, init))
  const retries = options.retries ?? DEFAULT_RETRIES

  if (await isFileValid(request.dest, request.sha1)) return { bytes: 0, reused: true }

  let lastError: Error | null = null
  for (let attempt = 1; attempt <= retries; attempt += 1) {
    try {
      return await downloadOnce(request, fetchImpl, options.onBytes ?? (() => {}))
    } catch (error) {
      lastError = error as Error
      if (attempt < retries) await new Promise((resolve) => setTimeout(resolve, attempt * 250))
    }
  }
  throw lastError ?? new Error(`Could not download ${request.url}`)
}

/**
 * Downloads a batch with a fixed number of files in flight.
 *
 * Progress is reported as bytes rather than files, because "41 of 3000" tells
 * nobody how long is left, and a single 100 MB asset would look identical to a
 * 2 KB pom.
 */
export async function downloadAll(
  requests: DownloadRequest[],
  options: {
    fetchImpl?: FetchLike
    concurrency?: number
    onProgress?: (progress: DownloadProgress) => void
  } = {}
): Promise<DownloadReport> {
  const onProgress = options.onProgress ?? (() => {})
  const total = requests.length
  const bytesTotal = requests.reduce((sum, request) => sum + (request.size ?? 0), 0)

  let completed = 0
  let bytesDone = 0
  let reused = 0
  let downloaded = 0
  let index = 0

  const report = (current: string): void => {
    onProgress({ completed, total, bytesDone, bytesTotal, current })
  }

  const worker = async (): Promise<void> => {
    for (;;) {
      const next = index
      index += 1
      if (next >= total) return
      const request = requests[next]
      const result = await downloadFile(request, {
        fetchImpl: options.fetchImpl,
        onBytes: (delta) => {
          bytesDone += delta
          report(request.label ?? request.dest)
        }
      })
      if (result.reused) {
        reused += 1
        bytesDone += request.size ?? 0
      } else {
        downloaded += 1
      }
      completed += 1
      report(request.label ?? request.dest)
    }
  }

  const workers = Math.max(1, Math.min(options.concurrency ?? DEFAULT_CONCURRENCY, total || 1))
  await Promise.all(Array.from({ length: workers }, () => worker()))

  return { downloaded, reused, bytes: bytesDone }
}

export async function fetchJson<T>(
  url: string,
  options: { fetchImpl?: FetchLike; retries?: number } = {}
): Promise<T> {
  const fetchImpl = options.fetchImpl ?? ((target, init) => fetch(target, init))
  const retries = options.retries ?? DEFAULT_RETRIES
  let lastError: Error | null = null

  for (let attempt = 1; attempt <= retries; attempt += 1) {
    try {
      const response = await fetchImpl(url)
      if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`)
      return (await response.json()) as T
    } catch (error) {
      lastError = error as Error
      if (attempt < retries) await new Promise((resolve) => setTimeout(resolve, attempt * 250))
    }
  }
  throw lastError ?? new Error(`Could not fetch ${url}`)
}

/** Writes a file atomically enough for the installer's purposes: temp, then rename. */
export async function writeTextFile(dest: string, contents: string): Promise<void> {
  mkdirSync(dirname(dest), { recursive: true })
  const temporary = `${dest}.tmp`
  await writeFile(temporary, contents, 'utf8')
  renameSync(temporary, dest)
}

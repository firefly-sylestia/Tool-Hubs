import { randomUUID } from 'node:crypto'
import type { TaskRecord } from '@shared/types'
import type { ServiceRegistry } from './registry'
import type { StateStore } from './state-store'

const MAX_TASKS = 50

/**
 * Tracks the launcher's own work so the UI can show it. Every long operation
 * runs through `run`, which records the task, streams progress events and
 * always finishes the record, including on failure.
 */
export class TaskService {
  constructor(
    private readonly state: StateStore,
    private readonly registry: ServiceRegistry
  ) {}

  list(): TaskRecord[] {
    return this.state.snapshot().tasks
  }

  async run<T>(
    name: string,
    work: (report: (progress: number, message: string) => void) => Promise<T> | T
  ): Promise<T> {
    const record: TaskRecord = {
      id: randomUUID(),
      name,
      state: 'running',
      progress: 0,
      message: 'Starting',
      startedAt: new Date().toISOString(),
      finishedAt: null
    }
    this.push(record)
    this.state.setValidating(true)

    const report = (progress: number, message: string): void => {
      const clamped = Math.max(0, Math.min(1, progress))
      this.state.update((state) => {
        const task = state.tasks.find((candidate) => candidate.id === record.id)
        if (!task) return
        task.progress = clamped
        task.message = message
      })
      this.registry.emit({ service: 'task', event: 'progress', payload: { id: record.id, progress: clamped, message } })
    }

    try {
      const result = await work(report)
      this.finish(record.id, 'succeeded', 'Done')
      return result
    } catch (error) {
      this.finish(record.id, 'failed', (error as Error).message)
      throw error
    } finally {
      this.state.setValidating(false)
    }
  }

  private push(record: TaskRecord): void {
    this.state.update((state) => {
      state.tasks.unshift(record)
      if (state.tasks.length > MAX_TASKS) state.tasks.length = MAX_TASKS
    })
    this.registry.emit({ service: 'task', event: 'activated', payload: record })
  }

  private finish(id: string, state: TaskRecord['state'], message: string): void {
    this.state.update((snapshot) => {
      const task = snapshot.tasks.find((candidate) => candidate.id === id)
      if (!task) return
      task.state = state
      task.message = message
      task.progress = state === 'succeeded' ? 1 : task.progress
      task.finishedAt = new Date().toISOString()
    })
    this.registry.emit({ service: 'task', event: state, payload: { id, message } })
  }
}

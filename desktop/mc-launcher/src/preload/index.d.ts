import type { ServiceEventMessage } from '@shared/ipc'
import type { LauncherState, TaskRecord } from '@shared/types'

type Unsubscribe = () => void

export interface ServiceChannelsApi {
  call: (service: string, method: string, params?: unknown) => Promise<unknown>
  callTraced: (
    service: string,
    method: string,
    params?: unknown
  ) => Promise<{ result: unknown; elapsedMs: number }>
  onEvent: (listener: (event: ServiceEventMessage) => void) => Unsubscribe
}

export interface WindowControllerApi {
  minimize: () => Promise<unknown>
  maximize: () => Promise<unknown>
  close: () => Promise<unknown>
  focus: () => Promise<unknown>
  isMaximized: () => Promise<unknown>
  toggleDevTools: () => Promise<unknown>
}

export interface BootstrapApi {
  load: () => Promise<{ state: LauncherState; services: Record<string, string[]> }>
  revalidate: () => Promise<LauncherState>
  commit: (next: LauncherState) => Promise<LauncherState>
  onSnapshot: (listener: (state: LauncherState) => void) => Unsubscribe
  onValidating: (listener: (validating: boolean) => void) => Unsubscribe
}

export interface TaskMonitorApi {
  list: () => Promise<TaskRecord[]>
  on: (listener: (event: ServiceEventMessage) => void) => Unsubscribe
}

declare global {
  interface Window {
    serviceChannels: ServiceChannelsApi
    windowController: WindowControllerApi
    bootstrap: BootstrapApi
    taskMonitor: TaskMonitorApi
  }
}

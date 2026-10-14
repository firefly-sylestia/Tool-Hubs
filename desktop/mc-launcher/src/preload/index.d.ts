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
  minimize: () => Promise<{ ok: boolean }>
  /** Toggles, so one control both maximizes and restores the window. */
  maximize: () => Promise<{ ok: boolean; maximized: boolean }>
  close: () => Promise<{ ok: boolean }>
  focus: () => Promise<{ ok: boolean }>
  isMaximized: () => Promise<{ maximized: boolean }>
  toggleDevTools: () => Promise<{ ok: boolean }>
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

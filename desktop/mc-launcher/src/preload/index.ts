import { contextBridge, ipcRenderer } from 'electron'
import { IPC, type ServiceCallMessage, type ServiceEventMessage } from '@shared/ipc'
import type { LauncherState, TaskRecord } from '@shared/types'

type Unsubscribe = () => void

const serviceChannels = {
  call: (service: string, method: string, params?: unknown): Promise<unknown> =>
    ipcRenderer.invoke(IPC.serviceCall, { service, method, params } satisfies ServiceCallMessage),
  callTraced: (
    service: string,
    method: string,
    params?: unknown
  ): Promise<{ result: unknown; elapsedMs: number }> =>
    ipcRenderer.invoke(IPC.serviceCallTraced, { service, method, params } satisfies ServiceCallMessage),
  onEvent: (listener: (event: ServiceEventMessage) => void): Unsubscribe => {
    const handler = (_event: unknown, payload: ServiceEventMessage): void => listener(payload)
    ipcRenderer.on(IPC.serviceEvent, handler)
    return () => ipcRenderer.off(IPC.serviceEvent, handler)
  }
}

const windowController = {
  minimize: () => serviceChannels.call('window', 'minimize'),
  maximize: () => serviceChannels.call('window', 'maximize'),
  close: () => serviceChannels.call('window', 'close'),
  focus: () => serviceChannels.call('window', 'focus'),
  isMaximized: () => serviceChannels.call('window', 'isMaximized'),
  toggleDevTools: () => serviceChannels.call('window', 'toggleDevTools')
}

const bootstrap = {
  load: (): Promise<{ state: LauncherState; services: Record<string, string[]> }> =>
    ipcRenderer.invoke(IPC.bootstrap),
  revalidate: (): Promise<LauncherState> => ipcRenderer.invoke(IPC.revalidate),
  commit: (next: LauncherState): Promise<LauncherState> => ipcRenderer.invoke(IPC.commit, next),
  onSnapshot: (listener: (state: LauncherState) => void): Unsubscribe => {
    const handler = (_event: unknown, message: ServiceEventMessage): void => {
      if (message.service === 'state' && message.event === 'snapshot') {
        listener(message.payload as LauncherState)
      }
    }
    ipcRenderer.on(IPC.serviceEvent, handler)
    return () => ipcRenderer.off(IPC.serviceEvent, handler)
  },
  onValidating: (listener: (validating: boolean) => void): Unsubscribe => {
    const handler = (_event: unknown, validating: boolean): void => listener(validating)
    ipcRenderer.on(IPC.stateValidating, handler)
    return () => ipcRenderer.off(IPC.stateValidating, handler)
  }
}

const taskMonitor = {
  list: (): Promise<TaskRecord[]> => serviceChannels.call('task', 'list') as Promise<TaskRecord[]>,
  on: (listener: (event: ServiceEventMessage) => void): Unsubscribe => serviceChannels.onEvent(listener)
}

contextBridge.exposeInMainWorld('serviceChannels', serviceChannels)
contextBridge.exposeInMainWorld('windowController', windowController)
contextBridge.exposeInMainWorld('bootstrap', bootstrap)
contextBridge.exposeInMainWorld('taskMonitor', taskMonitor)

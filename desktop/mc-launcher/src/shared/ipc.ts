/**
 * Renderer <-> main channels. These mirror the surface of the application we
 * studied: a generic service RPC channel, a service event channel, a state
 * snapshot protocol, and window controls.
 */

export const IPC = {
  serviceCall: 'service-call',
  serviceCallTraced: 'service-call-traced',
  serviceEvent: 'service-event',
  stateValidating: 'state-validating',
  commit: 'commit',
  revalidate: 'revalidate',
  bootstrap: 'bootstrap'
} as const

export type ServiceName =
  | 'profile'
  | 'instance'
  | 'modpack'
  | 'modrinth'
  | 'java'
  | 'installation'
  | 'install'
  | 'server'
  | 'save'
  | 'launch'
  | 'task'
  | 'settings'
  | 'log'
  | 'diagnostics'
  | 'window'

export interface ServiceCallMessage {
  service: string
  method: string
  params?: unknown
}

export interface ServiceEventMessage {
  service: string
  event: string
  payload?: unknown
}

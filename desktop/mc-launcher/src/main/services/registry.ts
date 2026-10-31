import type { ServiceEventMessage } from '@shared/ipc'

export type ServiceHandler = (params: unknown) => Promise<unknown> | unknown
export type ServiceMethodMap = Record<string, ServiceHandler>
export type ServiceEventEmitter = (event: ServiceEventMessage) => void

/**
 * The renderer talks to the main process through one generic channel
 * (`service-call`) that resolves a service name plus a method. Unknown services
 * and unknown methods fail loudly instead of being silently ignored.
 */
export class ServiceRegistry {
  private readonly services = new Map<string, ServiceMethodMap>()
  private emitter: ServiceEventEmitter = () => {}

  setEmitter(emitter: ServiceEventEmitter): void {
    this.emitter = emitter
  }

  register(service: string, methods: ServiceMethodMap): void {
    if (this.services.has(service)) {
      throw new Error(`Service ${service} is already registered`)
    }
    this.services.set(service, methods)
  }

  has(service: string, method: string): boolean {
    return this.services.get(service)?.[method] !== undefined
  }

  list(): Record<string, string[]> {
    const out: Record<string, string[]> = {}
    for (const [name, methods] of this.services) out[name] = Object.keys(methods)
    return out
  }

  emit(event: ServiceEventMessage): void {
    this.emitter(event)
  }

  async call(service: string, method: string, params?: unknown): Promise<unknown> {
    const methods = this.services.get(service)
    if (!methods) {
      throw new Error(`Unknown service "${service}". Registered: ${[...this.services.keys()].join(', ')}`)
    }
    const handler = methods[method]
    if (!handler) {
      throw new Error(`Unknown method "${service}.${method}". Available: ${Object.keys(methods).join(', ')}`)
    }
    return await handler(params)
  }
}

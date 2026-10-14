import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRouter, createWebHashHistory, type Router } from 'vue-router'
import type { LauncherState } from '@shared/types'
import App from '@renderer/App.vue'
import { initLauncher } from '@renderer/composables/useLauncher'
import { vuetify } from '@renderer/plugins/vuetify'
import { routes } from '@renderer/router'

const baseState: LauncherState = {
  profiles: [],
  selectedProfileId: null,
  servers: [],
  pendingServerId: null,
  settings: {
    gameDir: '/games/.minecraft',
    javaPath: null,
    maxMemoryMb: 4096,
    width: 1280,
    height: 720
  },
  tasks: [],
  installations: [],
  runningProfileId: null
}

const steveState: LauncherState = {
  ...baseState,
  profiles: [
    {
      id: 'p1',
      name: 'Steve',
      uuid: 'b50ad385-829d-3141-a216-7e7d7539ba7f',
      gameDir: null,
      versionId: '1.21.4',
      createdAt: '2026-10-13T00:00:00.000Z',
      lastPlayedAt: null
    }
  ],
  selectedProfileId: 'p1',
  installations: [
    {
      id: '1.21.4',
      jsonPath: '/games/.minecraft/versions/1.21.4/1.21.4.json',
      type: 'release',
      mainClass: 'net.minecraft.client.main.Main',
      assetsIndex: '19',
      javaMajorVersion: 21,
      inheritsFrom: null
    }
  ]
}

type RecordedCall = { service: string; method: string; params?: unknown }

let calls: RecordedCall[] = []
/** Calls made while the composable booted, before the per-test reset. */
let bootCalls: RecordedCall[] = []
let pushSnapshot: (state: LauncherState) => void = () => {}
let failNextCall: string | null = null

function stubBridge(): void {
  const call = vi.fn(async (service: string, method: string, params?: unknown) => {
    calls.push({ service, method, params })
    if (failNextCall) {
      const message = failNextCall
      failNextCall = null
      throw new Error(`Error invoking remote method 'service-call': Error: ${message}`)
    }
    if (service === 'log' && method === 'recent') return []
    if (service === 'install' && method === 'versions') {
      return [
        { id: '1.21.5-pre1', type: 'snapshot', releaseTime: '2026-10-01T00:00:00.000Z' },
        { id: '1.21.4', type: 'release', releaseTime: '2025-12-03T00:00:00.000Z' },
        { id: '1.21.3', type: 'release', releaseTime: '2025-10-23T00:00:00.000Z' }
      ]
    }
    if (service === 'install' && method === 'loaders') {
      return [
        { version: '0.17.0-beta.1', stable: false },
        { version: '0.16.9', stable: true }
      ]
    }
    if (service === 'install' && method === 'start') {
      return {
        versionId: '1.21.4',
        targetId: 'fabric-loader-0.16.9-1.21.4',
        files: 184,
        reused: 2,
        bytes: 4096,
        nativesExtracted: 7
      }
    }
    return null
  })

  ;(window as unknown as { serviceChannels: unknown }).serviceChannels = {
    call,
    callTraced: call,
    onEvent: () => () => {}
  }
  ;(window as unknown as { bootstrap: unknown }).bootstrap = {
    load: async () => ({ state: baseState, services: { profile: ['list'], launch: ['start'] } }),
    revalidate: async () => baseState,
    commit: async () => baseState,
    onSnapshot: (listener: (state: LauncherState) => void) => {
      pushSnapshot = listener
      return () => {}
    },
    onValidating: () => () => {}
  }
  ;(window as unknown as { windowController: unknown }).windowController = {
    minimize: vi.fn(),
    maximize: vi.fn(),
    close: vi.fn(),
    focus: vi.fn(),
    isMaximized: vi.fn(),
    toggleDevTools: vi.fn()
  }
  ;(window as unknown as { taskMonitor: unknown }).taskMonitor = {
    list: async () => [],
    on: () => () => {}
  }
}

async function mountApp(): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({ history: createWebHashHistory(), routes })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [vuetify, router] } })
  await flushPromises()
  return { wrapper, router }
}

/**
 * Vuetify renders two labels per field: a decorative one and the one bound to
 * the input. Only the bound label carries `for`.
 */
function inputByLabel(wrapper: ReturnType<typeof mount>, label: string): DOMWrapper<HTMLInputElement> {
  const labelElement = wrapper
    .findAll('label')
    .find((candidate) => candidate.text().includes(label) && candidate.attributes('for') !== undefined)
  expect(labelElement, `label "${label}" is rendered`).toBeTruthy()
  const id = labelElement?.attributes('for')
  expect(id, `label "${label}" points at an input`).toBeTruthy()
  const input = wrapper.find(`input#${id}`)
  expect(input.exists(), `input for "${label}" exists`).toBe(true)
  return input as DOMWrapper<HTMLInputElement>
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string): DOMWrapper<HTMLButtonElement> {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes(text))
  expect(button, `button "${text}" is rendered`).toBeTruthy()
  return button as DOMWrapper<HTMLButtonElement>
}

beforeAll(async () => {
  stubBridge()
  await initLauncher()
  bootCalls = [...calls]
})

beforeEach(async () => {
  calls = []
  failNextCall = null
  pushSnapshot(baseState)
  await flushPromises()
})

describe('renderer shell', () => {
  it('boots from the bridge and lists the navigation entries', async () => {
    const { wrapper } = await mountApp()
    const text = wrapper.text()
    for (const label of ['Play', 'Installed versions', 'Servers', 'Settings', 'Logs']) {
      expect(text).toContain(label)
    }
    expect(bootCalls.some((entry) => entry.service === 'log' && entry.method === 'recent')).toBe(true)
    expect(text).toContain('No versions found')
  })

  it('renders a snapshot pushed by the main process', async () => {
    const { wrapper } = await mountApp()
    pushSnapshot(steveState)
    await flushPromises()
    expect(wrapper.text()).toContain('Steve')
    expect(inputByLabel(wrapper, 'Offline UUID').element.value).toBe('b50ad385-829d-3141-a216-7e7d7539ba7f')
    expect(wrapper.text()).not.toContain('No versions found')
  })

  it('routes a UI action through the service bridge', async () => {
    const { wrapper } = await mountApp()
    const field = inputByLabel(wrapper, 'New profile name')
    await field.setValue('Alex')
    await flushPromises()
    await buttonByText(wrapper, 'Create profile').trigger('click')
    await flushPromises()
    expect(calls).toContainEqual({ service: 'profile', method: 'create', params: { name: 'Alex' } })
  })

  it('shows a failure from the main process without the invoke prefix', async () => {
    const { wrapper } = await mountApp()
    const field = inputByLabel(wrapper, 'New profile name')
    await field.setValue('Alex')
    await flushPromises()
    failNextCall = 'A profile named "Alex" already exists.'
    await buttonByText(wrapper, 'Create profile').trigger('click')
    await flushPromises()
    // The snackbar is teleported to the document body.
    const body = document.body.textContent ?? ''
    expect(body).toContain('A profile named "Alex" already exists.')
    expect(body).not.toContain('Error invoking remote method')
  })

  it('installs the version the picker offers, without preselecting a snapshot', async () => {
    const { wrapper, router } = await mountApp()
    await router.push('/instances')
    await flushPromises()

    expect(calls).toContainEqual({ service: 'install', method: 'versions', params: { refresh: false } })
    // The releases filter is on by default, so nothing on screen should be a
    // snapshot even though the manifest starts with one.
    expect(inputByLabel(wrapper, 'Game version').element.value).toBe('1.21.4')

    await buttonByText(wrapper, 'Install').trigger('click')
    await flushPromises()
    expect(calls).toContainEqual({
      service: 'install',
      method: 'start',
      params: { versionId: '1.21.4', loader: 'vanilla', loaderVersion: null }
    })
    expect(wrapper.text()).toContain('Installed fabric-loader-0.16.9-1.21.4')
  })

  it('navigates between the views', async () => {
    const { wrapper, router } = await mountApp()
    await router.push('/servers')
    await flushPromises()
    expect(wrapper.text()).toContain('Saved servers')
    expect(wrapper.text()).toContain('no account and no session')
    await router.push('/settings')
    await flushPromises()
    expect(wrapper.text()).toContain('Game directory')
    expect(inputByLabel(wrapper, 'Game directory').element.value).toBe('/games/.minecraft')
  })
})

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
  instances: [],
  selectedInstanceId: null,
  servers: [],
  pendingServerId: null,
  settings: {
    instancesDir: '/games/instances',
    javaPath: null,
    maxMemoryMb: 4096,
    width: 1280,
    height: 720,
    theme: 'system'
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
      createdAt: '2026-10-13T00:00:00.000Z',
      lastPlayedAt: null
    }
  ],
  selectedProfileId: 'p1',
  instances: [
    {
      id: 'i1',
      name: 'Steve’s world',
      dir: '/games/instances/steves-world',
      source: 'created',
      versionId: '1.21.4',
      loader: null,
      loaderVersion: null,
      createdAt: '2026-10-13T00:00:00.000Z',
      lastPlayedAt: null,
      jvmArgs: [],
      javaPath: null,
      maxMemoryMb: null
    }
  ],
  selectedInstanceId: 'i1',
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
    if (service === 'modrinth' && method === 'search') {
      return {
        totalHits: 1,
        offset: 0,
        limit: 20,
        folder: 'mods',
        hits: [
          {
            projectId: 'AANobbMI',
            slug: 'sodium',
            title: 'Sodium',
            description: 'Modern rendering engine',
            projectType: 'mod',
            downloads: 12345678,
            follows: 3000,
            iconUrl: null,
            gameVersions: ['1.21.4'],
            loaders: ['fabric'],
            categories: ['fabric', 'optimization'],
            latestVersion: 'v1'
          }
        ]
      }
    }
    if (service === 'modrinth' && method === 'project') {
      return {
        projectId: 'AANobbMI',
        slug: 'sodium',
        title: 'Sodium',
        description: 'Modern rendering engine',
        projectType: 'mod',
        iconUrl: null,
        downloads: 12345678,
        followers: 3000,
        updated: '2026-10-01T00:00:00Z',
        published: '2021-01-01T00:00:00Z',
        categories: ['fabric'],
        gameVersions: ['1.21.4'],
        loaders: ['fabric'],
        body: '# Sodium',
        galleryUrls: [],
        clientSide: 'required',
        serverSide: 'unsupported',
        license: 'LGPL-3.0',
        links: []
      }
    }
    if (service === 'modrinth' && method === 'versions') {
      return [
        {
          id: 'v1',
          projectId: 'AANobbMI',
          name: 'Sodium 0.6.13',
          versionNumber: 'mc1.21.4-0.6.13',
          versionType: 'release',
          gameVersions: ['1.21.4'],
          loaders: ['fabric'],
          files: [],
          dependencies: [],
          datePublished: '2026-09-01T00:00:00Z'
        }
      ]
    }
    if (service === 'save' && method === 'list') {
      return {
        instanceId: 'i1',
        instanceDir: '/games/instances/steves-world',
        dir: '/games/instances/steves-world/saves',
        backupsDir: '/games/instances/steves-world/backups',
        worlds: [
          {
            folder: 'My World',
            name: 'My World',
            version: '1.21.4',
            lastPlayed: '2026-10-01T12:00:00.000Z',
            gameMode: 'survival',
            hardcore: false,
            bytes: 2097152,
            problem: null
          }
        ],
        backups: [
          {
            name: 'before-20261014-101500.zip',
            path: '/games/instances/steves-world/backups/before-20261014-101500.zip',
            bytes: 1048576,
            at: '2026-10-14T10:15:00.000Z',
            kind: 'before-change'
          }
        ]
      }
    }
    if (service === 'modrinth' && method === 'installed') {
      return {
        instanceId: 'i1',
        dir: '/games/instances/steves-world',
        items: [
          {
            projectId: 'AANobbMI',
            title: 'Sodium',
            versionNumber: 'mc1.21.4-0.6.13',
            filename: 'sodium.jar',
            kind: 'mod',
            at: '2026-10-14T12:00:00.000Z'
          }
        ]
      }
    }
    if (service === 'save' && method === 'restoreBackup') {
      return {
        instanceId: 'i1',
        path: '/games/instances/steves-world/backups/before-20261014-101500.zip',
        worlds: [{ name: 'My World', folder: 'My World 2', files: 2, bytes: 1048576 }],
        skipped: 0
      }
    }
    if (service === 'save' && method === 'backup') {
      return {
        name: 'saves-20261014-120000.zip',
        path: '/games/instances/steves-world/backups/saves-20261014-120000.zip',
        bytes: 1048576,
        at: '2026-10-14T12:00:00.000Z',
        kind: 'manual'
      }
    }
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
    for (const label of ['Play', 'Instances', 'Servers', 'Settings', 'Logs']) {
      expect(text).toContain(label)
    }
    expect(bootCalls.some((entry) => entry.service === 'log' && entry.method === 'recent')).toBe(true)
    expect(text).toContain('No instance yet')
  })

  it('renders a snapshot pushed by the main process', async () => {
    const { wrapper } = await mountApp()
    pushSnapshot(steveState)
    await flushPromises()
    expect(wrapper.text()).toContain('Steve')
    // The offline UUID is shown as a value to copy rather than as a field.
    expect(wrapper.text()).toContain('b50ad385-829d-3141-a216-7e7d7539ba7f')
    expect(wrapper.text()).not.toContain('No instance yet')
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

  it('installs the version the picker offers into the selected instance', async () => {
    const { wrapper, router } = await mountApp()
    // The page installs into an instance, so give it one before opening it.
    pushSnapshot(steveState)
    await router.push('/instances')
    await flushPromises()

    expect(calls).toContainEqual({ service: 'install', method: 'versions', params: { refresh: false } })
    expect(calls).toContainEqual({ service: 'instance', method: 'resources', params: { instanceId: 'i1' } })
    // The releases filter is on by default, so nothing on screen should be a
    // snapshot even though the manifest starts with one.
    expect(inputByLabel(wrapper, 'Game version').element.value).toBe('1.21.4')

    await buttonByText(wrapper, 'Install').trigger('click')
    await flushPromises()
    expect(calls).toContainEqual({
      service: 'install',
      method: 'start',
      params: { versionId: '1.21.4', loader: 'vanilla', loaderVersion: null, instanceId: 'i1' }
    })
    // The instance plays what was installed, so Play is one click away next time.
    expect(calls).toContainEqual({
      service: 'instance',
      method: 'update',
      params: {
        id: 'i1',
        patch: { versionId: 'fabric-loader-0.16.9-1.21.4', loader: 'vanilla', loaderVersion: null }
      }
    })
    expect(wrapper.text()).toContain('Installed fabric-loader-0.16.9-1.21.4')
  })

  it('lists an instance’s worlds and takes a backup on request', async () => {
    const { wrapper, router } = await mountApp()
    pushSnapshot(steveState)
    await router.push('/instances')
    await flushPromises()

    expect(calls).toContainEqual({ service: 'save', method: 'list', params: { instanceId: 'i1' } })
    // The worlds have a tab of their own, so open it before looking at them.
    await buttonByText(wrapper, 'Worlds').trigger('click')
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('My World')
    // The line under a world is the world's own: its version, its game mode, when
    // it was last played and how big it is.
    expect(text).toContain('1.21.4 · survival · last played 2026-10-01 · 2.0 MB')
    expect(text).toContain('before-20261014-101500.zip')

    await buttonByText(wrapper, 'Back up now').trigger('click')
    await flushPromises()
    expect(calls).toContainEqual({
      service: 'save',
      method: 'backup',
      params: { instanceId: 'i1', kind: 'manual' }
    })
  })

  it('browses Modrinth and opens a project without leaving the app', async () => {
    const { wrapper, router } = await mountApp()
    pushSnapshot(steveState)
    await router.push('/store')
    await flushPromises()

    // The store opens on the catalogue with no search word, so it asks for the
    // most downloaded rather than for a relevance ranking with nothing to rank.
    // `anyVersion: false` is the store obeying the selected instance; the switch
    // above the list turns it off to browse everything.
    expect(calls).toContainEqual({
      service: 'modrinth',
      method: 'search',
      params: { query: '', kind: 'mod', sort: 'downloads', limit: 20, offset: 0, anyVersion: false }
    })
    expect(wrapper.text()).toContain('Sodium')
    expect(wrapper.text()).toContain('12.3M downloads')

    await buttonByText(wrapper, 'Details').trigger('click')
    await flushPromises()
    expect(calls).toContainEqual({ service: 'modrinth', method: 'project', params: { projectIdOrSlug: 'AANobbMI' } })
    expect(calls).toContainEqual({
      service: 'modrinth',
      method: 'versions',
      params: { projectIdOrSlug: 'AANobbMI', free: false }
    })
    // The dialog shows the project's own page, not the card: its published body,
    // its licence and its version list.
    const dialog = document.body.textContent ?? ''
    expect(dialog).toContain('# Sodium')
    expect(dialog).toContain('LGPL-3.0')
    expect(dialog).toContain('mc1.21.4-0.6.13')
  })

  it('opens the store with nothing selected and no search word', async () => {
    const { wrapper, router } = await mountApp()
    // Nothing is selected here, which is exactly when the page used to show a
    // warning and nothing else: the catalogue has to load regardless.
    pushSnapshot(baseState)
    await router.push('/store')
    await flushPromises()

    expect(calls).toContainEqual({
      service: 'modrinth',
      method: 'search',
      params: { query: '', kind: 'mod', sort: 'downloads', limit: 20, offset: 0, anyVersion: false }
    })
    expect(wrapper.text()).toContain('Sodium')
    // And an install says where things go instead of looking inert.
    expect(wrapper.text()).toContain('No instance selected')
    expect(wrapper.text()).toContain('Choose an instance')
  })

  it('restores a backup beside the world that is already there', async () => {
    const { wrapper, router } = await mountApp()
    pushSnapshot(steveState)
    await router.push('/instances')
    await flushPromises()
    await buttonByText(wrapper, 'Worlds').trigger('click')
    await flushPromises()

    await buttonByText(wrapper, 'Restore').trigger('click')
    await flushPromises()
    expect(calls).toContainEqual({
      service: 'save',
      method: 'restoreBackup',
      params: {
        instanceId: 'i1',
        path: '/games/instances/steves-world/backups/before-20261014-101500.zip'
      }
    })
    // What came back, and that nothing was taken away to make room for it.
    expect(wrapper.text()).toContain('Restored My World 2')
    expect(wrapper.text()).toContain('untouched')
  })

  it('marks a project the selected instance already has', async () => {
    const { wrapper, router } = await mountApp()
    pushSnapshot(steveState)
    await router.push('/store')
    await flushPromises()

    expect(calls).toContainEqual({
      service: 'modrinth',
      method: 'installed',
      params: { instanceId: 'i1' }
    })
    expect(wrapper.text()).toContain('In Steve’s world')
  })

  it('takes a keyboard shortcut to another page', async () => {
    const { router } = await mountApp()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: '2', ctrlKey: true }))
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/store')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: '5', ctrlKey: true }))
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/settings')
  })

  it('opens the instances page on the tab it was linked to', async () => {
    const { wrapper, router } = await mountApp()
    pushSnapshot(steveState)
    await router.push('/instances?tab=worlds')
    await flushPromises()
    // The tab is in the URL, so the worlds are on screen on arrival.
    expect(wrapper.text()).toContain('My World')
    expect(wrapper.text()).toContain('Back up now')
  })

  it('navigates between the views', async () => {
    const { wrapper, router } = await mountApp()
    await router.push('/servers')
    await flushPromises()
    expect(wrapper.text()).toContain('Saved servers')
    expect(wrapper.text()).toContain('no account and no session')
    await router.push('/settings')
    await flushPromises()
    expect(wrapper.text()).toContain('Instances folder')
    expect(inputByLabel(wrapper, 'Instances folder').element.value).toBe('/games/instances')
  })
})

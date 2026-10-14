<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type {
  GameInstance,
  InstallLoader,
  InstanceResources,
  LoaderOption,
  ModpackSummary,
  ModrinthHit,
  ModrinthVersion,
  RemoteVersion,
  ResourceEntry,
  ResourceKind
} from '@shared/types'
import { useLauncher } from '@renderer/composables/useLauncher'

const {
  instances,
  installations,
  selectedInstance,
  tasks,
  createInstance,
  updateInstance,
  selectInstance,
  removeInstance,
  importInstanceFolder,
  resources,
  setResourceEnabled,
  addResources,
  removeResource,
  inspectModpack,
  importModpack,
  modrinthSearch,
  modrinthVersions,
  installFromModrinth,
  pickFolder,
  pickFiles,
  openPath,
  rescan,
  remoteVersions,
  loaderVersions,
  installVersion,
  settings
} = useLauncher()

const busy = ref(false)
const loadingVersions = ref(false)
const note = ref<string | null>(null)
const noteType = ref<'info' | 'success' | 'warning'>('info')

/** Install form. */
const versionItems = ref<RemoteVersion[]>([])
const versionFilter = ref<'release' | 'snapshot' | 'all'>('release')
const selectedVersion = ref<string | null>(null)
const loader = ref<InstallLoader>('vanilla')
const loaderItems = ref<LoaderOption[]>([])
const selectedLoader = ref<string | null>(null)

/** Instance form. */
const showNewInstance = ref(false)
const newInstanceName = ref('')

/** Resources. */
const instanceResources = ref<InstanceResources | null>(null)
const resourceTab = ref<ResourceKind>('mod')
const tabKinds: { title: string; value: ResourceKind }[] = [
  { title: 'Mods', value: 'mod' },
  { title: 'Resource packs', value: 'resourcepack' },
  { title: 'Shader packs', value: 'shaderpack' }
]

/** Modpack. */
const modpackSummary = ref<ModpackSummary | null>(null)
const modpackPath = ref<string | null>(null)
const showModpack = ref(false)

/** Modrinth browsing, scoped to the instance and the tab that is open. */
const browse = reactive({
  open: false,
  query: '',
  loading: false,
  searched: false,
  hits: [] as ModrinthHit[],
  totalHits: 0,
  selected: null as ModrinthHit | null,
  versions: [] as ModrinthVersion[],
  versionsLoading: false,
  installing: false
})

const loaderChoices = [
  { title: 'Vanilla — the game alone', value: 'vanilla' },
  { title: 'Fabric', value: 'fabric' },
  { title: 'Quilt', value: 'quilt' }
]

const visibleVersions = computed(() =>
  versionFilter.value === 'all'
    ? versionItems.value
    : versionItems.value.filter((version) => version.type === versionFilter.value)
)

const runningTask = computed(() => tasks.value.find((task) => task.state === 'running') ?? null)
const entries = computed<ResourceEntry[]>(() => instanceResources.value?.entries[resourceTab.value] ?? [])
const resourceFolder = computed(() => instanceResources.value?.folders[resourceTab.value] ?? '')
const entryWarnings = computed(() =>
  entries.value.flatMap((entry) =>
    (entry.metadata?.warnings ?? []).map((warning) => `${entry.title}: ${warning}`)
  )
)
const tabKindLabel = computed(
  () => tabKinds.find((kind) => kind.value === resourceTab.value)?.title ?? 'mods'
)

/** A mod reads as its real name and version, not the file name someone chose. */
function entryTitle(entry: ResourceEntry): string {
  const metadata = entry.metadata
  if (!metadata?.name) return entry.title
  return `${metadata.name}${metadata.version ? ` ${metadata.version}` : ''}`
}

function entrySubtitle(entry: ResourceEntry): string {
  const parts: string[] = [entry.enabled ? 'enabled' : 'disabled']
  const metadata = entry.metadata
  if (metadata) {
    parts.push(`${metadata.loader}${metadata.id ? ` · ${metadata.id}` : ''}`)
  } else {
    parts.push('no readable metadata')
  }
  parts.push(`${Math.round(entry.size / 1024)} KB`)
  return parts.join(' · ')
}

const canInstall = computed(
  () =>
    selectedInstance.value !== null &&
    selectedVersion.value !== null &&
    !busy.value &&
    (loader.value === 'vanilla' || selectedLoader.value !== null)
)

function report(message: string, type: 'info' | 'success' | 'warning' = 'info'): void {
  noteType.value = type
  note.value = message
}

async function loadResources(): Promise<void> {
  if (!selectedInstance.value) {
    instanceResources.value = null
    return
  }
  try {
    instanceResources.value = await resources(selectedInstance.value.id)
  } catch {
    // Reported in the snackbar.
  }
}

async function loadVersions(refresh = false): Promise<void> {
  loadingVersions.value = true
  try {
    versionItems.value = await remoteVersions(refresh)
    if (!selectedVersion.value && versionItems.value.length > 0) {
      // The newest *offered* version, which is a release by default: the first
      // entry of the manifest is usually a snapshot, and preselecting something
      // the filter hides would look like an empty picker.
      const offered = versionItems.value.filter(
        (version) => versionFilter.value === 'all' || version.type === versionFilter.value
      )
      selectedVersion.value = (offered[0] ?? versionItems.value[0]).id
    }
  } catch {
    report('Could not reach Mojang to list versions. Check the connection and try again.', 'warning')
  } finally {
    loadingVersions.value = false
  }
}

async function onLoaderChange(): Promise<void> {
  loaderItems.value = []
  selectedLoader.value = null
  if (loader.value === 'vanilla') return
  try {
    loaderItems.value = await loaderVersions(loader.value)
    // Newest stable first: a loader built the same day often has no release yet.
    const stable = loaderItems.value.filter((item) => item.stable)
    selectedLoader.value = (stable[0] ?? loaderItems.value[0])?.version ?? null
  } catch {
    report(`Could not list ${loader.value} loader versions.`, 'warning')
  }
}

async function createInstanceFromName(): Promise<void> {
  const name = newInstanceName.value.trim()
  if (name === '') return
  busy.value = true
  try {
    const created = await createInstance({ name })
    newInstanceName.value = ''
    showNewInstance.value = false
    report(`Created ${created.name} at ${created.dir}. Install a version to start playing.`, 'success')
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function importFolder(): Promise<void> {
  const picked = await pickFolder()
  if (!picked.path) return
  busy.value = true
  try {
    const result = await importInstanceFolder(picked.path)
    report(result.message, 'success')
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function removeSelected(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  busy.value = true
  try {
    const result = await removeInstance(instance.id, false)
    report(`Removed ${result.removed} from the launcher; its files are still at ${result.dir}.`, 'success')
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function selectInstanceById(id: string): Promise<void> {
  await selectInstance(id)
  await rescan()
  await loadResources()
}

async function install(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance || !selectedVersion.value) return
  busy.value = true
  note.value = null
  try {
    const result = await installVersion({
      versionId: selectedVersion.value,
      loader: loader.value,
      loaderVersion: loader.value === 'vanilla' ? null : selectedLoader.value,
      instanceId: instance.id
    })
    // The instance plays what was installed, so the next step is Play.
    await updateInstance(instance.id, {
      versionId: result.targetId,
      loader: loader.value,
      loaderVersion: loader.value === 'vanilla' ? null : selectedLoader.value
    })
    await rescan()
    report(
      `Installed ${result.targetId}: ${result.files} files checked, ${result.reused} already ` +
        `present, ${result.nativesExtracted} native libraries unpacked.`,
      'success'
    )
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function addResourceFiles(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  const picked = await pickFiles()
  if (picked.paths.length === 0) return
  busy.value = true
  try {
    const result = await addResources({
      instanceId: instance.id,
      kind: resourceTab.value,
      paths: picked.paths
    })
    instanceResources.value = result.resources
    if (result.added.length > 0) report(`Added ${result.added.join(', ')}.`, 'success')
    if (result.skipped.length > 0) report(`Skipped: ${result.skipped.join(' ')}`, 'warning')
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function toggleResource(entry: ResourceEntry): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  try {
    instanceResources.value = await setResourceEnabled({
      instanceId: instance.id,
      kind: entry.kind,
      name: entry.name,
      enabled: !entry.enabled
    })
  } catch {
    // Reported in the snackbar.
  }
}

async function deleteResource(entry: ResourceEntry): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  try {
    instanceResources.value = await removeResource({
      instanceId: instance.id,
      kind: entry.kind,
      name: entry.name
    })
  } catch {
    // Reported in the snackbar.
  }
}

async function openResourceFolder(): Promise<void> {
  if (!selectedInstance.value) return
  // Make sure the folder exists before handing it to the OS.
  instanceResources.value = await resources(selectedInstance.value.id)
  await openPath(resourceFolder.value)
}

function openBrowse(): void {
  browse.open = true
  browse.hits = []
  browse.selected = null
  browse.versions = []
  browse.searched = false
  if (browse.query.trim() !== '') void runSearch()
}

async function runSearch(): Promise<void> {
  if (!selectedInstance.value || browse.query.trim() === '') return
  browse.loading = true
  try {
    const result = await modrinthSearch({
      query: browse.query.trim(),
      kind: resourceTab.value,
      limit: 20
    })
    browse.hits = result.hits
    browse.totalHits = result.totalHits
    browse.searched = true
    browse.selected = null
    browse.versions = []
  } catch {
    // Reported in the snackbar.
  } finally {
    browse.loading = false
  }
}

async function chooseHit(hit: ModrinthHit): Promise<void> {
  browse.selected = hit
  browse.versions = []
  browse.versionsLoading = true
  try {
    browse.versions = await modrinthVersions(hit.projectId)
  } catch {
    // Reported in the snackbar.
  } finally {
    browse.versionsLoading = false
  }
}

async function installFromBrowse(versionId?: string): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  browse.installing = true
  try {
    const result = await installFromModrinth({
      projectId: browse.selected?.projectId ?? null,
      versionId: versionId ?? null,
      kind: resourceTab.value,
      instanceId: instance.id
    })
    const dependencies = result.installed.filter((item) => item.dependency)
    report(
      `Installed ${result.requested}` +
        (dependencies.length > 0 ? ` and ${dependencies.length} required dependency file(s)` : '') +
        ` into ${tabKindLabel.value.toLowerCase()}.` +
        (result.warnings.length > 0 ? ` ${result.warnings.join(' ')}` : ''),
      result.warnings.length > 0 ? 'warning' : 'success'
    )
    browse.open = false
    await loadResources()
  } catch {
    // Reported in the snackbar.
  } finally {
    browse.installing = false
  }
}

async function chooseModpack(): Promise<void> {
  const picked = await pickFiles()
  const path = picked.paths[0]
  if (!path) return
  busy.value = true
  try {
    const summary = await inspectModpack(path)
    modpackSummary.value = summary
    modpackPath.value = path
    showModpack.value = true
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function installModpack(): Promise<void> {
  const path = modpackPath.value
  if (!path) return
  showModpack.value = false
  busy.value = true
  try {
    const result = await importModpack(path)
    const unresolved =
      result.unresolved.length > 0
        ? ` ${result.unresolved.length} file(s) need CurseForge's API and were not downloaded; the log lists them.`
        : ''
    report(
      `Imported ${result.summary.name} as ${result.instance.name}: ${result.files} files downloaded, ` +
        `${result.overrides} unpacked from the pack.${unresolved}`,
      result.unresolved.length > 0 ? 'warning' : 'success'
    )
    await selectInstance(result.instance.id)
    await loadResources()
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

function describe(instance: GameInstance): string {
  const version = instance.versionId ?? 'no version yet'
  const loaderLabel = instance.loader && instance.loader !== 'vanilla' ? ` · ${instance.loader}` : ''
  return `${version}${loaderLabel} · ${instance.source}`
}

function instanceProps(instance: GameInstance): { subtitle: string } {
  return { subtitle: describe(instance) }
}

watch(
  () => selectedInstance.value?.id ?? null,
  () => {
    void loadResources()
  }
)

onMounted(async () => {
  await loadResources()
  await loadVersions()
})
</script>

<template>
  <v-row>
    <v-col cols="12" md="6">
      <v-card>
        <v-card-title>Instances</v-card-title>
        <v-card-text>
          <v-select
            :items="instances"
            item-title="name"
            item-value="id"
            label="Instance"
            :model-value="selectedInstance?.id ?? null"
            :disabled="instances.length === 0"
            :item-props="instanceProps"
            @update:model-value="selectInstanceById"
          />

          <p v-if="selectedInstance" class="text-medium-emphasis mb-1">
            {{ describe(selectedInstance) }}
          </p>
          <p v-if="selectedInstance" class="text-medium-emphasis mb-0 text-body-2">
            {{ selectedInstance.dir }}
          </p>

          <v-alert v-if="instances.length === 0" type="info" variant="tonal" class="mt-4">
            An instance is one game directory with its own version and its own mods, resource packs
            and shader packs. Create one, copy a version folder into it, or point the launcher at a
            game directory you already have.
          </v-alert>

          <v-alert v-if="note" :type="noteType" variant="tonal" class="mt-4">{{ note }}</v-alert>

          <div v-if="runningTask" class="mt-4">
            <v-progress-linear :model-value="Math.round(runningTask.progress * 100)" height="8" rounded />
            <p class="text-medium-emphasis mt-2 mb-0">{{ runningTask.name }} — {{ runningTask.message }}</p>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-btn :disabled="busy" @click="showNewInstance = true">New instance</v-btn>
          <v-btn :disabled="busy" @click="importFolder">Import a folder</v-btn>
          <v-btn :disabled="busy" @click="chooseModpack">Import a modpack</v-btn>
          <v-spacer />
          <v-btn variant="text" color="error" :disabled="!selectedInstance" @click="removeSelected">
            Remove
          </v-btn>
        </v-card-actions>
      </v-card>

      <v-card class="mt-4">
        <v-card-title>Install a version here</v-card-title>
        <v-card-text>
          <p class="text-medium-emphasis">
            Installing goes into
            <strong>{{ selectedInstance?.name ?? 'the selected instance' }}</strong
            >. New instances are created under {{ settings?.instancesDir ?? 'the instances folder' }}.
          </p>

          <v-btn-toggle v-model="versionFilter" density="comfortable" mandatory class="my-4">
            <v-btn value="release" size="small">Releases</v-btn>
            <v-btn value="snapshot" size="small">Snapshots</v-btn>
            <v-btn value="all" size="small">All</v-btn>
          </v-btn-toggle>

          <v-autocomplete
            v-model="selectedVersion"
            :items="visibleVersions"
            item-title="id"
            item-value="id"
            label="Game version"
            :loading="loadingVersions"
            hide-details
          />

          <v-select
            v-model="loader"
            :items="loaderChoices"
            label="Mod loader"
            class="mt-4"
            hide-details
            @update:model-value="onLoaderChange"
          />

          <v-select
            v-if="loader !== 'vanilla'"
            v-model="selectedLoader"
            :items="loaderItems"
            item-title="version"
            item-value="version"
            label="Loader version"
            class="mt-4"
            hide-details
          />

          <p class="text-medium-emphasis mt-4 mb-0">
            Files come from Mojang's own servers, and from Fabric's or Quilt's metadata for a loader.
            Each one is checked against the hash its version json declares, and an interrupted
            download resumes rather than starting over.
          </p>
        </v-card-text>
        <v-card-actions>
          <v-btn color="primary" :disabled="!canInstall" :loading="busy" @click="install">Install</v-btn>
          <v-btn variant="text" :disabled="loadingVersions" @click="loadVersions(true)">Refresh list</v-btn>
        </v-card-actions>
      </v-card>
    </v-col>

    <v-col cols="12" md="6">
      <v-card>
        <v-card-title>Mods, packs and shaders</v-card-title>
        <v-card-text>
          <v-tabs v-model="resourceTab" density="comfortable">
            <v-tab v-for="kind in tabKinds" :key="kind.value" :value="kind.value">{{ kind.title }}</v-tab>
          </v-tabs>

          <p v-if="instanceResources" class="text-medium-emphasis mt-4 text-body-2">
            {{ resourceFolder }}
          </p>

          <p v-if="!selectedInstance" class="text-medium-emphasis mt-4">
            Select or create an instance first.
          </p>
          <template v-else>
            <p v-if="entries.length === 0" class="text-medium-emphasis mt-4">
              Nothing in this folder yet. Add files, take something from Modrinth, or drop them in
              yourself — turning one off renames it with a <code>.disabled</code> suffix, which every
              loader and pack list ignores.
            </p>

            <v-alert v-if="entryWarnings.length > 0" type="warning" variant="tonal" class="mt-4">
              <p v-for="warning in entryWarnings" :key="warning" class="mb-2">{{ warning }}</p>
            </v-alert>

            <v-list v-if="entries.length > 0" density="compact" class="mt-2">
            <v-list-item v-for="entry in entries" :key="entry.name" :title="entryTitle(entry)">
              <template #subtitle>
                <span>{{ entrySubtitle(entry) }}</span>
                <span v-if="entry.metadata?.warnings.length" class="text-warning">
                  — {{ entry.metadata.warnings.join(' ') }}
                </span>
              </template>
              <template #append>
                <v-switch
                  :model-value="entry.enabled"
                  color="primary"
                  density="compact"
                  hide-details
                  class="mr-2"
                  @update:model-value="toggleResource(entry)"
                />
                <v-btn size="small" variant="text" @click="deleteResource(entry)">Remove</v-btn>
                </template>
              </v-list-item>
            </v-list>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-btn color="primary" :disabled="!selectedInstance || busy" @click="openBrowse">
            Add from Modrinth
          </v-btn>
          <v-btn :disabled="!selectedInstance || busy" @click="addResourceFiles">Add files</v-btn>
          <v-btn variant="text" :disabled="!selectedInstance" @click="openResourceFolder">
            Open folder
          </v-btn>
        </v-card-actions>
      </v-card>

      <v-card class="mt-4">
        <v-card-title>Versions in this instance</v-card-title>
        <v-card-text>
          <v-alert v-if="installations.length === 0" type="info" variant="tonal" class="mb-2">
            Nothing under <code>versions/</code> yet.
          </v-alert>
          <v-list v-else density="compact">
            <v-list-item
              v-for="version in installations"
              :key="version.id"
              :title="version.id"
              :subtitle="`${version.type ?? 'unknown type'} · java ${version.javaMajorVersion ?? 'any'} · assets ${version.assetsIndex ?? 'none'}${version.inheritsFrom ? ' · inherits ' + version.inheritsFrom : ''}`"
            />
          </v-list>
        </v-card-text>
        <v-card-actions>
          <v-btn variant="text" :disabled="!selectedInstance" @click="rescan()">Rescan</v-btn>
        </v-card-actions>
      </v-card>
    </v-col>
  </v-row>

  <v-dialog v-model="showNewInstance" max-width="520">
    <v-card>
      <v-card-title>New instance</v-card-title>
      <v-card-text>
        <v-text-field
          v-model="newInstanceName"
          label="Instance name"
          hint="Up to 48 characters; it names the folder too."
          persistent-hint
          autofocus
          @keyup.enter="createInstanceFromName"
        />
        <p class="text-medium-emphasis mt-4 mb-0">
          It starts empty. Install a version into it, or import a pack.
        </p>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="showNewInstance = false">Cancel</v-btn>
        <v-btn color="primary" :disabled="busy || newInstanceName.trim() === ''" @click="createInstanceFromName">
          Create
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="browse.open" max-width="820" scrollable>
    <v-card>
      <v-card-title>Add {{ tabKindLabel.toLowerCase() }} from Modrinth</v-card-title>
      <v-card-text>
        <p class="text-medium-emphasis">
          Searching for {{ selectedInstance?.versionId ?? 'the instance version' }}
          <template v-if="selectedInstance?.loader && selectedInstance.loader !== 'vanilla'">
            on {{ selectedInstance.loader }}
          </template>
          , so what is listed fits this instance.
        </p>
        <div class="d-flex ga-2 align-center">
          <v-text-field
            v-model="browse.query"
            label="Search Modrinth"
            hide-details
            autofocus
            @keyup.enter="runSearch"
          />
          <v-btn :loading="browse.loading" @click="runSearch">Search</v-btn>
        </div>

        <p
          v-if="!browse.loading && browse.searched && browse.hits.length === 0"
          class="text-medium-emphasis mt-4"
        >
          Nothing found. Modrinth filters by this instance's version and loader, so a mod built for
          another one will not appear.
        </p>

        <v-list v-if="browse.hits.length > 0" density="compact" class="mt-4">
          <v-list-item
            v-for="hit in browse.hits"
            :key="hit.projectId"
            :title="hit.title"
            :subtitle="hit.description"
            :active="browse.selected?.projectId === hit.projectId"
            @click="chooseHit(hit)"
          >
            <template #append>
              <span class="text-medium-emphasis text-body-2">
                {{ hit.downloads.toLocaleString() }} downloads
              </span>
            </template>
          </v-list-item>
        </v-list>

        <template v-if="browse.selected">
          <v-divider class="my-4" />
          <p>
            <strong>{{ browse.selected.title }}</strong> — pick a version. Required dependencies are
            installed with it, and every file is checked against the hash Modrinth published.
          </p>
          <v-btn
            v-if="browse.versions.length > 0"
            color="primary"
            class="mb-2"
            :loading="browse.installing"
            @click="installFromBrowse(browse.versions[0].id)"
          >
            Install {{ browse.versions[0].versionNumber }}
            <template v-if="browse.versions[0].versionType !== 'release'">
              ({{ browse.versions[0].versionType }})
            </template>
          </v-btn>
          <v-progress-linear v-if="browse.versionsLoading" indeterminate height="3" />
          <v-list v-else density="compact">
            <v-list-item
              v-for="version in browse.versions"
              :key="version.id"
              :title="`${version.versionNumber} (${version.versionType})`"
              :subtitle="`${version.gameVersions.slice(0, 4).join(', ')} · ${version.loaders.join(', ') || 'any loader'}`"
            >
              <template #append>
                <v-btn size="small" :disabled="browse.installing" @click="installFromBrowse(version.id)">
                  Install
                </v-btn>
              </template>
            </v-list-item>
          </v-list>
        </template>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="browse.open = false">Close</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="showModpack" max-width="620">
    <v-card>
      <v-card-title>{{ modpackSummary?.name ?? 'Modpack' }}</v-card-title>
      <v-card-text v-if="modpackSummary">
        <v-list density="compact">
          <v-list-item title="Format" :subtitle="modpackSummary.format" />
          <v-list-item title="Minecraft" :subtitle="modpackSummary.gameVersion ?? 'not named'" />
          <v-list-item
            title="Loader"
            :subtitle="`${modpackSummary.loader ?? 'vanilla'}${modpackSummary.loaderVersion ? ' ' + modpackSummary.loaderVersion : ''}`"
          />
          <v-list-item title="Files to download" :subtitle="String(modpackSummary.downloadable)" />
          <v-list-item title="Files inside the pack" :subtitle="String(modpackSummary.bundled)" />
        </v-list>
        <v-alert v-if="modpackSummary.unresolved.length > 0" type="warning" variant="tonal" class="mt-4">
          {{ modpackSummary.unresolved.length }} file(s) in this pack are identified only by CurseForge
          project and file id, and this launcher does not call CurseForge's API. Everything else is
          installed, and the log lists what to add by hand.
        </v-alert>
        <p class="text-medium-emphasis mt-4 mb-0">
          A new instance is created for the pack, so it cannot disturb the ones you have.
        </p>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="showModpack = false">Cancel</v-btn>
        <v-btn color="primary" :loading="busy" @click="installModpack">Import</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

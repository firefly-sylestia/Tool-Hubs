<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { InstallLoader, LoaderOption, RemoteVersion } from '@shared/types'
import { useLauncher } from '@renderer/composables/useLauncher'

const {
  installations,
  settings,
  tasks,
  selectedProfile,
  rescan,
  remoteVersions,
  loaderVersions,
  installVersion,
  importFolder,
  pickFolder,
  updateProfile
} = useLauncher()

const gameDir = computed(() => settings.value?.gameDir ?? '')

const versionItems = ref<RemoteVersion[]>([])
const versionFilter = ref<'release' | 'snapshot' | 'all'>('release')
const selectedVersion = ref<string | null>(null)
const loader = ref<InstallLoader>('vanilla')
const loaderItems = ref<LoaderOption[]>([])
const selectedLoader = ref<string | null>(null)
const busy = ref(false)
const loadingVersions = ref(false)
const note = ref<string | null>(null)
const noteType = ref<'info' | 'success' | 'warning'>('info')

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

/** The install currently running, so its progress is visible on this page too. */
const runningTask = computed(() => tasks.value.find((task) => task.state === 'running') ?? null)

const canInstall = computed(
  () =>
    selectedVersion.value !== null &&
    !busy.value &&
    (loader.value === 'vanilla' || selectedLoader.value !== null)
)

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
    noteType.value = 'warning'
    note.value = 'Could not reach Mojang to list versions. Check the connection and try again.'
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
    noteType.value = 'warning'
    note.value = `Could not list ${loader.value} loader versions.`
  }
}

async function install(): Promise<void> {
  if (!canInstall.value || !selectedVersion.value) return
  busy.value = true
  note.value = null
  try {
    const result = await installVersion({
      versionId: selectedVersion.value,
      loader: loader.value,
      loaderVersion: loader.value === 'vanilla' ? null : selectedLoader.value
    })
    // Point a profile that has nothing selected at what was just installed, so
    // the next step is Play rather than hunting for the version again.
    const profile = selectedProfile.value
    if (profile && !profile.versionId) {
      await updateProfile(profile.id, { versionId: result.targetId })
    }
    noteType.value = 'success'
    note.value =
      `Installed ${result.targetId}: ${result.files} files checked, ` +
      `${result.reused} already present, ${result.nativesExtracted} native libraries unpacked.`
  } catch (error) {
    noteType.value = 'warning'
    note.value = `Install failed: ${(error as Error).message}`
  } finally {
    busy.value = false
  }
}

async function importFromDisk(): Promise<void> {
  const picked = await pickFolder()
  if (!picked.path) return
  busy.value = true
  try {
    const result = await importFolder(picked.path)
    noteType.value = 'success'
    note.value = result.message
  } catch (error) {
    noteType.value = 'warning'
    note.value = (error as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void loadVersions()
})
</script>

<template>
  <v-row>
    <v-col cols="12" md="7">
      <v-card>
        <v-card-title>Install a version</v-card-title>
        <v-card-text>
          <v-btn-toggle v-model="versionFilter" density="comfortable" mandatory class="mb-4">
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
            hint="Versions marked stable are releases; the rest are pre-releases."
            persistent-hint
          />

          <v-alert v-if="note" :type="noteType" variant="tonal" class="mt-4">
            {{ note }}
          </v-alert>

          <div v-if="runningTask" class="mt-4">
            <v-progress-linear :model-value="Math.round(runningTask.progress * 100)" height="8" rounded />
            <p class="text-medium-emphasis mt-2 mb-0">{{ runningTask.name }} — {{ runningTask.message }}</p>
          </div>

          <p class="text-medium-emphasis mt-4 mb-0">
            Files come from Mojang's own servers, and from Fabric's or Quilt's metadata for a loader.
            Each file is checked against the hash its version json declares, and a download that is
            interrupted keeps its place and resumes, so nothing half-fetched is ever counted as
            installed.
          </p>
        </v-card-text>
        <v-card-actions>
          <v-btn color="primary" :disabled="!canInstall" :loading="busy" @click="install">
            Install
          </v-btn>
          <v-btn variant="text" :disabled="loadingVersions" @click="loadVersions(true)">Refresh list</v-btn>
        </v-card-actions>
      </v-card>
    </v-col>

    <v-col cols="12" md="5">
      <v-card>
        <v-card-title>Installed versions</v-card-title>
        <v-card-text>
          <p class="text-medium-emphasis">Game directory: {{ gameDir }}</p>
          <v-alert v-if="installations.length === 0" type="info" variant="tonal" class="my-4">
            Nothing found under <code>versions/</code> yet. Install one above, or point the launcher at
            an installation you already have.
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
          <v-btn :loading="busy" @click="importFromDisk">Add from a folder</v-btn>
          <v-btn variant="text" @click="rescan">Rescan</v-btn>
        </v-card-actions>
      </v-card>
    </v-col>
  </v-row>
</template>

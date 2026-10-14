<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { JavaStatus, ThemeChoice } from '@shared/types'
import { useLauncher, type DetectedJava } from '@renderer/composables/useLauncher'

const {
  settings,
  selectedInstance,
  saveSettings,
  rescan,
  detectJava,
  javaStatus,
  installJava,
  pickFolder,
  pickFiles
} = useLauncher()

const form = reactive({
  instancesDir: '',
  javaPath: '',
  maxMemoryMb: 4096,
  width: 1280,
  height: 720,
  theme: 'system' as ThemeChoice
})

/**
 * The themes on offer, and the one the window is painting.
 *
 * A theme is a choice and not a saved value, so it is applied as it is picked
 * rather than waiting for Save: nothing is worse than a theme you cannot see
 * until you have committed to it.
 */
const themeOptions = [
  { value: 'system', title: 'Follow the desktop', icon: 'mdi-theme-light-dark' },
  { value: 'light', title: 'Light', icon: 'mdi-white-balance-sunny' },
  { value: 'dark', title: 'Dark', icon: 'mdi-weather-night' }
]

async function chooseTheme(value: unknown): Promise<void> {
  const choice: ThemeChoice = value === 'light' || value === 'dark' ? value : 'system'
  form.theme = choice
  try {
    await saveSettings({ theme: choice })
  } catch {
    // Surfaced by the composable.
  }
}
const saved = ref(false)
const found = ref<DetectedJava[]>([])
const status = ref<JavaStatus | null>(null)
const findingJava = ref(false)
const downloading = ref(false)
const javaNote = ref<string | null>(null)

/** What this instance needs, in words, so the section reads as one answer. */
const requiredLabel = computed(() => {
  const required = status.value?.requiredMajor
  if (required === null || required === undefined) return 'not known for this version'
  return `Java ${required}`
})

const hasTheRuntime = computed(() =>
  (status.value?.installed ?? []).some((runtime) => runtime.component === status.value?.component)
)

/**
 * Automatic, the runtimes this machine has, and anything set by hand.
 *
 * A text field for a path was the wrong shape for this: the launcher can find
 * Java itself, so the choice is which of the ones it found, or none.
 */
const javaItems = computed(() => {
  const items = [
    { value: '', title: 'Automatic', subtitle: 'the runtime this version asks for' },
    ...found.value.map((java) => ({
      value: java.path,
      title: `Java ${java.major ?? 'unknown'}`,
      subtitle: `${java.source} · ${java.path}`
    }))
  ]
  if (form.javaPath !== '' && !items.some((item) => item.value === form.javaPath)) {
    items.push({ value: form.javaPath, title: 'Set by hand', subtitle: form.javaPath })
  }
  return items
})

watch(
  settings,
  (value) => {
    if (!value) return
    form.instancesDir = value.instancesDir
    form.javaPath = value.javaPath ?? ''
    form.maxMemoryMb = value.maxMemoryMb
    form.width = value.width
    form.height = value.height
    form.theme = value.theme
  },
  { immediate: true }
)

watch(
  () => selectedInstance.value?.id ?? null,
  () => {
    void refreshJava()
  },
  { immediate: true }
)

async function refreshJava(): Promise<void> {
  try {
    status.value = await javaStatus(selectedInstance.value?.id ?? null)
    found.value = status.value.detected
  } catch {
    // Surfaced by the composable.
  }
}

async function save(): Promise<void> {
  saved.value = false
  try {
    await saveSettings({
      instancesDir: form.instancesDir,
      javaPath: form.javaPath.trim() === '' ? null : form.javaPath.trim(),
      maxMemoryMb: Number(form.maxMemoryMb),
      width: Number(form.width),
      height: Number(form.height)
    })
    saved.value = true
    await rescan()
    await refreshJava()
  } catch {
    // Surfaced by the composable.
  }
}

/** Looks for a JDK on this machine, and says so when there is none. */
async function findJava(): Promise<void> {
  findingJava.value = true
  javaNote.value = null
  try {
    const detected = await detectJava()
    await refreshJava()
    if (detected.length === 0) {
      javaNote.value =
        'No Java on this machine: nothing in JAVA_HOME, the usual install folders, or on PATH. ' +
        "That is normal on Windows, so use Mojang's runtime below instead."
    }
  } catch {
    // Surfaced by the composable.
  } finally {
    findingJava.value = false
  }
}

/** Downloads the runtime the version json names, into this instance. */
async function downloadJava(): Promise<void> {
  const component = status.value?.component
  if (!component) {
    javaNote.value =
      'This version does not name a Java runtime. Pick a version in the instance first, or set the ' +
      'path to java above.'
    return
  }
  downloading.value = true
  javaNote.value = null
  try {
    const result = await installJava(component)
    javaNote.value =
      `Installed ${component} (Java ${result.major ?? 'unknown'}): ${result.files} files, ` +
      `${(result.bytes / 1048576).toFixed(0)} MB. The launcher uses it for this instance automatically.`
    await refreshJava()
  } catch {
    // Surfaced by the composable.
  } finally {
    downloading.value = false
  }
}

async function browseJava(): Promise<void> {
  // A Java binary is not a mod: the picker has to offer everything.
  const picked = await pickFiles(true)
  if (picked.paths[0]) form.javaPath = picked.paths[0]
}

/** The folder picker, which is what choosing a path should be. */
async function chooseInstancesDir(): Promise<void> {
  const picked = await pickFolder()
  if (picked.path) form.instancesDir = picked.path
}

onMounted(() => {
  void refreshJava()
})
</script>

<template>
  <v-card max-width="880">
    <v-card-title class="d-flex align-center ga-2">
      <v-icon icon="mdi-cog-outline" size="small" />
      Settings
    </v-card-title>
    <v-card-text>
      <h3 class="text-h6 mb-2">Appearance</h3>
      <div class="d-flex flex-wrap ga-3 align-center">
        <v-btn-toggle
          :model-value="form.theme"
          mandatory
          divided
          variant="tonal"
          @update:model-value="chooseTheme"
        >
          <v-btn
            v-for="option in themeOptions"
            :key="option.value"
            :value="option.value"
            :prepend-icon="option.icon"
          >
            {{ option.title }}
          </v-btn>
        </v-btn-toggle>
      </div>
      <p class="text-medium-emphasis text-body-2 mt-2 mb-0">
        <strong>Follow the desktop</strong> changes with the system while the launcher is open, which
        is why it is the default. Surfaces are translucent and rounded, so what is behind the window
        still shows through it a little.
      </p>

      <v-divider class="my-6" />

      <h3 class="text-h6 mb-2">Instances</h3>
      <div class="d-flex ga-2 align-center">
        <v-text-field
          v-model="form.instancesDir"
          label="Instances folder"
          prepend-inner-icon="mdi-folder-outline"
          hint="Where new instances are created. An imported one keeps the path you picked for it."
          persistent-hint
          readonly
        />
        <v-btn variant="tonal" prepend-icon="mdi-folder-search-outline" @click="chooseInstancesDir">
          Choose
        </v-btn>
      </div>

      <v-divider class="my-6" />

      <h3 class="text-h6 mb-2">Java</h3>
      <v-alert type="info" variant="tonal" class="mb-4">
        <p class="mb-1">
          <strong>
            {{ selectedInstance ? selectedInstance.name : 'No instance selected' }}
          </strong>
          needs {{ requiredLabel }}<template v-if="status?.component"> ({{ status.component }})</template>.
        </p>
        <p class="mb-0">
          Java is installed <em>inside the game directory</em>, which is what the official launcher
          does too, so every instance can have the version it needs and nothing has to be installed
          system-wide.
        </p>
      </v-alert>

      <v-list v-if="(status?.installed ?? []).length > 0" density="comfortable" class="mb-2">
        <v-list-item
          v-for="runtime in status?.installed ?? []"
          :key="runtime.component"
          :title="`${runtime.component}, installed in this instance`"
          :subtitle="runtime.path"
        >
          <template #prepend>
            <div class="row-icon row-icon--success"><v-icon icon="mdi-coffee-outline" size="20" /></div>
          </template>
          <template #append>
            <v-chip size="small" color="success" variant="tonal" prepend-icon="mdi-check">in use</v-chip>
          </template>
        </v-list-item>
      </v-list>
      <v-alert v-else-if="selectedInstance" type="warning" variant="tonal" class="mb-2">
        No Java runtime inside this instance yet.
      </v-alert>

      <v-btn
        color="primary"
        class="mt-2"
        prepend-icon="mdi-download"
        :disabled="!status?.component || downloading || !selectedInstance"
        :loading="downloading"
        @click="downloadJava"
      >
        Download {{ status?.component ? `Mojang's ${requiredLabel}` : 'Java' }}
      </v-btn>
      <span v-if="hasTheRuntime" class="text-medium-emphasis ml-3">
        Already installed. Downloading again repairs it.
      </span>

      <v-divider class="my-6" />

      <p class="text-medium-emphasis">
        Which Java this instance runs. Automatic uses the runtime inside the instance, then one of the
        ones this machine has, and downloads Mojang's for the version if none of them is new enough.
      </p>
      <div class="d-flex ga-2 align-center mt-2">
        <v-select
          v-model="form.javaPath"
          :items="javaItems"
          item-title="title"
          item-value="value"
          label="Java runtime"
          prepend-inner-icon="mdi-coffee-outline"
          density="comfortable"
          hide-details
          class="flex-grow-1"
        />
        <v-btn variant="tonal" prepend-icon="mdi-folder-open-outline" @click="browseJava">Browse</v-btn>
        <v-btn
          variant="tonal"
          prepend-icon="mdi-magnify"
          :loading="findingJava"
          @click="findJava"
        >
          Find
        </v-btn>
      </div>

      <v-alert v-if="javaNote" type="info" variant="tonal" class="mt-4">{{ javaNote }}</v-alert>

      <v-divider class="my-6" />

      <h3 class="text-h6 mb-2">Defaults</h3>
      <p class="text-medium-emphasis mb-4">
        What a new instance starts with. An instance can be given its own memory on the Instances page,
        under <strong>Options</strong>, and its own Java under <strong>Java</strong> above.
      </p>
      <v-text-field
        v-model.number="form.maxMemoryMb"
        label="Maximum memory (MB)"
        type="number"
        hint="The -Xmx every instance gets unless it has been given its own."
        persistent-hint
      />
      <v-row class="mt-4">
        <v-col cols="6">
          <v-text-field v-model.number="form.width" label="Window width" type="number" />
        </v-col>
        <v-col cols="6">
          <v-text-field v-model.number="form.height" label="Window height" type="number" />
        </v-col>
      </v-row>
      <v-alert v-if="saved" type="success" variant="tonal" class="mt-4">
        Settings saved and versions rescanned.
      </v-alert>
    </v-card-text>
    <v-card-actions>
      <v-btn color="primary" prepend-icon="mdi-content-save-outline" @click="save">Save</v-btn>
    </v-card-actions>
  </v-card>
</template>

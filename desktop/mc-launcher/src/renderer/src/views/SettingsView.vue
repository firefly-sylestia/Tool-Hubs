<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { JavaStatus } from '@shared/types'
import { useLauncher, type DetectedJava } from '@renderer/composables/useLauncher'

const {
  settings,
  selectedInstance,
  saveSettings,
  rescan,
  detectJava,
  javaStatus,
  installJava,
  pickFiles
} = useLauncher()

const form = reactive({
  instancesDir: '',
  javaPath: '',
  maxMemoryMb: 4096,
  width: 1280,
  height: 720
})
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

watch(
  settings,
  (value) => {
    if (!value) return
    form.instancesDir = value.instancesDir
    form.javaPath = value.javaPath ?? ''
    form.maxMemoryMb = value.maxMemoryMb
    form.width = value.width
    form.height = value.height
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
  const picked = await pickFiles()
  if (picked.paths[0]) form.javaPath = picked.paths[0]
}

function useDetected(java: DetectedJava): void {
  form.javaPath = java.path
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
      <v-text-field
        v-model="form.instancesDir"
        label="Instances folder"
        hint="Where new instances are created. An imported one keeps the path you picked for it."
        persistent-hint
      />

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
        Or point the launcher at a Java you already have. Only needed if you want a specific one:
        what is found below is used automatically.
      </p>
      <v-text-field
        v-model="form.javaPath"
        label="Java binary (optional)"
        hint="Absolute path to java. Empty means the launcher finds one, or downloads Mojang's."
        persistent-hint
        class="mt-2"
      >
        <template #append>
          <v-btn size="small" variant="text" @click="browseJava">Browse</v-btn>
        </template>
      </v-text-field>

      <v-btn
        class="mt-4"
        :loading="findingJava"
        variant="tonal"
        prepend-icon="mdi-magnify"
        @click="findJava"
      >
        Find Java on this machine
      </v-btn>
      <v-list v-if="found.length > 0" density="comfortable" class="mt-2 app-stagger">
        <v-list-item
          v-for="java in found"
          :key="java.path"
          :title="`Java ${java.major ?? 'unknown'}`"
          :subtitle="`${java.path} · ${java.source}`"
        >
          <template #prepend>
            <div class="row-icon row-icon--primary"><v-icon icon="mdi-coffee" size="20" /></div>
          </template>
          <template #append>
            <v-btn
              size="small"
              variant="tonal"
              :prepend-icon="form.javaPath === java.path ? 'mdi-check' : 'mdi-arrow-right'"
              :disabled="form.javaPath === java.path"
              @click="useDetected(java)"
            >
              {{ form.javaPath === java.path ? 'Selected' : 'Use' }}
            </v-btn>
          </template>
        </v-list-item>
      </v-list>

      <v-alert v-if="javaNote" type="info" variant="tonal" class="mt-4">{{ javaNote }}</v-alert>

      <v-divider class="my-6" />

      <v-text-field v-model.number="form.maxMemoryMb" label="Maximum memory (MB)" type="number" />
      <v-row class="mt-2">
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

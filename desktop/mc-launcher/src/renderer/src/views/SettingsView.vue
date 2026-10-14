<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useLauncher, type DetectedJava } from '@renderer/composables/useLauncher'

const { settings, saveSettings, rescan, detectJava, pickFiles } = useLauncher()

const form = reactive({
  instancesDir: '',
  javaPath: '',
  maxMemoryMb: 4096,
  width: 1280,
  height: 720
})
const saved = ref(false)
const found = ref<DetectedJava[]>([])
const detecting = ref(false)

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
  } catch {
    // Surfaced by the composable.
  }
}

async function findJava(): Promise<void> {
  detecting.value = true
  try {
    found.value = await detectJava()
  } catch {
    // Surfaced by the composable.
  } finally {
    detecting.value = false
  }
}

async function browseJava(): Promise<void> {
  const picked = await pickFiles()
  if (picked.paths[0]) form.javaPath = picked.paths[0]
}

function useDetected(java: DetectedJava): void {
  form.javaPath = java.path
}
</script>

<template>
  <v-card max-width="820">
    <v-card-title>Settings</v-card-title>
    <v-card-text>
      <v-text-field
        v-model="form.instancesDir"
        label="Instances folder"
        hint="Where new instances are created. An imported one keeps the path you picked for it."
        persistent-hint
      />
      <v-text-field
        v-model="form.javaPath"
        label="Java binary"
        hint="Absolute path to java. Leave it empty and the launcher finds a runtime itself: the one Mojang installed inside the game directory, one from JAVA_HOME, or an installed JDK."
        persistent-hint
        class="mt-4"
      >
        <template #append>
          <v-btn size="small" variant="text" @click="browseJava">Browse</v-btn>
        </template>
      </v-text-field>

      <v-btn class="mt-4" :loading="detecting" variant="tonal" @click="findJava">Find installed Java</v-btn>
      <v-list v-if="found.length > 0" density="compact" class="mt-2">
        <v-list-item
          v-for="java in found"
          :key="java.path"
          :title="`Java ${java.major ?? 'unknown'}`"
          :subtitle="`${java.path} — ${java.source}`"
        >
          <template #append>
            <v-btn size="small" :disabled="form.javaPath === java.path" @click="useDetected(java)">
              Use
            </v-btn>
          </template>
        </v-list-item>
      </v-list>

      <v-text-field v-model.number="form.maxMemoryMb" label="Maximum memory (MB)" type="number" class="mt-4" />
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
      <v-btn color="primary" @click="save">Save</v-btn>
    </v-card-actions>
  </v-card>
</template>

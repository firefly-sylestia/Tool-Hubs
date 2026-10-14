<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useLauncher } from '@renderer/composables/useLauncher'

const { settings, saveSettings, rescan } = useLauncher()

const form = reactive({
  gameDir: '',
  javaPath: '',
  maxMemoryMb: 4096,
  width: 1280,
  height: 720
})
const saved = ref(false)

watch(
  settings,
  (value) => {
    if (!value) return
    form.gameDir = value.gameDir
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
      gameDir: form.gameDir,
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
</script>

<template>
  <v-card max-width="760">
    <v-card-title>Settings</v-card-title>
    <v-card-text>
      <v-text-field
        v-model="form.gameDir"
        label="Game directory"
        hint="Absolute path to an existing Minecraft installation (the folder holding versions/, libraries/ and assets/)."
        persistent-hint
      />
      <v-text-field
        v-model="form.javaPath"
        label="Java binary"
        hint="Absolute path to java, or empty to use java from PATH."
        persistent-hint
        class="mt-4"
      />
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

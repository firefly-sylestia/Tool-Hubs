<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { TaskRecord } from '@shared/types'
import { useLauncher } from '@renderer/composables/useLauncher'

/**
 * Downloads: everything the launcher is doing, and everything it did.
 *
 * The store's Install button and the instance page's Installers can only say
 * "working"; a download that takes two minutes needs to say what is being
 * fetched, how much of it has arrived and how long it has been going, and a
 * failed one needs somewhere to be read that does not disappear with the next
 * snackbar. That is this page: the same task records the title bar counts, in
 * full.
 */

const { tasks, clearTasks } = useLauncher()

const clearing = ref(false)
const note = ref<string | null>(null)

/**
 * A ticking clock, so a running task says how long for.
 *
 * Ticks only while something is running, so a launcher sitting on this page
 * with nothing to show is not repainting once a second for nothing.
 */
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

const running = computed(() => tasks.value.filter((task) => task.state === 'running'))
const finished = computed(() => tasks.value.filter((task) => task.state !== 'running'))

const LOOK: Record<TaskRecord['state'], { icon: string; color: string; label: string }> = {
  running: { icon: 'mdi-progress-download', color: 'primary', label: 'Running' },
  succeeded: { icon: 'mdi-check-circle', color: 'success', label: 'Done' },
  failed: { icon: 'mdi-alert-circle', color: 'error', label: 'Failed' }
}

function look(task: TaskRecord): { icon: string; color: string; label: string } {
  return LOOK[task.state]
}

function seconds(startedAt: string, finishedAt: string | null): number {
  const end = finishedAt ? new Date(finishedAt).getTime() : now.value
  return Math.max(0, Math.round((end - new Date(startedAt).getTime()) / 1000))
}

/** `3m 20s`, so a long download reads as one rather than as 200 seconds. */
function duration(startedAt: string, finishedAt: string | null): string {
  const total = seconds(startedAt, finishedAt)
  if (total < 60) return `${total}s`
  return `${Math.floor(total / 60)}m ${String(total % 60).padStart(2, '0')}s`
}

function percent(task: TaskRecord): number {
  return Math.round(task.progress * 100)
}

function when(at: string): string {
  return new Date(at).toLocaleTimeString()
}

async function clearFinished(): Promise<void> {
  clearing.value = true
  note.value = null
  try {
    const result = await clearTasks()
    note.value =
      result.cleared === 0
        ? 'Nothing has finished yet, so nothing was cleared.'
        : `Cleared ${result.cleared} finished task(s).`
  } catch {
    // Reported in the snackbar.
  } finally {
    clearing.value = false
  }
}

function startTicker(): void {
  if (ticker !== null) return
  ticker = setInterval(() => (now.value = Date.now()), 1000)
}

function stopTicker(): void {
  if (ticker === null) return
  clearInterval(ticker)
  ticker = null
}

onMounted(startTicker)
onBeforeUnmount(stopTicker)
</script>

<template>
  <v-card class="hero-card mb-4">
    <v-card-text>
      <div class="d-flex align-center flex-wrap ga-3">
        <div class="hero-icon"><v-icon icon="mdi-download-box-outline" /></div>
        <div class="flex-grow-1">
          <h1 class="text-h5 mb-0">Downloads</h1>
          <p class="text-body-2 text-medium-emphasis mb-0">
            Every install, download and launch the launcher has started, newest first.
          </p>
        </div>
        <v-chip
          v-if="running.length > 0"
          class="state-chip"
          color="primary"
          variant="tonal"
          prepend-icon="mdi-progress-download"
        >
          {{ running.length }} running
        </v-chip>
        <v-btn
          variant="tonal"
          size="small"
          prepend-icon="mdi-broom"
          :loading="clearing"
          :disabled="finished.length === 0"
          @click="clearFinished"
        >
          Clear finished
        </v-btn>
      </div>

      <v-alert v-if="note" type="info" variant="tonal" class="mt-4" density="comfortable">
        {{ note }}
      </v-alert>
    </v-card-text>
  </v-card>

  <v-card v-if="running.length > 0" class="mb-4">
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-progress-download" class="mr-2" size="small" />
      Running now
    </v-card-title>
    <v-card-text>
      <div v-for="task in running" :key="task.id" class="mb-4">
        <div class="d-flex align-center flex-wrap ga-2">
          <span class="status-dot status-dot--live" />
          <strong>{{ task.name }}</strong>
          <v-spacer />
          <span class="text-medium-emphasis text-body-2">
            {{ percent(task) }}% · {{ duration(task.startedAt, null) }} · started
            {{ when(task.startedAt) }}
          </span>
        </div>
        <p class="text-body-2 mb-2 mt-1">{{ task.message }}</p>
        <v-progress-linear :model-value="percent(task)" height="6" rounded color="primary" />
      </div>
    </v-card-text>
  </v-card>

  <v-card>
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-history" class="mr-2" size="small" />
      Finished
    </v-card-title>
    <v-card-text>
      <v-list v-if="finished.length > 0" density="comfortable" class="app-stagger">
        <v-list-item v-for="task in finished" :key="task.id">
          <template #prepend>
            <div class="row-icon" :class="`row-icon--${look(task).color}`">
              <v-icon :icon="look(task).icon" size="20" />
            </div>
          </template>
          <v-list-item-title>{{ task.name }}</v-list-item-title>
          <v-list-item-subtitle>
            {{ look(task).label }}: {{ task.message }} · took
            {{ duration(task.startedAt, task.finishedAt) }} · {{ when(task.startedAt) }}
          </v-list-item-subtitle>
        </v-list-item>
      </v-list>

      <div v-else class="text-center py-8">
        <v-icon icon="mdi-download-off-outline" size="38" class="text-medium-emphasis" />
        <p class="text-medium-emphasis mt-3 mb-0">
          Nothing yet. Installing a game version, a mod, a shader or a modpack from the store shows up
          here while it runs, and stays after it finishes — including the reason when one fails.
        </p>
      </div>
    </v-card-text>
  </v-card>
</template>

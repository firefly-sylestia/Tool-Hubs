<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { LaunchPlan, TaskRecord } from '@shared/types'
import { useLauncher } from '@renderer/composables/useLauncher'

const router = useRouter()
const {
  profiles,
  instances,
  selectedProfile,
  selectedInstance,
  pendingServer,
  runningProfileId,
  tasks,
  createProfile,
  selectProfile,
  selectInstance,
  openPath,
  startLaunch,
  stopLaunch,
  planLaunch
} = useLauncher()

const newName = ref('')
const busy = ref(false)
const plan = ref<LaunchPlan | null>(null)
const showPlan = ref(false)
const copied = ref(false)

const spinning = ref(false)
const menuOpen = ref(false)

const isRunning = computed(() => runningProfileId.value !== null)
const canLaunch = computed(
  () => selectedProfile.value !== null && selectedInstance.value?.versionId != null && !isRunning.value
)

/** A ticking clock, so a task that is running says how long for. */
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

const activeTask = computed(() => tasks.value.find((task) => task.state === 'running') ?? null)

const TASK_LOOK: Record<TaskRecord['state'], { icon: string; color: string; label: string }> = {
  running: { icon: 'mdi-progress-download', color: 'primary', label: 'Working' },
  succeeded: { icon: 'mdi-check-circle', color: 'success', label: 'Done' },
  failed: { icon: 'mdi-alert-circle', color: 'error', label: 'Failed' }
}

function taskLook(task: TaskRecord): { icon: string; color: string; label: string } {
  return TASK_LOOK[task.state]
}

function taskTail(task: TaskRecord): string {
  if (task.state === 'running') {
    const seconds = Math.max(0, Math.round((now.value - new Date(task.startedAt).getTime()) / 1000))
    return `${Math.round(task.progress * 100)}% · ${seconds}s`
  }
  if (!task.finishedAt) return task.message
  const seconds = Math.round(
    (new Date(task.finishedAt).getTime() - new Date(task.startedAt).getTime()) / 1000
  )
  return `${task.message} in ${seconds}s`
}

/** One word for where this instance stands, so the card leads with a state. */
const status = computed(() => {
  if (isRunning.value) return { label: 'Playing', icon: 'mdi-play-circle', color: 'success' }
  if (!selectedInstance.value) return { label: 'No instance', icon: 'mdi-plus-circle-outline', color: 'info' }
  if (!selectedInstance.value.versionId) {
    return { label: 'Needs a version', icon: 'mdi-alert-circle-outline', color: 'warning' }
  }
  if (!selectedProfile.value) return { label: 'No profile', icon: 'mdi-account-plus-outline', color: 'info' }
  return { label: 'Ready', icon: 'mdi-check-circle-outline', color: 'success' }
})

/** What the instance is: its version, its loader and where it lives. */
const instanceLine = computed(() => {
  const instance = selectedInstance.value
  if (!instance) return 'Create an instance to give the game a folder of its own.'
  const version = instance.versionId ?? 'no version installed yet'
  const loader = instance.loader && instance.loader !== 'vanilla' ? ` · ${instance.loader}` : ''
  return `${version}${loader} · ${instance.dir}`
})

const launchLabel = computed(() => {
  if (isRunning.value) return 'Playing'
  if (!selectedInstance.value) return 'No instance'
  if (!selectedInstance.value.versionId) return 'Install a version'
  if (!selectedProfile.value) return 'No profile'
  return 'Launch'
})

function selectProfileById(id: string | null): void {
  void selectProfile(id)
}

function selectInstanceById(id: string): void {
  void selectInstance(id)
}

/** A second line under each instance, so the picker says what would be launched. */
function instanceProps(instance: { versionId: string | null; loader: string | null }): { subtitle: string } {
  const version = instance.versionId ?? 'no version yet'
  const loader = instance.loader && instance.loader !== 'vanilla' ? ` · ${instance.loader}` : ''
  return { subtitle: `${version}${loader}` }
}

async function create(): Promise<void> {
  if (newName.value.trim() === '') return
  busy.value = true
  try {
    await createProfile(newName.value.trim())
    newName.value = ''
  } catch {
    // The composable surfaces the message in the snackbar.
  } finally {
    busy.value = false
  }
}

async function copyUuid(): Promise<void> {
  const uuid = selectedProfile.value?.uuid
  if (!uuid) return
  // A packaged app is loaded from file://, where the clipboard API is not
  // always exposed; the UUID is on screen either way.
  try {
    await navigator.clipboard?.writeText(uuid)
  } catch {
    return
  }
  copied.value = true
  window.setTimeout(() => (copied.value = false), 1500)
}

async function launch(): Promise<void> {
  const profile = selectedProfile.value
  const instance = selectedInstance.value
  if (!profile || !instance || !canLaunch.value) return
  spinning.value = true
  busy.value = true
  if (ticker === null) ticker = setInterval(() => (now.value = Date.now()), 1000)
  try {
    await startLaunch(profile.id, pendingServer.value?.id ?? null, instance.id)
  } catch {
    // Reported in the snackbar; the log holds the details.
  } finally {
    busy.value = false
  }
}

async function stop(): Promise<void> {
  try {
    await stopLaunch()
  } catch {
    // Reported in the snackbar.
  }
}

/** A whole folder for the instance, which is what people go looking for. */
async function openGameFolder(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  await openPath(instance.dir)
}

async function previewPlan(): Promise<void> {
  const profile = selectedProfile.value
  const instance = selectedInstance.value
  if (!profile || !instance) return
  try {
    plan.value = await planLaunch(profile.id, pendingServer.value?.id ?? null, instance.id)
    showPlan.value = true
  } catch {
    // Reported in the snackbar.
  }
}

onBeforeUnmount(() => {
  if (ticker !== null) clearInterval(ticker)
  ticker = null
})
</script>

<template>
  <v-row>
    <v-col cols="12" lg="7">
      <v-card class="hero-card">
        <v-card-text class="d-flex align-start ga-4">
          <div class="hero-icon">
            <v-icon icon="mdi-cube-outline" />
          </div>
          <div class="flex-grow-1">
            <div class="d-flex align-center flex-wrap ga-2">
              <h1 class="text-h5 mb-0">{{ selectedInstance?.name ?? 'No instance yet' }}</h1>
              <v-chip
                class="state-chip"
                :color="status.color"
                variant="tonal"
                :prepend-icon="status.icon"
                size="small"
              >
                {{ status.label }}
              </v-chip>
            </div>
            <p class="text-body-2 text-medium-emphasis mb-0 text-truncate">{{ instanceLine }}</p>
          </div>
        </v-card-text>

        <v-card-text class="pt-0">
          <v-row density="compact">
            <v-col cols="12" sm="7">
              <v-select
                :items="instances"
                item-title="name"
                item-value="id"
                label="Instance"
                prepend-inner-icon="mdi-cube-outline"
                :model-value="selectedInstance?.id ?? null"
                :disabled="instances.length === 0"
                :item-props="instanceProps"
                hide-details
                @update:model-value="selectInstanceById"
              />
            </v-col>
            <v-col cols="12" sm="5">
              <v-select
                :items="profiles"
                item-title="name"
                item-value="id"
                label="Profile"
                prepend-inner-icon="mdi-account-outline"
                :model-value="selectedProfile?.id ?? null"
                hide-details
                @update:model-value="selectProfileById"
              />
            </v-col>
          </v-row>

          <v-alert v-if="instances.length === 0" type="info" variant="tonal" class="mt-4">
            No instance yet. Create one or import a folder you already have on
            <strong>Instances</strong>: an instance is one game directory with its own mods.
          </v-alert>
          <v-alert
            v-else-if="selectedInstance && !selectedInstance.versionId"
            type="warning"
            variant="tonal"
            class="mt-4"
          >
            <strong>{{ selectedInstance.name }}</strong> has no version installed yet. Pick one on
            <strong>Instances</strong>.
          </v-alert>

          <div v-if="selectedProfile" class="identity-line mt-4">
            <v-icon icon="mdi-account-key-outline" size="small" />
            <code>{{ selectedProfile.uuid }}</code>
            <span class="text-medium-emphasis">offline UUID, derived from the name</span>
            <v-btn
              :icon="copied ? 'mdi-check' : 'mdi-content-copy'"
              :color="copied ? 'success' : undefined"
              size="x-small"
              variant="text"
              aria-label="Copy the UUID"
              @click="copyUuid"
            />
          </div>

          <div class="d-flex ga-2 align-center mt-4">
            <v-text-field
              v-model="newName"
              label="New profile name"
              prepend-inner-icon="mdi-account-plus-outline"
              density="compact"
              hide-details
              @keyup.enter="create"
            />
            <v-btn :disabled="busy || newName.trim() === ''" :loading="busy" @click="create">
              Create profile
            </v-btn>
          </div>

          <v-alert
            v-if="pendingServer"
            type="warning"
            variant="tonal"
            prepend-icon="mdi-server-network"
            class="mt-4"
          >
            Next launch joins {{ pendingServer.name }} ({{ pendingServer.host }}:{{ pendingServer.port }}).
            Only do that if the server accepts unauthenticated clients.
          </v-alert>

          <v-alert v-if="activeTask" type="info" variant="tonal" prepend-icon="mdi-progress-download" class="mt-4">
            {{ activeTask.name }}: {{ activeTask.message }}
            <template v-if="activeTask.state === 'running'">({{ Math.round(activeTask.progress * 100) }}%)</template>
          </v-alert>
        </v-card-text>

        <v-card-actions class="px-4 pb-4 pt-0">
          <!-- A pill with the play control set into it. The icon spins once on
               click, which is the only sign a launch started before a window
               appears. While the game runs the pill turns into how to stop it. -->
          <template v-if="!isRunning">
            <div class="launch-pill mr-2">
              <v-btn
                color="primary"
                variant="flat"
                class="btn-launch"
                :disabled="busy || !canLaunch"
                @click="launch"
              >
                {{ launchLabel }}
              </v-btn>
              <v-btn
                icon
                variant="flat"
                class="launch-inset launch-inset--play"
                :disabled="busy || !canLaunch"
                aria-label="Launch"
                @click="launch"
              >
                <v-icon :class="{ 'is-spinning': spinning }" @animationend="spinning = false">
                  mdi-play
                </v-icon>
              </v-btn>
              <v-menu
                location="bottom end"
                :open-on-hover="true"
                transition="scroll-y-transition"
                @update:model-value="(open: boolean) => (menuOpen = open)"
              >
                <template #activator="{ props: activatorProps }">
                  <v-btn
                    icon
                    variant="flat"
                    class="launch-inset launch-inset--menu"
                    aria-label="Instance actions"
                    v-bind="activatorProps"
                  >
                    <v-icon :class="{ 'is-active': menuOpen }">mdi-dots-horizontal</v-icon>
                  </v-btn>
                </template>
                <v-list density="compact">
                  <v-list-item
                    title="Show the command"
                    prepend-icon="mdi-console-line"
                    @click="previewPlan()"
                  />
                  <v-list-item
                    title="Manage instances"
                    prepend-icon="mdi-cube-outline"
                    @click="router.push('/instances')"
                  />
                  <v-list-item
                    title="Open the game folder"
                    prepend-icon="mdi-folder-outline"
                    @click="openGameFolder()"
                  />
                </v-list>
              </v-menu>
            </div>
          </template>
          <template v-else>
            <div class="running-panel">
              <span class="status-dot status-dot--live" />
              <span class="running-panel__text">
                <strong>{{ selectedProfile?.name ?? 'The game' }}</strong> is running
              </span>
            </div>
            <v-btn color="error" variant="tonal" prepend-icon="mdi-stop" class="ml-3" @click="stop">
              Stop
            </v-btn>
          </template>

          <v-spacer />
          <v-btn variant="text" prepend-icon="mdi-cube-outline" @click="router.push('/instances')">
            Manage instances
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-col>

    <v-col cols="12" lg="5">
      <v-card class="h-100">
        <v-card-title class="d-flex align-center">
          <v-icon icon="mdi-history" class="mr-2" size="small" />
          Activity
        </v-card-title>
        <v-card-text>
          <v-list v-if="tasks.length > 0" density="comfortable" class="app-stagger">
            <v-list-item v-for="task in tasks.slice(0, 8)" :key="task.id">
              <template #prepend>
                <div class="row-icon" :class="`row-icon--${taskLook(task).color}`">
                  <v-icon :icon="taskLook(task).icon" size="20" />
                </div>
              </template>
              <v-list-item-title>{{ task.name }}</v-list-item-title>
              <v-list-item-subtitle>
                {{ taskLook(task).label }}: {{ task.message }} · {{ taskTail(task) }}
              </v-list-item-subtitle>
              <v-progress-linear
                v-if="task.state === 'running'"
                :model-value="Math.round(task.progress * 100)"
                height="4"
                rounded
                class="mt-2"
              />
            </v-list-item>
          </v-list>
          <div v-else class="text-center py-6">
            <v-icon icon="mdi-history" size="34" class="text-medium-emphasis" />
            <p class="text-medium-emphasis mb-0 mt-2">
              Nothing has run yet. Installing a version, a mod or a pack shows up here.
            </p>
          </div>
        </v-card-text>
      </v-card>
    </v-col>
  </v-row>

  <v-dialog v-model="showPlan" max-width="820">
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon icon="mdi-console-line" class="mr-2" size="small" />
        Command
      </v-card-title>
      <v-card-text>
        <p v-if="plan" class="text-medium-emphasis d-flex align-center ga-2 mb-1">
          <v-icon icon="mdi-coffee-outline" size="small" />{{ plan.javaPath }}
        </p>
        <p v-if="plan" class="text-medium-emphasis d-flex align-center ga-2">
          <v-icon icon="mdi-folder-outline" size="small" />{{ plan.cwd }}
        </p>
        <pre class="plan">{{ plan?.args.join('\n') }}</pre>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="showPlan = false">Close</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.identity-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 0.82rem;
  opacity: 0.9;
}

.identity-line code {
  font-size: 0.78rem;
}

/* The running state, in place of the launch pill. */
.running-panel {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  height: 64px;
  border-radius: 9999px;
  color: rgb(var(--v-theme-success));
  background: rgba(var(--v-theme-success), 0.12);
}

.running-panel__text {
  font-size: 1rem;
}

.plan {
  max-height: 420px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  padding: 10px;
  border-radius: 8px;
  background: rgba(var(--v-theme-on-surface), 0.06);
}
</style>

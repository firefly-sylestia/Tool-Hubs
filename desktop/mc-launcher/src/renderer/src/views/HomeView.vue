<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { JavaStatus, LaunchPlan, TaskRecord } from '@shared/types'
import { useLauncher } from '@renderer/composables/useLauncher'

/**
 * Play: one instance, what launching it will do, and one button.
 *
 * The profile used to be chosen here too, which put two dropdowns and a name
 * field between someone and the thing this page exists for. A profile is an
 * account, so it lives in the drawer's account slot now; this page is about the
 * instance, and it says what pressing Launch will actually do.
 */
const router = useRouter()
const {
  instances,
  settings,
  selectedProfile,
  selectedInstance,
  pendingServer,
  runningProfileId,
  lastLaunch,
  tasks,
  selectInstance,
  openPath,
  dismissLaunchOutcome,
  resources,
  javaStatus,
  launchSelected,
  stopLaunch,
  planLaunch
} = useLauncher()

const busy = ref(false)
const plan = ref<LaunchPlan | null>(null)
const showPlan = ref(false)

/** What the instance holds and what it needs, so the page says more than a name. */
const counts = ref({ mods: 0, resourcepacks: 0, shaderpacks: 0, modsOff: 0 })
const javaInfo = ref<JavaStatus | null>(null)
const detailsBusy = ref(false)

const spinning = ref(false)
const menuOpen = ref(false)

const isRunning = computed(() => runningProfileId.value !== null)

/** How the last launch ended, and its own last words, for the panel above. */
const launchOutcome = lastLaunch
const outcomeTail = computed(() => (launchOutcome.value?.tail ?? []).slice(-10).join('\n'))

async function dismissOutcome(): Promise<void> {
  try {
    await dismissLaunchOutcome()
  } catch {
    // Reported in the snackbar.
  }
}
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
  const played = instance.lastPlayedAt ? ` · ${playedLine(instance.lastPlayedAt)}` : ''
  return `${version}${loader}${played} · ${instance.dir}`
})

const launchLabel = computed(() => {
  if (isRunning.value) return 'Playing'
  if (!selectedInstance.value) return 'No instance'
  if (!selectedInstance.value.versionId) return 'Install a version'
  if (!selectedProfile.value) return 'No profile'
  return 'Launch'
})

/** When a profile was last used, which is what tells two of them apart. */
function playedLine(value: string | null): string {
  return value ? `last played ${value.slice(0, 10)}` : 'never played'
}

/**
 * What launching this instance will do, read from the instance itself.
 *
 * The counts are of files the game will load, so a mod switched off is not
 * counted as one of the mods.
 */
async function loadDetails(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) {
    counts.value = { mods: 0, resourcepacks: 0, shaderpacks: 0, modsOff: 0 }
    javaInfo.value = null
    return
  }
  detailsBusy.value = true
  try {
    const found = await resources(instance.id)
    const of = (kind: 'mod' | 'resourcepack' | 'shaderpack'): number =>
      found.entries[kind].filter((entry) => entry.enabled).length
    counts.value = {
      mods: of('mod'),
      resourcepacks: of('resourcepack'),
      shaderpacks: of('shaderpack'),
      modsOff: found.entries.mod.filter((entry) => !entry.enabled).length
    }
    try {
      javaInfo.value = await javaStatus(instance.id)
    } catch {
      javaInfo.value = null
    }
  } catch {
    // Reported in the snackbar; the card simply says less.
  } finally {
    detailsBusy.value = false
  }
}

/** The memory this launch will ask for, from the instance or from the launcher. */
const memoryLine = computed(() => {
  const own = selectedInstance.value?.maxMemoryMb ?? null
  if (own !== null) return `${own} MB, set for this instance`
  return `${settings.value?.maxMemoryMb ?? 4096} MB, the launcher's default`
})

const javaLine = computed(() => {
  const instance = selectedInstance.value
  if (!instance) return 'No instance'
  if (instance.javaPath) return `${instance.javaPath}, set for this instance`
  const required = javaInfo.value?.requiredMajor ?? null
  const installed = javaInfo.value?.installed ?? []
  if (installed.length > 0) {
    const wanted = required !== null ? `, which wants Java ${required}` : ''
    return `${installed[0].component} downloaded by the launcher${wanted}`
  }
  return required !== null
    ? `Automatic: this version asks for Java ${required}`
    : 'Automatic, and this version does not name one'
})

function selectInstanceById(id: string): void {
  void selectInstance(id)
}

watch(() => selectedInstance.value?.id ?? null, () => void loadDetails(), { immediate: true })

onMounted(() => void loadDetails())

/** A second line under each instance, so the picker says what would be launched. */
function instanceProps(instance: { versionId: string | null; loader: string | null }): { subtitle: string } {
  const version = instance.versionId ?? 'no version yet'
  const loader = instance.loader && instance.loader !== 'vanilla' ? ` · ${instance.loader}` : ''
  return { subtitle: `${version}${loader}` }
}

async function launch(): Promise<void> {
  if (!canLaunch.value) return
  spinning.value = true
  busy.value = true
  if (ticker === null) ticker = setInterval(() => (now.value = Date.now()), 1000)
  try {
    await launchSelected()
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

          <!-- The profile is not this page's business any more, so it says where
               to go for one instead of carrying a picker of its own. -->
          <v-alert
            v-if="!selectedProfile"
            type="info"
            variant="tonal"
            class="mt-4"
            prepend-icon="mdi-account-outline"
          >
            No profile yet. A profile is the name the game reports and nothing else — create one in the
            account section at the foot of the sidebar, then press Launch.
          </v-alert>

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

          <!-- What the last launch actually did. A game that fails on the way up
               exits seconds after the launcher said it was running, and its own
               words go to its log rather than to this window; without this the
               only sign was a status that quietly flipped back to Launch. -->
          <v-alert
            v-if="launchOutcome?.failed"
            type="error"
            variant="tonal"
            class="mt-4"
            :title="`${launchOutcome.instanceName} stopped after ${launchOutcome.seconds}s`"
          >
            <p class="mb-2">
              {{ launchOutcome.exitCode !== null ? `Exit code ${launchOutcome.exitCode}` : `Stopped by ${launchOutcome.signal ?? 'a signal'}` }}
              on {{ launchOutcome.versionId }} as {{ launchOutcome.profileName }}: the game never got
              as far as a window.
            </p>
            <div v-if="launchOutcome.findings.length > 0" class="mb-2">
              <p v-for="finding in launchOutcome.findings.slice(0, 3)" :key="finding.id" class="mb-1">
                <strong>{{ finding.title }}</strong>
                <span v-if="finding.hints[0]"> — {{ finding.hints[0] }}</span>
              </p>
            </div>
            <pre v-if="outcomeTail" class="failure-tail">{{ outcomeTail }}</pre>
            <div class="d-flex flex-wrap ga-2 mt-3">
              <v-btn size="small" variant="flat" color="primary" @click="router.push('/logs')">
                Read the log
              </v-btn>
              <v-btn
                v-if="launchOutcome.logPath"
                size="small"
                variant="text"
                prepend-icon="mdi-text-box-outline"
                @click="openPath(launchOutcome.logPath)"
              >
                Open the game's log
              </v-btn>
              <v-btn size="small" variant="text" @click="dismissOutcome">Dismiss</v-btn>
            </div>
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
          <v-spacer />
          <!-- The card shows the last few; the rest, with what each download
               fetched and how long it took, is a page of its own. -->
          <v-btn
            v-if="tasks.length > 0"
            size="small"
            variant="text"
            prepend-icon="mdi-download-box-outline"
            @click="router.push('/downloads')"
          >
            Downloads
          </v-btn>
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
              <!-- The message says which file and how many bytes while it runs. -->
              <span v-if="task.state === 'running'" class="text-medium-emphasis text-caption">
                {{ Math.round(task.progress * 100) }}%
              </span>
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

/* The game's own output when a launch fails: monospaced and scrollable, because
   the reason is usually the last few lines of a stack trace. */
.failure-tail {
  max-height: 180px;
  overflow: auto;
  padding: 8px 10px;
  margin: 0;
  border-radius: 8px;
  font-size: 0.72rem;
  line-height: 1.45;
  white-space: pre-wrap;
  background: rgba(var(--v-theme-surface-variant), 0.4);
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

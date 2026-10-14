<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { LaunchPlan } from '@shared/types'
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
  startLaunch,
  stopLaunch,
  planLaunch
} = useLauncher()

const newName = ref('')
const busy = ref(false)
const plan = ref<LaunchPlan | null>(null)
const showPlan = ref(false)

const isRunning = computed(() => runningProfileId.value !== null)
const canLaunch = computed(
  () => selectedProfile.value !== null && selectedInstance.value?.versionId != null && !isRunning.value
)

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

async function launch(): Promise<void> {
  const profile = selectedProfile.value
  const instance = selectedInstance.value
  if (!profile || !instance) return
  busy.value = true
  try {
    await startLaunch(profile.id, pendingServer.value?.id ?? null, instance.id)
  } catch {
    // Reported in the snackbar; the log holds the details.
  } finally {
    busy.value = false
  }
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
</script>

<template>
  <v-row>
    <v-col cols="12" md="7">
      <v-card>
        <v-card-title>Play</v-card-title>
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

          <v-alert v-if="instances.length === 0" type="info" variant="tonal" class="mb-4">
            No instance yet. Create one or import a folder you already have on
            <strong>Instances</strong> — an instance is one game directory with its own mods.
          </v-alert>
          <v-alert
            v-else-if="selectedInstance && !selectedInstance.versionId"
            type="warning"
            variant="tonal"
            class="mb-4"
          >
            <strong>{{ selectedInstance.name }}</strong> has no version installed yet. Pick one on
            <strong>Instances</strong>.
          </v-alert>

          <v-select
            :items="profiles"
            item-title="name"
            item-value="id"
            label="Profile"
            :model-value="selectedProfile?.id ?? null"
            @update:model-value="selectProfileById"
          />
          <v-text-field
            v-if="selectedProfile"
            :model-value="selectedProfile.uuid"
            label="Offline UUID"
            readonly
            hint="Derived from the profile name; this launcher never contacts an account service."
            persistent-hint
          />
          <v-text-field
            v-model="newName"
            label="New profile name"
            hint="3-16 characters: letters, numbers and underscores."
            persistent-hint
            class="mt-4"
          />
          <v-btn class="mt-4" :disabled="busy || newName.trim() === ''" @click="create">Create profile</v-btn>

          <v-alert v-if="pendingServer" type="warning" variant="tonal" class="mt-4">
            Next launch joins {{ pendingServer.name }} ({{ pendingServer.host }}:{{ pendingServer.port }}).
            Enable join only if that server is configured for unauthenticated clients.
          </v-alert>
        </v-card-text>
        <v-card-actions>
          <v-btn color="primary" :disabled="busy || !canLaunch" @click="launch">Launch</v-btn>
          <v-btn :disabled="!isRunning" @click="stopLaunch()">Stop</v-btn>
          <v-btn variant="text" :disabled="!canLaunch" @click="previewPlan">Show command</v-btn>
          <v-spacer />
          <v-btn variant="text" @click="router.push('/instances')">Manage instances</v-btn>
        </v-card-actions>
      </v-card>
    </v-col>

    <v-col cols="12" md="5">
      <v-card>
        <v-card-title>Tasks</v-card-title>
        <v-card-text>
          <v-list v-if="tasks.length > 0" density="compact">
            <v-list-item v-for="task in tasks.slice(0, 10)" :key="task.id" :title="task.name">
              <v-progress-linear
                v-if="task.state === 'running'"
                :model-value="Math.round(task.progress * 100)"
                height="6"
                rounded
                class="my-2"
              />
              <p class="text-medium-emphasis mb-0">
                {{ task.state }} — {{ task.message }}
                <template v-if="task.state === 'running'">
                  ({{ Math.round(task.progress * 100) }}%)
                </template>
              </p>
            </v-list-item>
          </v-list>
          <p v-else class="text-medium-emphasis">Nothing has run yet.</p>
        </v-card-text>
      </v-card>
    </v-col>
  </v-row>

  <v-dialog v-model="showPlan" max-width="820">
    <v-card>
      <v-card-title>Command</v-card-title>
      <v-card-text>
        <p v-if="plan" class="text-medium-emphasis">java: {{ plan.javaPath }}</p>
        <p v-if="plan" class="text-medium-emphasis">cwd: {{ plan.cwd }}</p>
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
.plan {
  max-height: 420px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
}
</style>

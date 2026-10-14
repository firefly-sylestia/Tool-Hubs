<script setup lang="ts">
import { computed, ref } from 'vue'
import type { LaunchPlan } from '@shared/types'
import { useLauncher } from '@renderer/composables/useLauncher'

const {
  profiles,
  installations,
  selectedProfile,
  pendingServer,
  runningProfileId,
  tasks,
  createProfile,
  selectProfile,
  updateProfile,
  startLaunch,
  stopLaunch,
  planLaunch,
  rescan
} = useLauncher()

const newName = ref('')
const busy = ref(false)
const plan = ref<LaunchPlan | null>(null)
const showPlan = ref(false)

const selected = computed(() => selectedProfile.value)
const isRunning = computed(() => runningProfileId.value !== null)

function selectProfileById(id: string | null): void {
  void selectProfile(id)
}

function selectVersion(versionId: string | null): void {
  const profile = selected.value
  if (!profile) return
  void updateProfile(profile.id, { versionId })
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
  const profile = selected.value
  if (!profile) return
  busy.value = true
  try {
    await startLaunch(profile.id, pendingServer.value?.id ?? null)
  } catch {
    // Reported in the snackbar; the log holds the details.
  } finally {
    busy.value = false
  }
}

async function previewPlan(): Promise<void> {
  const profile = selected.value
  if (!profile) return
  try {
    plan.value = await planLaunch(profile.id, pendingServer.value?.id ?? null)
    showPlan.value = true
  } catch {
    // Reported in the snackbar.
  }
}

async function stop(): Promise<void> {
  await stopLaunch()
}
</script>

<template>
  <v-row>
    <v-col cols="12" md="7">
      <v-card>
        <v-card-title>Play</v-card-title>
        <v-card-text>
          <v-select
            :items="profiles"
            item-title="name"
            item-value="id"
            label="Profile"
            :model-value="selected?.id ?? null"
            @update:model-value="selectProfileById"
          />
          <v-text-field
            v-if="selected"
            :model-value="selected.uuid"
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

          <v-divider class="my-6" />

          <v-select
            :items="installations"
            item-title="id"
            item-value="id"
            label="Installed version"
            :model-value="selected?.versionId ?? null"
            :disabled="!selected || installations.length === 0"
            @update:model-value="selectVersion"
          />
          <v-alert v-if="installations.length === 0" type="info" variant="tonal" class="mt-2">
            No versions found yet. Install one from <strong>Installed versions</strong>, or add an
            installation you already have — then pick it here.
          </v-alert>

          <v-alert v-if="pendingServer" type="warning" variant="tonal" class="mt-4">
            Next launch joins {{ pendingServer.name }} ({{ pendingServer.host }}:{{ pendingServer.port }}).
            Enable join only if that server is configured for unauthenticated clients.
          </v-alert>
        </v-card-text>
        <v-card-actions>
          <v-btn color="primary" :disabled="busy || !selected || !selected.versionId || isRunning" @click="launch">
            Launch
          </v-btn>
          <v-btn :disabled="!isRunning" @click="stop">Stop</v-btn>
          <v-btn variant="text" :disabled="!selected || !selected.versionId" @click="previewPlan">
            Show command
          </v-btn>
          <v-spacer />
          <v-btn variant="text" @click="rescan">Rescan versions</v-btn>
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

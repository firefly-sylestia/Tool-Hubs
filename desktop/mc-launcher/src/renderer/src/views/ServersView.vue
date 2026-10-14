<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLauncher } from '@renderer/composables/useLauncher'

const router = useRouter()
const { servers, pendingServer, addServer, removeServer, setPendingServer } = useLauncher()

const name = ref('')
const host = ref('')
const port = ref(25565)
const busy = ref(false)

const canAdd = computed(() => host.value.trim() !== '' && Number(port.value) > 0)

async function add(): Promise<void> {
  if (!canAdd.value) return
  busy.value = true
  try {
    await addServer(name.value.trim() === '' ? host.value.trim() : name.value.trim(), host.value.trim(), Number(port.value))
    name.value = ''
    host.value = ''
    port.value = 25565
  } catch {
    // Surfaced by the composable.
  } finally {
    busy.value = false
  }
}

async function connect(id: string): Promise<void> {
  await setPendingServer(id)
  await router.push('/')
}
</script>

<template>
  <v-row>
    <v-col cols="12" md="7">
      <v-card>
        <v-card-title>Saved servers</v-card-title>
        <v-card-text>
          <v-alert type="info" variant="tonal" class="mb-4">
            This launcher has no account and no session. It can only join servers that accept
            unauthenticated clients (offline mode). Public servers and Realms will refuse the join.
          </v-alert>
          <v-list v-if="servers.length > 0">
            <v-list-item
              v-for="server in servers"
              :key="server.id"
              :title="server.name"
              :subtitle="`${server.host}:${server.port}`"
            >
              <template #append>
                <v-btn size="small" :disabled="pendingServer?.id === server.id" @click="connect(server.id)">
                  {{ pendingServer?.id === server.id ? 'Queued' : 'Queue join' }}
                </v-btn>
                <v-btn size="small" variant="text" @click="removeServer(server.id)">Remove</v-btn>
              </template>
            </v-list-item>
          </v-list>
          <p v-else class="text-medium-emphasis">No servers saved yet.</p>
          <v-btn v-if="pendingServer" class="mt-4" variant="text" @click="setPendingServer(null)">
            Cancel queued join
          </v-btn>
        </v-card-text>
      </v-card>
    </v-col>

    <v-col cols="12" md="5">
      <v-card>
        <v-card-title>Add a server</v-card-title>
        <v-card-text>
          <v-text-field v-model="name" label="Name" />
          <v-text-field v-model="host" label="Address" hint="Hostname or IP, no port." persistent-hint />
          <v-text-field v-model.number="port" label="Port" type="number" class="mt-4" />
        </v-card-text>
        <v-card-actions>
          <v-btn :disabled="busy || !canAdd" @click="add">Add</v-btn>
        </v-card-actions>
      </v-card>
    </v-col>
  </v-row>
</template>

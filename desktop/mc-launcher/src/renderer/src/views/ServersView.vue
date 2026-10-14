<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { ServerStatus, ServersDatResult } from '@shared/types'
import { describeStatus } from '@shared/server-text'
import { useLauncher } from '@renderer/composables/useLauncher'

const router = useRouter()
const {
  servers,
  pendingServer,
  selectedInstance,
  addServer,
  removeServer,
  setPendingServer,
  pingServer,
  gameServers,
  syncGameServers,
  importGameServers
} = useLauncher()

const name = ref('')
const host = ref('')
const port = ref(25565)
const busy = ref(false)
const note = ref<string | null>(null)
const noteType = ref<'info' | 'success' | 'warning'>('info')

/** Pings are kept per server, so a list does not re-ping when it re-renders. */
const statuses = ref<Record<string, ServerStatus>>({})
const pinging = ref<Record<string, boolean>>({})
const game = ref<ServersDatResult | null>(null)
const gameBusy = ref(false)

const canAdd = computed(() => host.value.trim() !== '' && Number(port.value) > 0)

function report(message: string, type: 'info' | 'success' | 'warning' = 'info'): void {
  noteType.value = type
  note.value = message
}

/** The line under a server: what the last ping said, or that it has not been pinged. */
function statusLine(id: string): string {
  if (pinging.value[id]) return 'pinging…'
  const status = statuses.value[id]
  return status ? describeStatus(status) : 'not pinged yet'
}

async function ping(id: string): Promise<void> {
  pinging.value = { ...pinging.value, [id]: true }
  try {
    const status = await pingServer({ id })
    statuses.value = { ...statuses.value, [id]: status }
  } catch {
    // A thrown call is a bug in the bridge, not an offline server: that path
    // reports itself through the returned status above.
  } finally {
    pinging.value = { ...pinging.value, [id]: false }
  }
}

async function pingAll(): Promise<void> {
  for (const server of servers.value) await ping(server.id)
}

async function add(): Promise<void> {
  if (!canAdd.value) return
  busy.value = true
  try {
    await addServer(
      name.value.trim() === '' ? host.value.trim() : name.value.trim(),
      host.value.trim(),
      Number(port.value)
    )
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

async function loadGameServers(): Promise<void> {
  if (!selectedInstance.value) {
    game.value = null
    return
  }
  try {
    game.value = await gameServers(selectedInstance.value.id)
  } catch {
    // Reported in the snackbar.
  }
}

async function syncToGame(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  gameBusy.value = true
  try {
    const result = await syncGameServers(instance.id)
    game.value = result
    report(
      `Wrote ${result.added} new server(s) into ${instance.name}'s own servers.dat, which kept its ` +
        `${result.kept} — ${result.total} in total.`,
      'success'
    )
  } catch {
    // Reported in the snackbar.
  } finally {
    gameBusy.value = false
  }
}

async function importFromGame(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  gameBusy.value = true
  try {
    const result = await importGameServers(instance.id)
    await loadGameServers()
    report(
      result.added === 0
        ? `${instance.name}'s own list has nothing this launcher does not already have.`
        : `Imported ${result.added} server(s) from ${instance.name}'s own list.`,
      result.added === 0 ? 'info' : 'success'
    )
  } catch {
    // Reported in the snackbar.
  } finally {
    gameBusy.value = false
  }
}

onMounted(() => {
  void loadGameServers()
})
</script>

<template>
  <v-row>
    <v-col cols="12" md="7">
      <v-card>
        <v-card-title class="d-flex align-center">
          Saved servers
          <v-spacer />
          <v-btn
            size="small"
            variant="text"
            :disabled="servers.length === 0"
            :loading="Object.values(pinging).some(Boolean)"
            @click="pingAll()"
          >
            Ping all
          </v-btn>
        </v-card-title>
        <v-card-text>
          <v-alert type="info" variant="tonal" class="mb-4">
            This launcher has no account and no session. It can only join servers that accept
            unauthenticated clients (offline mode). Public servers and Realms will refuse the join.
          </v-alert>

          <v-list v-if="servers.length > 0" class="app-stagger">
            <v-list-item
              v-for="server in servers"
              :key="server.id"
              :title="server.name"
              :subtitle="`${server.host}:${server.port} · ${statusLine(server.id)}`"
            >
              <template #prepend>
                <v-avatar v-if="statuses[server.id]?.icon" size="32" rounded>
                  <v-img :src="statuses[server.id].icon ?? ''" />
                </v-avatar>
              </template>
              <template #append>
                <v-btn
                  size="small"
                  variant="text"
                  :loading="pinging[server.id]"
                  @click="ping(server.id)"
                >
                  Ping
                </v-btn>
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
          <v-alert v-if="note" :type="noteType" variant="tonal" class="mt-4">{{ note }}</v-alert>
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

      <v-card class="mt-4">
        <v-card-title>The game's own list</v-card-title>
        <v-card-text>
          <p v-if="!selectedInstance" class="text-medium-emphasis">
            Select an instance first: each instance has its own game directory, so each has its own
            <code>servers.dat</code>.
          </p>
          <template v-else>
            <p class="text-medium-emphasis">
              {{ selectedInstance.name }}'s <code>servers.dat</code> — the list the game's own
              multiplayer screen reads and writes.
            </p>
            <v-alert v-if="game?.problem" type="warning" variant="tonal" class="my-2">
              That file could not be read ({{ game.problem }}), so nothing will be written over it.
            </v-alert>
            <v-alert v-else-if="game && game.total === 0" type="info" variant="tonal" class="my-2">
              No servers in the game's own list yet. Writing adds this launcher's list to it.
            </v-alert>
            <v-list v-else-if="game" density="compact">
              <v-list-item
                v-for="server in game.servers"
                :key="server.address"
                :title="server.name"
                :subtitle="server.address + (server.players.length > 0 ? ` · ${server.players.length} player(s) recorded` : '')"
              />
            </v-list>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn
            variant="text"
            :disabled="!selectedInstance || gameBusy"
            @click="importFromGame()"
          >
            Import from the game
          </v-btn>
          <v-btn
            color="primary"
            variant="tonal"
            :disabled="!selectedInstance || gameBusy || game?.problem !== null"
            @click="syncToGame()"
          >
            Write the launcher's list into it
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-col>
  </v-row>
</template>

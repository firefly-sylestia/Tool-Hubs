<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { InstanceDiagnostics, LogFinding } from '@shared/types'
import { useLauncher } from '@renderer/composables/useLauncher'

const { lines, clearLogs, selectedInstance, diagnostics, openPath } = useLauncher()

const pinned = ref(true)
const viewport = ref<HTMLElement | null>(null)
const report = ref<InstanceDiagnostics | null>(null)
const chosenCrash = ref<string | null>(null)
const loading = ref(false)

const rendered = computed(() =>
  lines.value.map((line) => ({
    ...line,
    text: `${line.stream === 'stderr' ? '!' : '>'} ${line.line}`
  }))
)

const findings = computed<LogFinding[]>(() => report.value?.findings ?? [])
const severityColor = (severity: LogFinding['severity']): string =>
  severity === 'error' ? 'error' : severity === 'warning' ? 'warning' : 'info'

async function load(name?: string | null): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) {
    report.value = null
    return
  }
  loading.value = true
  try {
    report.value = await diagnostics(instance.id, name ?? chosenCrash.value)
  } catch {
    // Reported in the snackbar.
  } finally {
    loading.value = false
  }
}

function openCrash(name: string): void {
  chosenCrash.value = name
  void load(name)
}

watch(
  () => selectedInstance.value?.id ?? null,
  () => {
    chosenCrash.value = null
    void load(null)
  }
)

onMounted(() => {
  void load(null)
})

watch(lines, async () => {
  if (!pinned.value) return
  await nextTick()
  const element = viewport.value
  if (element) element.scrollTop = element.scrollHeight
})
</script>

<template>
  <v-card>
    <v-card-title class="d-flex align-center">
      What went wrong
      <v-spacer />
      <v-btn size="small" variant="text" :disabled="!selectedInstance" :loading="loading" @click="load()">
        Re-read
      </v-btn>
    </v-card-title>
    <v-card-text>
      <p v-if="!selectedInstance" class="text-medium-emphasis">
        Select an instance first: a crash report and a log live in its own game directory.
      </p>
      <template v-else>
        <p class="text-medium-emphasis">
          Read from {{ selectedInstance.name }}'s own
          <code>crash-reports/</code> and <code>logs/latest.log</code>. Nothing here is
          modified — a crash report is evidence.
        </p>

        <v-alert v-if="report && !report.source" type="info" variant="tonal" class="mb-3">
          No crash report and no log in this instance yet. Play it once and the game will start writing
          them.
        </v-alert>

        <template v-if="report?.source">
          <v-select
            v-if="report.crashes.length > 0"
            :items="report.crashes.map((crash) => ({ title: `${crash.name} — ${crash.description ?? 'no description'}`, value: crash.name }))"
            :model-value="chosenCrash ?? report.crashes[0].name"
            label="Crash report"
            density="compact"
            hide-details
            class="mb-3"
            @update:model-value="openCrash"
          />

          <p class="text-medium-emphasis mb-2">
            From <strong>{{ report.source }}</strong>
            <template v-if="report.crashes[0]">
              · {{ report.crashes[0].description ?? 'no description' }}
              <template v-if="report.crashes[0].exception"> · {{ report.crashes[0].exception }}</template>
              <template v-if="report.crashes[0].minecraftVersion">
                · Minecraft {{ report.crashes[0].minecraftVersion }}
              </template>
              <template v-if="report.crashes[0].javaVersion"> · Java {{ report.crashes[0].javaVersion }}</template>
              <template v-if="report.crashes[0].mods > 0"> · {{ report.crashes[0].mods }} mods listed</template>
            </template>
          </p>
          <p v-if="findings.length === 0" class="mb-0">
            Nothing in it matches a problem this launcher knows how to name. The whole file is worth
            reading, or the log below has the launcher's own side of it.
          </p>

          <v-expansion-panels v-else variant="accordion" class="mt-2">
            <v-expansion-panel v-for="finding in findings" :key="finding.id">
              <v-expansion-panel-title>
                <v-chip :color="severityColor(finding.severity)" size="small" variant="tonal" class="mr-3">
                  {{ finding.severity }}
                </v-chip>
                {{ finding.title }}
              </v-expansion-panel-title>
              <v-expansion-panel-text>
                <ul class="mb-3">
                  <li v-for="hint in finding.hints" :key="hint">{{ hint }}</li>
                </ul>
                <pre class="evidence">{{ finding.evidence }}</pre>
              </v-expansion-panel-text>
            </v-expansion-panel>
          </v-expansion-panels>
        </template>

        <v-card-actions class="px-0">
          <v-btn
            variant="text"
            :disabled="!report?.latestLog.exists"
            @click="report && openPath(report.logPath)"
          >
            Open latest.log
          </v-btn>
          <v-btn variant="text" :disabled="!report?.crashes.length" @click="report && openPath(report.crashDir)">
            Crash reports folder
          </v-btn>
        </v-card-actions>
      </template>
    </v-card-text>
  </v-card>

  <v-card class="mt-4">
    <v-card-title>Launcher log</v-card-title>
    <v-card-actions>
      <v-btn @click="clearLogs">Clear</v-btn>
      <v-btn variant="text" @click="pinned = !pinned">
        {{ pinned ? 'Auto-scroll on' : 'Auto-scroll off' }}
      </v-btn>
    </v-card-actions>
    <v-card-text>
      <div ref="viewport" class="log">
        <p v-if="rendered.length === 0" class="text-medium-emphasis">No output yet.</p>
        <p
          v-for="(line, index) in rendered"
          :key="`${line.at}-${index}`"
          :class="line.stream === 'stderr' ? 'err' : 'out'"
        >
          {{ line.text }}
        </p>
      </div>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.log {
  max-height: 50vh;
  overflow: auto;
  font-family: monospace;
  font-size: 12px;
}
.log .out {
  color: #9ad29a;
}
.log .err {
  color: #f0a0a0;
}
.evidence {
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  padding: 8px;
  border-radius: 6px;
  background: rgba(var(--v-theme-on-surface), 0.06);
}
</style>

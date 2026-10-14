<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useLauncher } from '@renderer/composables/useLauncher'

const { lines, clearLogs } = useLauncher()
const pinned = ref(true)
const viewport = ref<HTMLElement | null>(null)

const rendered = computed(() =>
  lines.value.map((line) => ({
    ...line,
    text: `${line.stream === 'stderr' ? '!' : '>'} ${line.line}`
  }))
)

watch(lines, async () => {
  if (!pinned.value) return
  await nextTick()
  const element = viewport.value
  if (element) element.scrollTop = element.scrollHeight
})
</script>

<template>
  <v-card>
    <v-card-title>Log</v-card-title>
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
  max-height: 60vh;
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
</style>

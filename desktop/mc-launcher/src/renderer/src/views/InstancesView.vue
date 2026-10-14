<script setup lang="ts">
import { computed } from 'vue'
import { useLauncher } from '@renderer/composables/useLauncher'

const { installations, settings, rescan } = useLauncher()

const gameDir = computed(() => settings.value?.gameDir ?? '')
</script>

<template>
  <v-card>
    <v-card-title>Installed versions</v-card-title>
    <v-card-text>
      <p class="text-medium-emphasis">Game directory: {{ gameDir }}</p>
      <v-alert v-if="installations.length === 0" type="info" variant="tonal" class="my-4">
        Nothing found under <code>versions/</code>. This launcher does not download game files: it
        runs an installation that is already on disk.
      </v-alert>
      <v-list v-else>
        <v-list-item
          v-for="version in installations"
          :key="version.id"
          :title="version.id"
          :subtitle="`${version.type ?? 'unknown type'} · java ${version.javaMajorVersion ?? 'any'} · assets ${version.assetsIndex ?? 'none'}${version.inheritsFrom ? ' · inherits ' + version.inheritsFrom : ''}`"
        />
      </v-list>
    </v-card-text>
    <v-card-actions>
      <v-btn @click="rescan">Rescan</v-btn>
    </v-card-actions>
  </v-card>
</template>

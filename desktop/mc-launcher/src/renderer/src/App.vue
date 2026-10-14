<script setup lang="ts">
import { computed } from 'vue'
import { useLauncher } from '@renderer/composables/useLauncher'
import { routes } from '@renderer/router'

const { validating, error, tasks, dismissError } = useLauncher()
const windowApi = window.windowController

const navItems = computed(() =>
  routes.map((route) => ({ title: String(route.meta?.title ?? route.name), to: route.path }))
)

const activeTask = computed(() => tasks.value.find((task) => task.state === 'running') ?? null)
const showError = computed(() => error.value !== null)
</script>

<template>
  <v-app>
    <v-app-bar flat density="comfortable">
      <v-app-bar-title>MC Launcher</v-app-bar-title>
      <v-spacer />
      <v-btn variant="text" @click="windowApi.minimize()">Minimize</v-btn>
      <v-btn variant="text" @click="windowApi.maximize()">Maximize</v-btn>
      <v-btn variant="text" @click="windowApi.close()">Close</v-btn>
    </v-app-bar>

    <v-navigation-drawer permanent width="220">
      <v-list nav density="compact">
        <v-list-item v-for="item in navItems" :key="item.to" :to="item.to" :title="item.title" />
      </v-list>
      <v-divider />
      <v-list density="compact">
        <v-list-item
          title="Local profiles only"
          subtitle="No account is attached to this launcher"
        />
      </v-list>
    </v-navigation-drawer>

    <v-main>
      <v-progress-linear
        v-if="validating || activeTask"
        :model-value="activeTask ? activeTask.progress * 100 : 0"
        :indeterminate="Boolean(activeTask) === false"
        height="3"
      />
      <v-container fluid>
        <router-view />
      </v-container>
    </v-main>

    <v-snackbar :model-value="showError" color="error" timeout="6000" @update:model-value="dismissError">
      {{ error }}
    </v-snackbar>
  </v-app>
</template>

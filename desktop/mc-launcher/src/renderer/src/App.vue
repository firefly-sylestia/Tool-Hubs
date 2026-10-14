<script setup lang="ts">
import { computed } from 'vue'
import { useLauncher } from '@renderer/composables/useLauncher'
import { routes } from '@renderer/router'

const { validating, error, tasks, profiles, runningProfileId, dismissError, stopLaunch } = useLauncher()
const windowApi = window.windowController

/** The icon each page's row carries, so the navigation reads at a glance. */
const NAV_ICONS: Record<string, string> = {
  '/': 'mdi-play-circle-outline',
  '/store': 'mdi-storefront-outline',
  '/instances': 'mdi-cube-outline',
  '/servers': 'mdi-server-network',
  '/settings': 'mdi-cog-outline',
  '/logs': 'mdi-text-box-search-outline'
}

const navItems = computed(() =>
  routes.map((route) => ({
    title: String(route.meta?.title ?? route.name),
    to: route.path,
    icon: NAV_ICONS[route.path] ?? 'mdi-chevron-right'
  }))
)

/** The game itself, when a process is alive. */
const runningProfile = computed(
  () => profiles.value.find((profile) => profile.id === runningProfileId.value) ?? null
)
/** The install, download or launch currently working, if any. */
const activeTask = computed(() => tasks.value.find((task) => task.state === 'running') ?? null)
const taskPercent = computed(() =>
  activeTask.value ? Math.round(activeTask.value.progress * 100) : 0
)
const showError = computed(() => error.value !== null)

/** The indicator is worth a control too, so stopping is never a page away. */
async function stop(): Promise<void> {
  try {
    await stopLaunch()
  } catch {
    // The composable already put the message in the snackbar.
  }
}
</script>

<template>
  <v-app>
    <v-app-bar flat density="comfortable" class="app-bar" height="68">
      <div class="app-bar__mark">
        <v-avatar size="36" rounded color="primary" variant="tonal">
          <v-icon icon="mdi-cube-scan" />
        </v-avatar>
        <div class="app-bar__name">
          <span>MC Launcher</span>
          <small>Local profiles only</small>
        </div>
      </div>

      <v-spacer />

      <!-- What is happening right now, from every page: the game a profile is
           playing, and the one install or launch still working. -->
      <v-chip
        v-if="runningProfile"
        class="state-chip running-chip mr-2"
        color="success"
        variant="tonal"
        @click="$router.push('/')"
      >
        <span class="status-dot status-dot--live" />
        {{ runningProfile.name }} is playing
      </v-chip>
      <v-btn
        v-if="runningProfile"
        class="mr-3"
        size="small"
        variant="tonal"
        color="error"
        prepend-icon="mdi-stop"
        @click="stop"
      >
        Stop
      </v-btn>

      <v-chip v-if="activeTask" class="state-chip mr-3" color="primary" variant="tonal">
        <v-icon icon="mdi-progress-download" start />
        {{ activeTask.name }} {{ taskPercent }}%
      </v-chip>

      <div class="window-controls">
        <v-btn icon="mdi-window-minimize" variant="text" aria-label="Minimize" @click="windowApi.minimize()" />
        <v-btn icon="mdi-window-maximize" variant="text" aria-label="Maximize" @click="windowApi.maximize()" />
        <v-btn
          icon="mdi-close"
          variant="text"
          class="window-controls__close"
          aria-label="Close"
          @click="windowApi.close()"
        />
      </div>
    </v-app-bar>

    <v-navigation-drawer permanent width="236" class="app-drawer">
      <v-list nav density="comfortable" class="py-2">
        <v-list-item
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          :title="item.title"
          :prepend-icon="item.icon"
          rounded="lg"
        />
      </v-list>

      <template #append>
        <v-divider />
        <v-list density="compact" class="py-2">
          <v-list-item
            title="No account"
            subtitle="Nothing here signs in"
            prepend-icon="mdi-account-off-outline"
          />
        </v-list>
      </template>
    </v-navigation-drawer>

    <v-main>
      <v-progress-linear
        v-if="validating || activeTask"
        :model-value="activeTask ? taskPercent : 0"
        :indeterminate="activeTask === null"
        height="3"
      />
      <div class="mood">
        <v-container fluid class="page-pad">
          <router-view v-slot="{ Component }">
            <transition name="page" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </v-container>
      </div>
    </v-main>

    <v-snackbar :model-value="showError" color="error" timeout="6000" @update:model-value="dismissError">
      {{ error }}
    </v-snackbar>
  </v-app>
</template>

<style scoped>
.app-bar__mark {
  display: flex;
  align-items: center;
  gap: 10px;
}

.app-bar__name {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.app-bar__name span {
  font-size: 0.95rem;
  font-weight: 600;
}

.app-bar__name small {
  font-size: 0.72rem;
  opacity: 0.6;
}

/* The chips are the only busy thing in the bar, so they are the only thing that
   moves: the dot breathes while the game runs. */
.running-chip {
  cursor: pointer;
}

.window-controls {
  display: flex;
  align-items: center;
}

.window-controls__close:hover {
  color: rgb(var(--v-theme-error));
}
</style>

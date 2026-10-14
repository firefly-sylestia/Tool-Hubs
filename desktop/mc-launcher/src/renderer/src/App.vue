<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTheme } from 'vuetify'
import type { ThemeChoice } from '@shared/types'
import { useLauncher } from '@renderer/composables/useLauncher'
import { routes } from '@renderer/router'

const route = useRoute()
const router = useRouter()
const {
  settings,
  validating,
  error,
  tasks,
  profiles,
  runningProfileId,
  dismissError,
  stopLaunch,
  launchSelected
} = useLauncher()

/**
 * The theme the window paints, which the launcher's settings own.
 *
 * `system` is Vuetify's own follow-the-desktop mode rather than a palette, and
 * it keeps following it: the preference is re-read while the app is open, so a
 * desktop that switches at dusk takes the launcher with it.
 */
const theme = useTheme()

function applyTheme(choice: ThemeChoice): void {
  void theme.change(choice)
}

watch(() => settings.value?.theme ?? 'system', applyTheme, { immediate: true })
const windowApi = window.windowController

/** The key that goes with the number, which is not the same on every desktop. */
const modifier = navigator.userAgent.includes('Mac') ? 'Cmd' : 'Ctrl'
const maximized = ref(false)
/**
 * Whether the shortcut modifier is being held down.
 *
 * A column of `Ctrl 1` chips beside the navigation is noise to anyone who is
 * not reading it, so they appear while the modifier is held — which is exactly
 * when someone is looking for them — and not otherwise.
 */
const modifierDown = ref(false)

/** The icon each page's row carries, so the navigation reads at a glance. */
const NAV_ICONS: Record<string, string> = {
  '/': 'mdi-play-circle-outline',
  '/store': 'mdi-storefront-outline',
  '/instances': 'mdi-cube-outline',
  '/servers': 'mdi-server-network',
  '/settings': 'mdi-cog-outline',
  '/logs': 'mdi-text-box-search-outline',
  '/downloads': 'mdi-download-box-outline'
}

const navItems = computed(() =>
  routes.map((page, index) => ({
    title: String(page.meta?.title ?? page.name),
    to: page.path,
    icon: NAV_ICONS[page.path] ?? 'mdi-chevron-right',
    shortcut: `${modifier} ${index + 1}`
  }))
)

/** The page that is open, so the title bar says where you are. */
const pageTitle = computed(
  () => String(routes.find((page) => page.path === route.path)?.meta?.title ?? 'MC Launcher')
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

/** Asks the window whether it is maximized, which is what the icon shows. */
async function refreshMaximized(): Promise<void> {
  try {
    maximized.value = (await windowApi.isMaximized()).maximized
  } catch {
    // A window that cannot answer leaves the icon as it is.
  }
}

async function toggleMaximize(): Promise<void> {
  try {
    const result = await windowApi.maximize()
    maximized.value = result.maximized
  } catch {
    await refreshMaximized()
  }
}

/** Typing a shortcut into a field is text, not a command. */
function isTyping(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null
  if (!element) return false
  return (
    element.tagName === 'INPUT' ||
    element.tagName === 'TEXTAREA' ||
    element.tagName === 'SELECT' ||
    element.isContentEditable
  )
}

/**
 * The keyboard, which the shell had none of: Ctrl/Cmd and a number for a page,
 * Ctrl/Cmd and Enter to launch what is selected.
 */
function onKeydown(event: KeyboardEvent): void {
  if (!event.ctrlKey && !event.metaKey) return
  if (isTyping(event.target)) return

  if (event.key === 'Enter') {
    event.preventDefault()
    void launchSelected().catch(() => {
      // The composable put the reason in the snackbar.
    })
    return
  }

  const index = Number(event.key)
  if (!Number.isInteger(index) || index < 1 || index > routes.length) return
  event.preventDefault()
  void router.push(routes[index - 1].path)
}

function onModifierDown(event: KeyboardEvent): void {
  if (event.key === 'Control' || event.key === 'Meta') modifierDown.value = true
}

function onModifierUp(event: KeyboardEvent): void {
  if (event.key === 'Control' || event.key === 'Meta') modifierDown.value = false
}

/** Losing focus mid-press must not leave the hints stuck on screen. */
function onBlur(): void {
  modifierDown.value = false
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('keydown', onModifierDown)
  window.addEventListener('keyup', onModifierUp)
  window.addEventListener('blur', onBlur)
  // The window can also be maximized by its own title bar or the OS, so the icon
  // is re-read whenever the window changes shape.
  window.addEventListener('resize', refreshMaximized)
  void refreshMaximized()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('keydown', onModifierDown)
  window.removeEventListener('keyup', onModifierUp)
  window.removeEventListener('blur', onBlur)
  window.removeEventListener('resize', refreshMaximized)
})
</script>

<template>
  <v-app>
    <v-app-bar flat density="comfortable" class="app-bar" height="68">
      <div class="app-bar__mark">
        <v-avatar size="36" rounded color="primary" variant="tonal">
          <v-icon icon="mdi-cube-scan" />
        </v-avatar>
        <div class="app-bar__name">
          <span>{{ pageTitle }}</span>
          <small>MC Launcher, local profiles only</small>
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
        <v-btn
          :icon="maximized ? 'mdi-window-restore' : 'mdi-window-maximize'"
          variant="text"
          :aria-label="maximized ? 'Restore the window' : 'Maximize the window'"
          @click="toggleMaximize()"
        />
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
        >
          <template #append>
            <span v-if="modifierDown" class="shortcut-hint">{{ item.shortcut }}</span>
          </template>
        </v-list-item>
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

/* Shown only while the modifier is held: a shortcut nobody is told about is a
   shortcut nobody uses, and a column of them is clutter the rest of the time. */
.shortcut-hint {
  font-size: 0.68rem;
  letter-spacing: 0.02em;
  opacity: 0.5;
  border: 1px solid currentColor;
  border-radius: 5px;
  padding: 1px 5px;
}
</style>

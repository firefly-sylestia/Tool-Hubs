<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type {
  ModrinthHit,
  ModrinthProjectDetail,
  ModrinthStatus,
  ModrinthVersion,
  ResourceKind,
  StoreInstall
} from '@shared/types'
import { describeError, useLauncher } from '@renderer/composables/useLauncher'

/**
 * The store: Modrinth's catalogue, browsable without searching and searchable
 * when you know what you want.
 *
 * It is a page of its own rather than a dialog inside an instance because
 * browsing is what you do *before* choosing where something goes: the tabs cover
 * resources (which need an instance) and modpacks (which create one), and the
 * filter says which instance a resource install would land in.
 */

type StoreKind = ResourceKind | 'modpack'

const router = useRouter()
const {
  instances,
  selectedInstance,
  selectInstance,
  modrinthStatus,
  modrinthSearch,
  tasks,
  modrinthVersions,
  modrinthProject,
  storeInstalls,
  installFromModrinth,
  installModpackFromStore
} = useLauncher()

const tabs: { title: string; value: StoreKind; icon: string; blurb: string }[] = [
  { title: 'Mods', value: 'mod', icon: 'mdi-puzzle-outline', blurb: 'go into an instance\'s mods folder' },
  {
    title: 'Resource packs',
    value: 'resourcepack',
    icon: 'mdi-palette-outline',
    blurb: 'change how the game looks'
  },
  {
    title: 'Shaders',
    value: 'shaderpack',
    icon: 'mdi-shimmer',
    blurb: 'need a shader loader such as Iris'
  },
  {
    title: 'Modpacks',
    value: 'modpack',
    icon: 'mdi-package-variant-closed',
    blurb: 'become a new instance, with their own game version'
  }
]

const sorts = [
  { title: 'Most downloaded', value: 'downloads' },
  { title: 'Relevance', value: 'relevance' },
  { title: 'Most followed', value: 'follows' },
  { title: 'Newest', value: 'newest' },
  { title: 'Recently updated', value: 'updated' }
]

const PAGE_SIZE = 20

const kind = ref<StoreKind>('mod')
/**
 * Browsing the catalogue does not need an instance, and it does not need to obey
 * the one that happens to be selected either, so both are switchable here.
 */
const anyVersion = ref(false)
const query = ref('')
const sort = ref('downloads')
const hits = ref<ModrinthHit[]>([])
const totalHits = ref(0)
const offset = ref(0)
const loading = ref(false)
const busy = ref(false)
const note = ref<string | null>(null)
const noteType = ref<'info' | 'success' | 'warning'>('info')
/**
 * Whether the last search failed rather than found nothing.
 *
 * "Nothing matched" and "Modrinth could not be reached" are different answers
 * and only one of them is about the search, so the empty state has to know
 * which one it is looking at.
 */
const failed = ref(false)
const checking = ref(false)
const status = ref<ModrinthStatus | null>(null)

/** What the store has already put in the selected instance, keyed by project. */
const installed = ref<StoreInstall[]>([])
const installedFor = computed(() => {
  const byProject = new Map<string, StoreInstall>()
  for (const item of installed.value) byProject.set(item.projectId, item)
  return byProject
})

/**
 * Results are kept briefly, keyed by what was asked for, so switching tabs and
 * coming back does not mean asking Modrinth the same question again.
 */
const CACHE_MS = 60_000
const searchCache = new Map<string, { at: number; hits: ModrinthHit[]; totalHits: number }>()

function cacheKey(): string {
  return JSON.stringify({
    query: query.value.trim(),
    kind: kind.value,
    sort: sort.value,
    offset: offset.value,
    instance: anyVersion.value ? null : (selectedInstance.value?.id ?? null),
    anyVersion: anyVersion.value
  })
}

const detail = reactive({
  open: false,
  loading: false,
  project: null as ModrinthProjectDetail | null,
  versions: [] as ModrinthVersion[],
  showAllVersions: false
})

const activeTab = computed(() => tabs.find((tab) => tab.value === kind.value) ?? tabs[0])

/** Instances to filter by, and a way to have none selected at all. */
const instanceOptions = computed(() => [
  { value: '', title: 'No instance', subtitle: 'browse the whole catalogue' },
  ...instances.value.map((instance) => ({
    value: instance.id,
    title: instance.name,
    subtitle: instance.versionId ?? 'no version yet'
  }))
])

function chooseInstance(value: string | null): void {
  void selectInstance(value === null || value === '' ? null : value)
}
/**
 * The install or download happening right now, and how far through it is.
 *
 * A spinner on the button says "working"; this says what is being fetched and
 * how much of it has arrived, which is the difference between a download that
 * is progressing and one that has stalled.
 */
const activeTask = computed(() => tasks.value.find((task) => task.state === 'running') ?? null)
const taskPercent = computed(() => Math.round((activeTask.value?.progress ?? 0) * 100))

/** The detail dialog's own project, when it is one this instance already has. */
const detailInstall = computed(() =>
  detail.project ? (installedFor.value.get(detail.project.projectId) ?? null) : null
)
const kindLabel = computed(() => activeTab.value.title)
/** A modpack is installed as an instance, so it needs no instance of its own. */
const requiresInstance = computed(() => kind.value !== 'modpack')
const readyToInstall = computed(() => !requiresInstance.value || selectedInstance.value !== null)
/** True when the list is narrowed to the selected instance's version and loader. */
const filtering = computed(
  () => kind.value !== 'modpack' && selectedInstance.value !== null && !anyVersion.value
)

function report(message: string, type: 'info' | 'success' | 'warning' = 'info'): void {
  noteType.value = type
  note.value = message
}

/** What this instance already has from the store, so its cards can say so. */
async function loadInstalled(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) {
    installed.value = []
    return
  }
  try {
    installed.value = (await storeInstalls(instance.id)).items
  } catch {
    // Reported in the snackbar; the cards simply do not claim anything.
  }
}

/**
 * The catalogue, filtered by the selected instance when there is one: without a
 * search word the page still shows what is most downloaded for it, which is what
 * makes opening the store anything other than an empty box.
 */
async function search(reset = true): Promise<void> {
  if (reset) offset.value = 0
  if (reset) note.value = null
  failed.value = false
  const key = cacheKey()
  const cached = reset ? searchCache.get(key) : undefined
  if (cached && Date.now() - cached.at < CACHE_MS) {
    hits.value = cached.hits
    totalHits.value = cached.totalHits
    return
  }

  loading.value = true
  try {
    const result = await modrinthSearch({
      query: query.value.trim(),
      kind: kind.value,
      sort: sort.value,
      limit: PAGE_SIZE,
      offset: offset.value,
      anyVersion: anyVersion.value
    })
    hits.value = reset ? result.hits : [...hits.value, ...result.hits]
    totalHits.value = result.totalHits
    if (reset) searchCache.set(key, { at: Date.now(), hits: result.hits, totalHits: result.totalHits })
  } catch (reason) {
    // The reason, not a guess at it: a rate limit, a refused connection and a
    // bad response all read the same otherwise, and only one of them is fixed by
    // trying again.
    failed.value = true
    if (reset) hits.value = []
    report(`Modrinth could not be reached: ${describeError(reason)}`, 'warning')
  } finally {
    loading.value = false
  }
}

/**
 * Asks Modrinth whether it answers, and reports what it said either way.
 *
 * This exists because "the store is empty" and "the store cannot reach the
 * internet" look identical in the list, and a launcher that does not say which
 * one it is leaves someone to guess.
 */
async function checkConnection(): Promise<void> {
  checking.value = true
  try {
    status.value = await modrinthStatus()
    if (status.value.reachable) {
      report(
        `Modrinth is answering: ${status.value.totalHits.toLocaleString()} projects, ` +
          `replied in ${status.value.elapsedMs} ms.`,
        'success'
      )
      await search()
    } else {
      report(`Modrinth could not be reached: ${status.value.error ?? 'no answer'}.`, 'warning')
    }
  } catch {
    // Reported in the snackbar.
  } finally {
    checking.value = false
  }
}

async function loadMore(): Promise<void> {
  offset.value += PAGE_SIZE
  await search(false)
}

function switchTab(next: StoreKind): void {
  kind.value = next
  detail.open = false
  void search()
}

async function openDetail(hit: ModrinthHit): Promise<void> {
  detail.open = true
  detail.loading = true
  detail.project = null
  detail.versions = []
  detail.showAllVersions = false
  try {
    detail.project = await modrinthProject(hit.projectId)
    // A modpack brings its own game version and loader, so its list is not
    // filtered by this instance the way a mod's is.
    detail.versions = await modrinthVersions(hit.projectId, kind.value === 'modpack')
  } catch {
    // Reported in the snackbar.
  } finally {
    detail.loading = false
  }
}

/** Installing the whole pack, which lands as a new instance. */
async function installPack(projectId: string, versionId?: string): Promise<void> {
  busy.value = true
  try {
    const result = await installModpackFromStore({ projectId, versionId: versionId ?? null })
    report(
      `Installed ${result.summary.name} as a new instance: ${result.files} files downloaded, ` +
        `${result.overrides} unpacked from the pack.` +
        (result.unresolved.length > 0 ? ` ${result.unresolved.length} file(s) need CurseForge's API.` : ''),
      result.unresolved.length > 0 ? 'warning' : 'success'
    )
    detail.open = false
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

/** Installing one file into the selected instance. */
async function installResource(hit: ModrinthHit | null, versionId?: string): Promise<void> {
  const instance = selectedInstance.value
  const projectId = hit?.projectId ?? detail.project?.projectId ?? null
  if (!projectId) return
  if (!instance) {
    report(
      `Pick an instance first: ${kindLabel.value.toLowerCase()} are installed into one instance's own folder.`,
      'warning'
    )
    return
  }
  busy.value = true
  try {
    const result = await installFromModrinth({
      projectId,
      versionId: versionId ?? null,
      kind: kind.value === 'modpack' ? 'mod' : kind.value,
      instanceId: instance.id
    })
    const dependencies = result.installed.filter((item) => item.dependency)
    report(
      `Installed ${result.requested} into ${instance.name}` +
        (dependencies.length > 0 ? ` with ${dependencies.length} required dependency file(s)` : '') +
        '.' +
        (result.warnings.length > 0 ? ` ${result.warnings.join(' ')}` : ''),
      result.warnings.length > 0 ? 'warning' : 'success'
    )
    await loadInstalled()
    detail.open = false
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function install(hit: ModrinthHit | null, versionId?: string): Promise<void> {
  if (kind.value === 'modpack') {
    const projectId = hit?.projectId ?? detail.project?.projectId
    if (projectId) await installPack(projectId, versionId)
    return
  }
  await installResource(hit, versionId)
}

/** Opens a project or a gallery image in the OS browser. */
function openLink(url: string): void {
  window.open(url, '_blank')
}

function downloads(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (value >= 1_000) return `${(value / 1_000).toFixed(0)}k`
  return String(value)
}

watch(
  () => selectedInstance.value?.id ?? null,
  () => {
    // What fits the instance changes with it, and so does what it already has.
    void loadInstalled()
    void search()
  }
)

onMounted(() => {
  void loadInstalled()
  void search()
})
</script>

<template>
  <v-card class="hero-card mb-4">
    <v-card-text>
      <div class="d-flex align-center flex-wrap ga-3">
        <div class="hero-icon"><v-icon icon="mdi-storefront-outline" /></div>
        <div class="flex-grow-1">
          <h1 class="text-h5 mb-0">Modrinth</h1>
          <p class="text-body-2 text-medium-emphasis mb-0">
            Browsing {{ kindLabel.toLowerCase() }}, which {{ activeTab.blurb }}.
          </p>
        </div>
        <v-chip
          v-if="filtering"
          class="state-chip"
          color="primary"
          variant="tonal"
          prepend-icon="mdi-cube-outline"
        >
          fits {{ selectedInstance?.name }}
        </v-chip>
        <v-chip v-else class="state-chip" color="info" variant="tonal" prepend-icon="mdi-earth">
          every version
        </v-chip>
        <v-btn
          variant="tonal"
          size="small"
          prepend-icon="mdi-lan-connect"
          :loading="checking"
          @click="checkConnection"
        >
          Check connection
        </v-btn>
      </div>

      <v-tabs :model-value="kind" class="mt-4" density="comfortable" @update:model-value="switchTab">
        <v-tab v-for="tab in tabs" :key="tab.value" :value="tab.value" :prepend-icon="tab.icon">
          {{ tab.title }}
        </v-tab>
      </v-tabs>

      <!-- Browsing needs no instance, and does not have to obey the one that is
           selected, so both are choices here rather than somewhere else. -->
      <div class="d-flex ga-3 align-center flex-wrap mt-4">
        <v-select
          :items="instanceOptions"
          item-title="title"
          item-value="value"
          label="Install into"
          prepend-inner-icon="mdi-cube-outline"
          :model-value="selectedInstance?.id ?? ''"
          density="comfortable"
          hide-details
          class="instance-field"
          @update:model-value="chooseInstance"
        />
        <v-switch
          v-model="anyVersion"
          color="primary"
          density="compact"
          hide-details
          label="Ignore its version"
          :disabled="!selectedInstance || kind === 'modpack'"
          @update:model-value="search()"
        />
      </div>

      <div class="d-flex ga-2 align-center flex-wrap mt-4">
        <v-text-field
          v-model="query"
          label="Search Modrinth"
          prepend-inner-icon="mdi-magnify"
          hide-details
          clearable
          density="comfortable"
          class="search-field"
          @keyup.enter="search()"
          @click:clear="search()"
        />
        <v-select
          v-model="sort"
          :items="sorts"
          label="Sort by"
          hide-details
          density="comfortable"
          class="sort-field"
          @update:model-value="search()"
        />
        <v-btn color="primary" prepend-icon="mdi-magnify" :loading="loading" @click="search()">Search</v-btn>
      </div>

      <v-alert v-if="note" :type="noteType" variant="tonal" class="mt-4">
        {{ note }}
        <template #append>
          <v-btn size="small" variant="text" prepend-icon="mdi-refresh" @click="search()">Try again</v-btn>
        </template>
      </v-alert>

      <p v-if="filtering" class="text-medium-emphasis text-body-2 mt-4 mb-0">
        Listing what fits <strong>{{ selectedInstance?.name }}</strong>
        ({{ selectedInstance?.versionId ?? 'no version' }}<template
          v-if="selectedInstance?.loader && selectedInstance.loader !== 'vanilla'"
        >, {{ selectedInstance.loader }}</template
        >). Files are installed into that instance.
      </p>
      <p v-else class="text-medium-emphasis text-body-2 mt-4 mb-0">
        Listing every version of every {{ kind === 'modpack' ? 'pack' : kindLabel.toLowerCase() }}. Turn
        <strong>Ignore its version</strong> off to see only what fits an instance.
      </p>

      <!-- What is downloading, while it downloads. The Downloads page has the
           whole list; this is the one that is about the page you are on. -->
      <div v-if="activeTask" class="mt-4">
        <div class="d-flex align-center flex-wrap ga-2">
          <span class="status-dot status-dot--live" />
          <strong>{{ activeTask.name }}</strong>
          <v-spacer />
          <span class="text-medium-emphasis text-body-2">{{ taskPercent }}%</span>
          <v-btn size="small" variant="text" prepend-icon="mdi-download-box-outline" to="/downloads">
            Downloads
          </v-btn>
        </div>
        <p class="text-body-2 mb-2 mt-1">{{ activeTask.message }}</p>
        <v-progress-linear :model-value="taskPercent" height="6" rounded color="primary" />
      </div>

      <v-alert
        v-if="requiresInstance && !selectedInstance"
        type="info"
        variant="tonal"
        class="mt-4"
        prepend-icon="mdi-information-outline"
      >
        <p class="mb-2">
          <strong>No instance selected</strong>, so this is the whole catalogue: nothing is filtered.
          Installing needs an instance to put the files in.
        </p>
        <p class="mb-2">
          Pick one above, or use the Modpacks tab, which arrives as its own instance.
        </p>
        <v-btn
          size="small"
          variant="tonal"
          prepend-icon="mdi-cube-outline"
          @click="router.push('/instances')"
        >
          Choose an instance
        </v-btn>
      </v-alert>
    </v-card-text>
  </v-card>

  <div v-if="loading && hits.length === 0" class="d-flex justify-center py-10">
    <v-progress-circular indeterminate color="primary" />
  </div>

  <v-row v-else-if="hits.length > 0" class="app-stagger">
    <v-col v-for="hit in hits" :key="hit.projectId" cols="12" md="6" lg="4">
      <!-- The whole card opens the project, not just the button in its corner:
           the page is the point of the card, and a button is a small target. -->
      <v-card
        class="h-100 d-flex flex-column result-card result-card--openable"
        role="button"
        tabindex="0"
        :aria-label="`Open ${hit.title}`"
        @click="openDetail(hit)"
        @keydown.enter.prevent="openDetail(hit)"
        @keydown.space.prevent="openDetail(hit)"
      >
        <v-card-text class="flex-grow-1">
          <div class="d-flex ga-3 align-start">
            <v-avatar v-if="hit.iconUrl" rounded size="52" class="result-card__icon">
              <v-img :src="hit.iconUrl" :alt="hit.title" />
            </v-avatar>
            <div v-else class="hero-icon result-card__icon-fallback">
              <v-icon icon="mdi-package-variant" />
            </div>
            <div class="flex-grow-1">
              <h3 class="text-subtitle-1 mb-1">{{ hit.title }}</h3>
              <p class="text-medium-emphasis text-body-2 mb-0 d-flex align-center ga-3 flex-wrap">
                <span class="d-inline-flex align-center ga-1">
                  <v-icon icon="mdi-download" size="x-small" />
                  {{ downloads(hit.downloads) }} downloads
                </span>
                <span class="d-inline-flex align-center ga-1">
                  <v-icon icon="mdi-heart-outline" size="x-small" />
                  {{ downloads(hit.follows) }} followers
                </span>
              </p>
            </div>
          </div>
          <p class="mt-3 mb-2 result-card__description">{{ hit.description }}</p>
          <div class="d-flex flex-wrap ga-1">
            <v-chip
              v-if="installedFor.get(hit.projectId)"
              size="small"
              color="success"
              variant="tonal"
              prepend-icon="mdi-check-circle"
            >
              In {{ selectedInstance?.name ?? 'this instance' }}
            </v-chip>
            <v-chip v-for="loader in hit.loaders" :key="loader" size="small" variant="tonal">
              {{ loader }}
            </v-chip>
            <v-chip
              v-for="category in hit.categories.slice(0, 3)"
              :key="category"
              size="small"
              variant="text"
            >
              {{ category }}
            </v-chip>
          </div>
        </v-card-text>
        <!-- Installing from a card is not the same gesture as opening it, so the
             actions keep the click to themselves. -->
        <v-card-actions @click.stop>
          <v-btn size="small" variant="text" prepend-icon="mdi-information-outline" @click="openDetail(hit)">
            Details
          </v-btn>
          <v-spacer />
          <v-btn
            size="small"
            color="primary"
            :loading="busy"
            :prepend-icon="readyToInstall ? 'mdi-download' : 'mdi-cube-off-outline'"
            @click="install(hit)"
          >
            {{ readyToInstall ? 'Install' : 'Pick an instance' }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-col>
  </v-row>

  <!-- A failed request and an empty result are different answers, so the page
       does not report one as the other. -->
  <div v-else-if="failed" class="text-center py-10">
    <v-icon icon="mdi-lan-disconnect" size="40" class="text-medium-emphasis" />
    <p class="text-medium-emphasis mt-3 mb-0">
      Modrinth did not answer, so there is nothing to list. The message above says what it said when
      it did not.
    </p>
    <v-btn class="mt-3" variant="tonal" prepend-icon="mdi-refresh" :loading="loading" @click="search()">
      Try again
    </v-btn>
  </div>

  <div v-else class="text-center py-10">
    <v-icon icon="mdi-magnify-close" size="40" class="text-medium-emphasis" />
    <p class="text-medium-emphasis mt-3 mb-0">
      Nothing matched. Modrinth filters by the instance's version and loader, so a build for another
      one will not appear.
    </p>
  </div>

  <div v-if="hits.length > 0 && hits.length < totalHits" class="text-center mt-4">
    <v-btn :loading="loading" variant="tonal" prepend-icon="mdi-chevron-down" @click="loadMore">
      Load more ({{ hits.length }} of {{ totalHits }})
    </v-btn>
  </div>
  <p v-else-if="hits.length > 0" class="text-center text-medium-emphasis text-body-2 mt-4 mb-0">
    That is all {{ totalHits }} of them.
  </p>

  <v-dialog v-model="detail.open" max-width="900" scrollable>
    <v-card>
      <v-card-title class="d-flex align-center ga-3">
        <v-avatar v-if="detail.project?.iconUrl" rounded size="40">
          <v-img :src="detail.project.iconUrl" />
        </v-avatar>
        <span>{{ detail.project?.title ?? 'Project' }}</span>
        <v-spacer />
        <v-chip v-if="detailInstall" size="small" color="success" variant="tonal" prepend-icon="mdi-check-circle">
          {{ detailInstall.versionNumber }}
        </v-chip>
        <v-chip size="small" variant="tonal">{{ detail.project?.projectType }}</v-chip>
      </v-card-title>
      <v-card-text>
        <v-progress-linear v-if="detail.loading" indeterminate height="3" class="mb-4" />

        <template v-if="detail.project">
          <p class="text-medium-emphasis d-flex align-center ga-3 flex-wrap">
            <span class="d-inline-flex align-center ga-1">
              <v-icon icon="mdi-download" size="small" />{{ downloads(detail.project.downloads) }} downloads
            </span>
            <span class="d-inline-flex align-center ga-1">
              <v-icon icon="mdi-heart-outline" size="small" />{{ downloads(detail.project.followers) }} followers
            </span>
            <span class="d-inline-flex align-center ga-1">
              <v-icon icon="mdi-update" size="small" />updated {{ detail.project.updated.slice(0, 10) }}
            </span>
            <span v-if="detail.project.license" class="d-inline-flex align-center ga-1">
              <v-icon icon="mdi-license" size="small" />{{ detail.project.license }}
            </span>
          </p>
          <p
            v-if="detail.project.clientSide || detail.project.serverSide"
            class="text-medium-emphasis text-body-2"
          >
            client: {{ detail.project.clientSide ?? 'unknown' }} · server:
            {{ detail.project.serverSide ?? 'unknown' }}
          </p>

          <div class="d-flex flex-wrap ga-1 my-3">
            <v-chip v-for="category in detail.project.categories" :key="category" size="small" variant="tonal">
              {{ category }}
            </v-chip>
          </div>

          <div v-if="detail.project.galleryUrls.length > 0" class="d-flex ga-2 mb-4 overflow-x-auto">
            <v-img
              v-for="url in detail.project.galleryUrls.slice(0, 4)"
              :key="url"
              :src="url"
              width="180"
              height="100"
              cover
              class="rounded"
            />
          </div>

          <!-- The description is shown as published: it is Markdown, and this app
               has no Markdown renderer to turn it into HTML honestly. -->
          <pre class="body">{{ detail.project.body }}</pre>

          <div v-if="detail.project.links.length > 0" class="mt-4 d-flex flex-wrap ga-2">
            <v-btn
              v-for="link in detail.project.links"
              :key="link.url"
              size="small"
              variant="tonal"
              prepend-icon="mdi-open-in-new"
              @click="openLink(link.url)"
            >
              {{ link.label }}
            </v-btn>
          </div>

          <v-divider class="my-4" />

          <p class="mb-2">
            <strong>Versions</strong>: the newest that fits is listed first. Required dependencies are
            downloaded with the file.
          </p>
          <v-btn
            v-if="detail.versions.length > 0"
            color="primary"
            class="mb-2"
            :loading="busy"
            :disabled="!readyToInstall"
            :prepend-icon="readyToInstall ? 'mdi-download' : 'mdi-cube-off-outline'"
            @click="install(null, detail.versions[0].id)"
          >
            Install {{ detail.versions[0].versionNumber }}
            <template v-if="detail.versions[0].versionType !== 'release'">
              ({{ detail.versions[0].versionType }})
            </template>
          </v-btn>
          <v-list
            density="compact"
            :max-height="detail.showAllVersions ? undefined : 220"
            class="overflow-y-auto"
          >
            <v-list-item
              v-for="version in detail.showAllVersions ? detail.versions : detail.versions.slice(0, 5)"
              :key="version.id"
              :title="`${version.versionNumber} (${version.versionType})`"
              :subtitle="`${version.gameVersions.slice(0, 5).join(', ')} · ${version.loaders.join(', ') || 'any loader'}${version.dependencies.some((d) => d.dependencyType === 'required') ? ' · needs ' + version.dependencies.filter((d) => d.dependencyType === 'required').length + ' dependency(ies)' : ''}`"
            >
              <template #prepend>
                <div class="row-icon" :class="version.versionType === 'release' ? 'row-icon--success' : 'row-icon--warning'">
                  <v-icon
                    :icon="version.versionType === 'release' ? 'mdi-tag-check-outline' : 'mdi-flask-outline'"
                    size="20"
                  />
                </div>
              </template>
              <template #append>
                <v-btn
                  size="small"
                  :loading="busy"
                  :disabled="!readyToInstall"
                  @click="install(null, version.id)"
                >
                  Install
                </v-btn>
              </template>
            </v-list-item>
          </v-list>
          <v-btn
            v-if="detail.versions.length > 5"
            variant="text"
            size="small"
            :prepend-icon="detail.showAllVersions ? 'mdi-chevron-up' : 'mdi-chevron-down'"
            @click="detail.showAllVersions = !detail.showAllVersions"
          >
            {{ detail.showAllVersions ? 'Show fewer' : `Show all ${detail.versions.length}` }}
          </v-btn>
        </template>
      </v-card-text>
      <v-card-actions>
        <v-btn
          v-if="detail.project"
          variant="text"
          prepend-icon="mdi-open-in-new"
          @click="openLink(`https://modrinth.com/${detail.project.projectType}/${detail.project.slug}`)"
        >
          Open on Modrinth
        </v-btn>
        <v-spacer />
        <v-btn variant="text" @click="detail.open = false">Close</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.search-field {
  flex: 1 1 320px;
  max-width: 460px;
}

.sort-field {
  flex: 0 1 190px;
}

.instance-field {
  flex: 0 1 320px;
  min-width: 240px;
}

.result-card__description {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.result-card--openable {
  cursor: pointer;
}

.result-card__icon {
  flex: 0 0 auto;
}

.result-card__icon-fallback {
  width: 52px;
  height: 52px;
  font-size: 24px;
}

.body {
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  font-size: 13px;
  font-family: inherit;
  padding: 10px;
  border-radius: 8px;
  background: rgba(var(--v-theme-on-surface), 0.06);
}
</style>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { ModrinthHit, ModrinthProjectDetail, ModrinthVersion, ResourceKind } from '@shared/types'
import { useLauncher } from '@renderer/composables/useLauncher'

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
  modrinthSearch,
  modrinthVersions,
  modrinthProject,
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
const query = ref('')
const sort = ref('downloads')
const hits = ref<ModrinthHit[]>([])
const totalHits = ref(0)
const offset = ref(0)
const loading = ref(false)
const busy = ref(false)
const note = ref<string | null>(null)
const noteType = ref<'info' | 'success' | 'warning'>('info')

const detail = reactive({
  open: false,
  loading: false,
  project: null as ModrinthProjectDetail | null,
  versions: [] as ModrinthVersion[],
  showAllVersions: false
})

const activeTab = computed(() => tabs.find((tab) => tab.value === kind.value) ?? tabs[0])
const kindLabel = computed(() => activeTab.value.title)
/** A modpack is installed as an instance, so it needs no instance of its own. */
const requiresInstance = computed(() => kind.value !== 'modpack')
const readyToInstall = computed(() => !requiresInstance.value || selectedInstance.value !== null)

function report(message: string, type: 'info' | 'success' | 'warning' = 'info'): void {
  noteType.value = type
  note.value = message
}

/**
 * The catalogue, filtered by the selected instance when there is one: without a
 * search word the page still shows what is most downloaded for it, which is what
 * makes opening the store anything other than an empty box.
 */
async function search(reset = true): Promise<void> {
  loading.value = true
  if (reset) offset.value = 0
  try {
    const result = await modrinthSearch({
      query: query.value.trim(),
      kind: kind.value,
      sort: sort.value,
      limit: PAGE_SIZE,
      offset: offset.value
    })
    hits.value = reset ? result.hits : [...hits.value, ...result.hits]
    totalHits.value = result.totalHits
  } catch {
    // Reported in the snackbar, and kept as a note here so the page says so.
    report('Modrinth could not be reached. Check the connection and try again.', 'warning')
  } finally {
    loading.value = false
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
    // What fits the instance changes with it, so the list is looked up again.
    void search()
  }
)

onMounted(() => {
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
          v-if="requiresInstance && selectedInstance"
          class="state-chip"
          color="primary"
          variant="tonal"
          prepend-icon="mdi-cube-outline"
        >
          fits {{ selectedInstance.name }}
        </v-chip>
        <v-chip v-else-if="requiresInstance" class="state-chip" color="warning" variant="tonal" prepend-icon="mdi-cube-off-outline">
          no instance selected
        </v-chip>
      </div>

      <v-tabs :model-value="kind" class="mt-4" density="comfortable" @update:model-value="switchTab">
        <v-tab v-for="tab in tabs" :key="tab.value" :value="tab.value" :prepend-icon="tab.icon">
          {{ tab.title }}
        </v-tab>
      </v-tabs>

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

      <v-alert
        v-if="requiresInstance && !selectedInstance"
        type="warning"
        variant="tonal"
        class="mt-4"
        prepend-icon="mdi-cube-off-outline"
      >
        Nothing is selected, so what you install has nowhere to go. Pick an instance, or install a
        modpack, which arrives as its own instance.
        <template #append>
          <v-btn size="small" variant="text" @click="router.push('/instances')">Choose an instance</v-btn>
        </template>
      </v-alert>
      <v-alert v-else-if="selectedInstance && requiresInstance" type="info" variant="tonal" class="mt-4">
        Listing what fits <strong>{{ selectedInstance.name }}</strong>
        ({{ selectedInstance.versionId ?? 'no version' }}<template
          v-if="selectedInstance.loader && selectedInstance.loader !== 'vanilla'"
        >, {{ selectedInstance.loader }}</template
        >). Files are installed into that instance.
      </v-alert>

      <v-alert v-if="note" :type="noteType" variant="tonal" class="mt-4">{{ note }}</v-alert>
    </v-card-text>
  </v-card>

  <div v-if="loading && hits.length === 0" class="d-flex justify-center py-10">
    <v-progress-circular indeterminate color="primary" />
  </div>

  <v-row v-else-if="hits.length > 0" class="app-stagger">
    <v-col v-for="hit in hits" :key="hit.projectId" cols="12" md="6" lg="4">
      <v-card class="h-100 d-flex flex-column result-card">
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
        <v-card-actions>
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

.result-card__description {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
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

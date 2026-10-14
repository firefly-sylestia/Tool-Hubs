<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { ModrinthHit, ModrinthProjectDetail, ModrinthVersion, ResourceKind } from '@shared/types'
import { useLauncher } from '@renderer/composables/useLauncher'

/**
 * The store: Modrinth's catalogue, searchable, with an install button.
 *
 * It is a page of its own rather than a dialog inside an instance because
 * browsing is what you do *before* choosing where something goes — the tabs cover
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

const tabs: { title: string; value: StoreKind }[] = [
  { title: 'Mods', value: 'mod' },
  { title: 'Resource packs', value: 'resourcepack' },
  { title: 'Shaders', value: 'shaderpack' },
  { title: 'Modpacks', value: 'modpack' }
]

const sorts = [
  { title: 'Relevance', value: 'relevance' },
  { title: 'Downloads', value: 'downloads' },
  { title: 'Followers', value: 'follows' },
  { title: 'Newest', value: 'newest' },
  { title: 'Recently updated', value: 'updated' }
]

const PAGE_SIZE = 20

const kind = ref<StoreKind>('mod')
const query = ref('')
const sort = ref('relevance')
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

const kindLabel = computed(() => tabs.find((tab) => tab.value === kind.value)?.title ?? 'Mods')
const requiresInstance = computed(() => kind.value !== 'modpack')
const canSearch = computed(() => requiresInstance.value ? selectedInstance.value !== null : true)

function report(message: string, type: 'info' | 'success' | 'warning' = 'info'): void {
  noteType.value = type
  note.value = message
}

async function search(reset = true): Promise<void> {
  if (!canSearch.value) {
    report('Modpacks are installed as new instances; for mods, packs and shaders pick an instance first.', 'warning')
    return
  }
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
    // Reported in the snackbar.
  } finally {
    loading.value = false
  }
}

async function loadMore(): Promise<void> {
  offset.value += PAGE_SIZE
  await search(false)
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
  if (!instance || !projectId) return
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

onMounted(() => {
  void search()
})
</script>

<template>
  <v-card>
    <v-card-title class="d-flex align-center">
      Modrinth
      <v-spacer />
      <span class="text-medium-emphasis text-body-2">
        {{ instances.length }} instance(s) · {{ selectedInstance?.name ?? 'none selected' }}
      </span>
    </v-card-title>
    <v-card-text>
      <div class="d-flex ga-2 align-center flex-wrap">
        <v-text-field
          v-model="query"
          label="Search Modrinth"
          hide-details
          clearable
          style="max-width: 420px"
          @keyup.enter="search()"
        />
        <v-select
          v-model="sort"
          :items="sorts"
          label="Sort by"
          hide-details
          density="compact"
          style="max-width: 200px"
          @update:model-value="search()"
        />
        <v-btn color="primary" :loading="loading" @click="search()">Search</v-btn>
      </div>

      <v-tabs v-model="kind" class="mt-4" @update:model-value="search()">
        <v-tab v-for="tab in tabs" :key="tab.value" :value="tab.value">{{ tab.title }}</v-tab>
      </v-tabs>

      <p class="text-medium-emphasis mt-4">
        <template v-if="kind === 'modpack'">
          A modpack is installed as a new instance: its Minecraft version, its loader and its own files.
        </template>
        <template v-else-if="selectedInstance">
          Showing what fits <strong>{{ selectedInstance.name }}</strong> —
          {{ selectedInstance.versionId ?? 'no version' }}<template v-if="selectedInstance.loader && selectedInstance.loader !== 'vanilla'">
            on {{ selectedInstance.loader }}</template
          >. Files go into that instance's {{ kindLabel.toLowerCase() }} folder.
        </template>
        <template v-else>
          No instance is selected. Pick one on <strong>Instances</strong> to install into, or use the
          Modpacks tab, which creates one.
        </template>
      </p>

      <v-alert v-if="note" :type="noteType" variant="tonal" class="mt-4">{{ note }}</v-alert>
    </v-card-text>
  </v-card>

  <v-row class="mt-4 app-stagger">
    <v-col v-for="hit in hits" :key="hit.projectId" cols="12" md="6" lg="4">
      <v-card class="h-100 d-flex flex-column">
        <v-card-text class="flex-grow-1">
          <div class="d-flex ga-3">
            <v-avatar v-if="hit.iconUrl" rounded size="56">
              <v-img :src="hit.iconUrl" :alt="hit.title" />
            </v-avatar>
            <div>
              <h3 class="text-h6">{{ hit.title }}</h3>
              <p class="text-medium-emphasis mb-1 text-body-2">
                {{ downloads(hit.downloads) }} downloads · {{ downloads(hit.follows) }} followers
              </p>
            </div>
          </div>
          <p class="mt-3 mb-2">{{ hit.description }}</p>
          <div class="d-flex flex-wrap ga-1">
            <v-chip v-for="loader in hit.loaders" :key="loader" size="small" variant="tonal">
              {{ loader }}
            </v-chip>
            <v-chip v-for="category in hit.categories.slice(0, 3)" :key="category" size="small" variant="text">
              {{ category }}
            </v-chip>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-btn size="small" variant="text" @click="openDetail(hit)">Details</v-btn>
          <v-spacer />
          <v-btn
            size="small"
            color="primary"
            :loading="busy"
            :disabled="requiresInstance && !selectedInstance"
            @click="install(hit)"
          >
            Install
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-col>
  </v-row>

  <div v-if="!loading && hits.length === 0" class="text-center mt-6">
    <p class="text-medium-emphasis">
      Nothing to show. Search for something, or check the instance this filters by.
    </p>
  </div>

  <div v-if="hits.length > 0 && hits.length < totalHits" class="text-center mt-4">
    <v-btn :loading="loading" variant="tonal" @click="loadMore">
      Load more ({{ hits.length }} of {{ totalHits }})
    </v-btn>
  </div>

  <v-dialog v-model="detail.open" max-width="900" scrollable>
    <v-card>
      <v-card-title class="d-flex align-center ga-3">
        <v-avatar v-if="detail.project?.iconUrl" rounded size="40">
          <v-img :src="detail.project.iconUrl" />
        </v-avatar>
        <span>{{ detail.project?.title ?? 'Project' }}</span>
        <v-spacer />
        <span class="text-body-2 text-medium-emphasis">{{ detail.project?.projectType }}</span>
      </v-card-title>
      <v-card-text>
        <v-progress-linear v-if="detail.loading" indeterminate height="3" class="mb-4" />

        <template v-if="detail.project">
          <p class="text-medium-emphasis">
            {{ downloads(detail.project.downloads) }} downloads ·
            {{ downloads(detail.project.followers) }} followers ·
            updated {{ detail.project.updated.slice(0, 10) }}
            <template v-if="detail.project.license"> · {{ detail.project.license }}</template>
          </p>
          <p v-if="detail.project.clientSide || detail.project.serverSide" class="text-medium-emphasis text-body-2">
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
              @click="openLink(link.url)"
            >
              {{ link.label }}
            </v-btn>
          </div>

          <v-divider class="my-4" />

          <p class="mb-2">
            <strong>Versions</strong> — the newest that fits is listed first. Required dependencies are
            downloaded with the file.
          </p>
          <v-btn
            v-if="detail.versions.length > 0"
            color="primary"
            class="mb-2"
            :loading="busy"
            :disabled="requiresInstance && !selectedInstance"
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
              <template #append>
                <v-btn
                  size="small"
                  :loading="busy"
                  :disabled="requiresInstance && !selectedInstance"
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
            @click="detail.showAllVersions = !detail.showAllVersions"
          >
            {{ detail.showAllVersions ? 'Show fewer' : `Show all ${detail.versions.length}` }}
          </v-btn>

          <v-btn variant="text" size="small" @click="router.push('/instances')">
            Manage instances
          </v-btn>
        </template>
      </v-card-text>
      <v-card-actions>
        <v-btn
          v-if="detail.project"
          variant="text"
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
.body {
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  font-size: 13px;
  font-family: inherit;
}
</style>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type {
  GameInstance,
  InstallLoader,
  InstanceGameOptions,
  InstanceResources,
  InstanceSaves,
  JavaStatus,
  LoaderOption,
  ModpackSummary,
  ModrinthHit,
  ModrinthVersion,
  RemoteVersion,
  ResourceEntry,
  ResourceKind,
  SaveBackup,
  WorldInfo
} from '@shared/types'
import { useLauncher, type DetectedJava } from '@renderer/composables/useLauncher'
import { parseJvmArgs } from '@shared/jvm-args'

const {
  instances,
  installations,
  selectedInstance,
  tasks,
  createInstance,
  updateInstance,
  selectInstance,
  removeInstance,
  importInstanceFolder,
  resources,
  setResourceEnabled,
  addResources,
  removeResource,
  gameOptions,
  saveGameOptions,
  inspectModpack,
  importModpack,
  exportPack,
  modrinthSearch,
  modrinthVersions,
  installFromModrinth,
  javaStatus,
  instanceSaves,
  backupWorlds,
  restoreWorldBackup,
  exportWorld,
  importWorld,
  pickFolder,
  pickFiles,
  openPath,
  rescan,
  remoteVersions,
  loaderVersions,
  installVersion,
  settings
} = useLauncher()

const busy = ref(false)
const loadingVersions = ref(false)

/**
 * The page is four cards deep and each one is a whole subject, so they are put
 * behind tabs rather than stacked: the version it runs, what is in it, its
 * worlds and its options.
 *
 * The open tab lives in the query string, so it survives navigating away and
 * can be linked: `#/instances?tab=worlds` is the worlds of the selected
 * instance, which is the one thing worth pointing somebody at.
 */
const TABS = ['version', 'content', 'worlds', 'options'] as const
type InstanceTab = (typeof TABS)[number]

function tabFromQuery(value: unknown): InstanceTab {
  const name = typeof value === 'string' ? value : ''
  return ((TABS as readonly string[]).includes(name) ? name : 'version') as InstanceTab
}

const route = useRoute()
const router = useRouter()
const tab = ref<InstanceTab>(tabFromQuery(route.query.tab))

watch(
  () => route.query.tab,
  (value) => {
    const next = tabFromQuery(value)
    if (next !== tab.value) tab.value = next
  }
)

watch(tab, (value) => {
  const wanted = value === 'version' ? undefined : value
  if (route.query.tab === wanted) return
  void router.replace(wanted ? { query: { tab: wanted } } : { query: {} })
})
const note = ref<string | null>(null)
const noteType = ref<'info' | 'success' | 'warning'>('info')

/** Install form. */
const versionItems = ref<RemoteVersion[]>([])
const versionFilter = ref<'release' | 'snapshot' | 'all'>('release')
const selectedVersion = ref<string | null>(null)
const loader = ref<InstallLoader>('vanilla')
const loaderItems = ref<LoaderOption[]>([])
const selectedLoader = ref<string | null>(null)

/** Instance form. */
const showNewInstance = ref(false)
const newInstanceName = ref('')

/** Resources. */
const instanceResources = ref<InstanceResources | null>(null)
const resourceTab = ref<ResourceKind>('mod')
const tabKinds: { title: string; value: ResourceKind }[] = [
  { title: 'Mods', value: 'mod' },
  { title: 'Resource packs', value: 'resourcepack' },
  { title: 'Shader packs', value: 'shaderpack' }
]

/** Worlds and their backups, read from the instance's own `saves/` folder. */
const savesInfo = ref<InstanceSaves | null>(null)
const worldsBusy = ref(false)

/** Modpack.
 *
 * Importing a pack creates an instance; exporting one writes this instance out as
 * a `.mrpack` somebody else can import.
 */
const modpackSummary = ref<ModpackSummary | null>(null)
const modpackPath = ref<string | null>(null)
const showModpack = ref(false)
const exportForm = reactive({ open: false, busy: false, name: '', versionId: '' })

/** Modrinth browsing, scoped to the instance and the tab that is open. */
const browse = reactive({
  open: false,
  query: '',
  loading: false,
  searched: false,
  hits: [] as ModrinthHit[],
  totalHits: 0,
  selected: null as ModrinthHit | null,
  versions: [] as ModrinthVersion[],
  versionsLoading: false,
  installing: false
})

const loaderChoices = [
  { title: 'Vanilla, the game on its own', value: 'vanilla' },
  { title: 'Fabric', value: 'fabric' },
  { title: 'Quilt', value: 'quilt' }
]

const visibleVersions = computed(() =>
  versionFilter.value === 'all'
    ? versionItems.value
    : versionItems.value.filter((version) => version.type === versionFilter.value)
)

const runningTask = computed(() => tasks.value.find((task) => task.state === 'running') ?? null)

/** The state of the instance being looked at, in the same words the Play page uses. */
const instanceState = computed(() => {
  const instance = selectedInstance.value
  if (!instance) return { label: 'No instance', icon: 'mdi-plus-circle-outline', color: 'info' }
  if (!instance.versionId) {
    return { label: 'Needs a version', icon: 'mdi-alert-circle-outline', color: 'warning' }
  }
  return { label: 'Ready to play', icon: 'mdi-check-circle-outline', color: 'success' }
})
const entries = computed<ResourceEntry[]>(() => instanceResources.value?.entries[resourceTab.value] ?? [])
const resourceFolder = computed(() => instanceResources.value?.folders[resourceTab.value] ?? '')
const entryWarnings = computed(() =>
  entries.value.flatMap((entry) =>
    (entry.metadata?.warnings ?? []).map((warning) => `${entry.title}: ${warning}`)
  )
)
/** The instance's own options.txt, and its JVM arguments. */
const options = ref<InstanceGameOptions | null>(null)
const optionForm = reactive<Record<string, string>>({})
/** One argument per entry, so each one can be read and removed on its own. */
const jvmArgs = ref<string[]>([])
const newJvmArg = ref('')
const savingOptions = ref(false)
const extraKey = ref('')
const extraValue = ref('')
const showEveryOption = ref(false)
const optionSearch = ref('')

/**
 * Java and memory, which belong to the instance rather than to the launcher.
 *
 * One setting for everything cannot serve both a 1.12.2 instance that only
 * starts on Java 8 and a 1.21 one that wants 21, and the memory a heavy modpack
 * needs is not the memory a vanilla instance should reserve.
 */
const javaInfo = ref<JavaStatus | null>(null)
const detectedJava = ref<DetectedJava[]>([])
const instanceJava = ref('')
/** A string, so "empty" can mean the launcher's default rather than 0. */
const instanceMemory = ref('')
const savingRuntime = ref(false)

/** Automatic, the runtimes this machine has, and one set by hand. */
const javaItems = computed(() => {
  const items = [
    { value: '', title: 'Automatic', subtitle: 'the runtime this version asks for' },
    ...detectedJava.value.map((java) => ({
      value: java.path,
      title: `Java ${java.major ?? 'unknown'}`,
      subtitle: `${java.source} · ${java.path}`
    }))
  ]
  if (instanceJava.value !== '' && !items.some((item) => item.value === instanceJava.value)) {
    items.push({ value: instanceJava.value, title: 'Set by hand', subtitle: instanceJava.value })
  }
  return items
})

/** The file can hold hundreds of keys, so the raw list is searchable. */
const filteredEntries = computed(() => {
  const needle = optionSearch.value.trim().toLowerCase()
  const entries = options.value?.entries ?? []
  if (needle === '') return entries
  return entries.filter(
    (entry) =>
      entry.key.toLowerCase().includes(needle) || entry.value.toLowerCase().includes(needle)
  )
})

/**
 * The icon a setting leads with, by what kind of thing it is.
 *
 * Not a taxonomy of Minecraft settings, just enough that a list of nine rows
 * does not read as nine identical ones.
 */
function optionIcon(key: string): string {
  if (key === 'fullscreen') return 'mdi-monitor'
  if (key.startsWith('graphics')) return 'mdi-auto-fix'
  if (key.toLowerCase().includes('fps')) return 'mdi-speedometer'
  if (key.startsWith('render') || key.startsWith('simulation')) return 'mdi-image-size-select-large'
  if (key.toLowerCase().includes('vsync')) return 'mdi-sync'
  if (key.toLowerCase().includes('gui')) return 'mdi-grid-large'
  return 'mdi-tune-variant'
}

const tabKindLabel = computed(
  () => tabKinds.find((kind) => kind.value === resourceTab.value)?.title ?? 'mods'
)

/** A mod reads as its real name and version, not the file name someone chose. */
function entryTitle(entry: ResourceEntry): string {
  const metadata = entry.metadata
  if (!metadata?.name) return entry.title
  return `${metadata.name}${metadata.version ? ` ${metadata.version}` : ''}`
}

/** The icon a resource row leads with, so a folder of files reads by kind. */
const KIND_ICONS: Record<ResourceKind, string> = {
  mod: 'mdi-puzzle-outline',
  resourcepack: 'mdi-palette-outline',
  shaderpack: 'mdi-shimmer'
}

function kindIcon(kind: ResourceKind): string {
  return KIND_ICONS[kind] ?? 'mdi-file-outline'
}

function entrySubtitle(entry: ResourceEntry): string {
  const parts: string[] = [entry.enabled ? 'enabled' : 'disabled']
  const metadata = entry.metadata
  if (metadata) {
    parts.push(`${metadata.loader}${metadata.id ? ` · ${metadata.id}` : ''}`)
  } else {
    parts.push('no readable metadata')
  }
  parts.push(`${Math.round(entry.size / 1024)} KB`)
  return parts.join(' · ')
}

const canInstall = computed(
  () =>
    selectedInstance.value !== null &&
    selectedVersion.value !== null &&
    !busy.value &&
    (loader.value === 'vanilla' || selectedLoader.value !== null)
)

function report(message: string, type: 'info' | 'success' | 'warning' = 'info'): void {
  noteType.value = type
  note.value = message
}

async function loadResources(): Promise<void> {
  if (!selectedInstance.value) {
    instanceResources.value = null
    return
  }
  try {
    instanceResources.value = await resources(selectedInstance.value.id)
  } catch {
    // Reported in the snackbar.
  }
}

async function loadSaves(): Promise<void> {
  if (!selectedInstance.value) {
    savesInfo.value = null
    return
  }
  try {
    savesInfo.value = await instanceSaves(selectedInstance.value.id)
  } catch {
    // Reported in the snackbar.
  }
}

function worldSubtitle(world: WorldInfo): string {
  if (world.problem) return world.problem
  const parts: string[] = []
  if (world.version) parts.push(world.version)
  if (world.gameMode) parts.push(world.hardcore ? `${world.gameMode}, hardcore` : world.gameMode)
  parts.push(world.lastPlayed ? `last played ${world.lastPlayed.slice(0, 10)}` : 'never played')
  parts.push(`${(world.bytes / 1048576).toFixed(1)} MB`)
  return parts.join(' · ')
}

async function backupWorldsNow(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  worldsBusy.value = true
  try {
    const backup = await backupWorlds('manual', instance.id)
    await loadSaves()
    report(`Backed the worlds up to ${backup.name} (${(backup.bytes / 1048576).toFixed(1)} MB).`, 'success')
  } catch {
    // Reported in the snackbar.
  } finally {
    worldsBusy.value = false
  }
}

/**
 * Puts a backup back into `saves/`.
 *
 * Nothing is overwritten: the worlds it holds come back beside whatever is in
 * the folder now, under their own names or with a number on the end.
 */
async function restoreBackupNow(backup: SaveBackup): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  worldsBusy.value = true
  try {
    const result = await restoreWorldBackup({ instanceId: instance.id, path: backup.path })
    await loadSaves()
    const folders = result.worlds.map((world) => world.folder).join(', ')
    report(
      result.worlds.length === 1
        ? `Restored ${folders} from ${backup.name}. The world that was already there is untouched.`
        : `Restored ${result.worlds.length} worlds from ${backup.name}: ${folders}.`,
      'success'
    )
  } catch {
    // Reported in the snackbar.
  } finally {
    worldsBusy.value = false
  }
}

async function exportWorldNow(world: WorldInfo): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  const picked = await pickFolder()
  if (!picked.path) return
  worldsBusy.value = true
  try {
    const result = await exportWorld({ instanceId: instance.id, folder: world.folder, destDir: picked.path })
    report(`Exported ${world.name} to ${result.path} (${result.files} files).`, 'success')
  } catch {
    // Reported in the snackbar.
  } finally {
    worldsBusy.value = false
  }
}

async function importWorldNow(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  // A folder first: an unpacked world is the common case, and a zip of one is
  // what people get sent. Either can be picked from the same dialog.
  const picked = await pickFolder()
  if (!picked.path) return
  worldsBusy.value = true
  try {
    const result = await importWorld({ instanceId: instance.id, source: picked.path })
    await loadSaves()
    report(`Imported ${result.folder} into ${instance.name}: ${result.files} file(s).`, 'success')
  } catch {
    // Reported in the snackbar.
  } finally {
    worldsBusy.value = false
  }
}

async function importWorldZip(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  const picked = await pickFiles('world')
  const source = picked.paths[0]
  if (!source) return
  worldsBusy.value = true
  try {
    const result = await importWorld({ instanceId: instance.id, source })
    await loadSaves()
    report(`Imported ${result.folder} into ${instance.name}: ${result.files} file(s).`, 'success')
  } catch {
    // Reported in the snackbar.
  } finally {
    worldsBusy.value = false
  }
}

async function loadVersions(refresh = false): Promise<void> {
  loadingVersions.value = true
  try {
    versionItems.value = await remoteVersions(refresh)
    if (!selectedVersion.value && versionItems.value.length > 0) {
      // The newest *offered* version, which is a release by default: the first
      // entry of the manifest is usually a snapshot, and preselecting something
      // the filter hides would look like an empty picker.
      const offered = versionItems.value.filter(
        (version) => versionFilter.value === 'all' || version.type === versionFilter.value
      )
      selectedVersion.value = (offered[0] ?? versionItems.value[0]).id
    }
  } catch {
    report('Could not reach Mojang to list versions. Check the connection and try again.', 'warning')
  } finally {
    loadingVersions.value = false
  }
}

async function onLoaderChange(): Promise<void> {
  loaderItems.value = []
  selectedLoader.value = null
  if (loader.value === 'vanilla') return
  try {
    loaderItems.value = await loaderVersions(loader.value)
    // Newest stable first: a loader built the same day often has no release yet.
    const stable = loaderItems.value.filter((item) => item.stable)
    selectedLoader.value = (stable[0] ?? loaderItems.value[0])?.version ?? null
  } catch {
    report(`Could not list ${loader.value} loader versions.`, 'warning')
  }
}

async function createInstanceFromName(): Promise<void> {
  const name = newInstanceName.value.trim()
  if (name === '') return
  busy.value = true
  try {
    const created = await createInstance({ name })
    newInstanceName.value = ''
    showNewInstance.value = false
    report(`Created ${created.name} at ${created.dir}. Install a version to start playing.`, 'success')
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function importFolder(): Promise<void> {
  const picked = await pickFolder()
  if (!picked.path) return
  busy.value = true
  try {
    const result = await importInstanceFolder(picked.path)
    report(result.message, 'success')
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function removeSelected(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  busy.value = true
  try {
    const result = await removeInstance(instance.id, false)
    report(`Removed ${result.removed} from the launcher; its files are still at ${result.dir}.`, 'success')
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function selectInstanceById(id: string): Promise<void> {
  await selectInstance(id)
  await rescan()
  await loadResources()
}

async function install(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance || !selectedVersion.value) return
  busy.value = true
  note.value = null
  try {
    const result = await installVersion({
      versionId: selectedVersion.value,
      loader: loader.value,
      loaderVersion: loader.value === 'vanilla' ? null : selectedLoader.value,
      instanceId: instance.id
    })
    // The instance plays what was installed, so the next step is Play.
    await updateInstance(instance.id, {
      versionId: result.targetId,
      loader: loader.value,
      loaderVersion: loader.value === 'vanilla' ? null : selectedLoader.value
    })
    await rescan()
    report(
      `Installed ${result.targetId}: ${result.files} files checked, ${result.reused} already ` +
        `present, ${result.nativesExtracted} native libraries unpacked.`,
      'success'
    )
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function loadOptions(): Promise<void> {
  if (!selectedInstance.value) {
    options.value = null
    return
  }
  try {
    const loaded = await gameOptions(selectedInstance.value.id)
    options.value = loaded
    for (const key of Object.keys(optionForm)) delete optionForm[key]
    for (const field of loaded.fields) optionForm[field.key] = loaded.quick[field.key] ?? ''
    jvmArgs.value = [...loaded.jvmArgs]
  } catch {
    // Reported in the snackbar.
  }
}

/** What this instance runs now, and which runtimes are available to choose. */
async function loadRuntime(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) {
    javaInfo.value = null
    detectedJava.value = []
    instanceJava.value = ''
    instanceMemory.value = ''
    return
  }
  instanceJava.value = instance.javaPath ?? ''
  instanceMemory.value = instance.maxMemoryMb === null ? '' : String(instance.maxMemoryMb)
  try {
    javaInfo.value = await javaStatus(instance.id)
    detectedJava.value = javaInfo.value.detected
  } catch {
    // Reported in the snackbar.
  }
}

/**
 * Saves this instance's own Java and memory.
 *
 * An empty control is not a zero: it is "whatever the launcher is set to",
 * which is why both are sent as null rather than as an empty string or a 0.
 */
async function saveRuntime(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  savingRuntime.value = true
  try {
    const memory = instanceMemory.value.trim()
    const java = instanceJava.value.trim()
    await updateInstance(instance.id, {
      javaPath: java === '' ? null : java,
      maxMemoryMb: memory === '' ? null : Number(memory)
    })
    report(
      memory === ''
        ? `Saved Java for ${instance.name}; its memory follows the launcher's default.`
        : `Saved Java and a ${memory} MB limit for ${instance.name}.`,
      'success'
    )
    await loadRuntime()
  } catch {
    // Reported in the snackbar.
  } finally {
    savingRuntime.value = false
  }
}

async function saveOptions(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  savingOptions.value = true
  try {
    // Only what actually changed is written, and a cleared field removes its
    // key so the game falls back to its own default.
    const changes: Record<string, string | null> = {}
    for (const field of options.value?.fields ?? []) {
      const next = (optionForm[field.key] ?? '').trim()
      const before = options.value?.quick[field.key] ?? ''
      if (next === before) continue
      changes[field.key] = next === '' ? null : next
    }

    const saved = await saveGameOptions({
      instanceId: instance.id,
      changes,
      jvmArgs: jvmArgs.value
    })
    options.value = saved
    jvmArgs.value = [...saved.jvmArgs]
    report(
      Object.keys(changes).length > 0
        ? `Saved ${Object.keys(changes).length} option(s) and the JVM arguments for ${instance.name}.`
        : `Saved the JVM arguments for ${instance.name}.`,
      'success'
    )
  } catch {
    // Reported in the snackbar.
  } finally {
    savingOptions.value = false
  }
}

/** Sets one arbitrary key, which is how a less common option is changed. */
async function setExtraOption(): Promise<void> {
  const instance = selectedInstance.value
  const key = extraKey.value.trim()
  if (!instance || key === '') return
  try {
    options.value = await saveGameOptions({
      instanceId: instance.id,
      changes: { [key]: extraValue.value.trim() === '' ? null : extraValue.value.trim() }
    })
    optionForm[key] = options.value.quick[key] ?? extraValue.value.trim()
    extraKey.value = ''
    extraValue.value = ''
    report(`Set ${key} in options.txt.`, 'success')
  } catch {
    // Reported in the snackbar.
  }
}

/** Accepts one or several, quoted the way a shell would read them. */
function addJvmArg(): void {
  const parsed = parseJvmArgs(newJvmArg.value)
  if (parsed.length === 0) return
  jvmArgs.value = [...jvmArgs.value, ...parsed]
  newJvmArg.value = ''
}

function removeJvmArg(index: number): void {
  jvmArgs.value = jvmArgs.value.filter((_arg, position) => position !== index)
}

async function removeOption(key: string): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  try {
    options.value = await saveGameOptions({ instanceId: instance.id, changes: { [key]: null } })
    optionForm[key] = ''
    report(`Removed ${key}; the game will use its own default.`, 'success')
  } catch {
    // Reported in the snackbar.
  }
}

async function addResourceFiles(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  const picked = await pickFiles(resourceTab.value)
  if (picked.paths.length === 0) return
  busy.value = true
  try {
    const result = await addResources({
      instanceId: instance.id,
      kind: resourceTab.value,
      paths: picked.paths
    })
    instanceResources.value = result.resources
    if (result.added.length > 0) report(`Added ${result.added.join(', ')}.`, 'success')
    if (result.skipped.length > 0) report(`Skipped: ${result.skipped.join(' ')}`, 'warning')
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

async function toggleResource(entry: ResourceEntry): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  try {
    instanceResources.value = await setResourceEnabled({
      instanceId: instance.id,
      kind: entry.kind,
      name: entry.name,
      enabled: !entry.enabled
    })
  } catch {
    // Reported in the snackbar.
  }
}

async function deleteResource(entry: ResourceEntry): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  try {
    instanceResources.value = await removeResource({
      instanceId: instance.id,
      kind: entry.kind,
      name: entry.name
    })
  } catch {
    // Reported in the snackbar.
  }
}

async function openResourceFolder(): Promise<void> {
  if (!selectedInstance.value) return
  // Make sure the folder exists before handing it to the OS.
  instanceResources.value = await resources(selectedInstance.value.id)
  await openPath(resourceFolder.value)
}

/** Opened without a search word it lists the catalogue, the way the store does. */
function openBrowse(): void {
  browse.open = true
  browse.hits = []
  browse.selected = null
  browse.versions = []
  browse.searched = false
  void runSearch()
}

async function runSearch(): Promise<void> {
  if (!selectedInstance.value) return
  browse.loading = true
  try {
    const result = await modrinthSearch({
      query: browse.query.trim(),
      kind: resourceTab.value,
      limit: 20
    })
    browse.hits = result.hits
    browse.totalHits = result.totalHits
    browse.searched = true
    browse.selected = null
    browse.versions = []
  } catch {
    // Reported in the snackbar.
  } finally {
    browse.loading = false
  }
}

async function chooseHit(hit: ModrinthHit): Promise<void> {
  browse.selected = hit
  browse.versions = []
  browse.versionsLoading = true
  try {
    browse.versions = await modrinthVersions(hit.projectId)
  } catch {
    // Reported in the snackbar.
  } finally {
    browse.versionsLoading = false
  }
}

async function installFromBrowse(versionId?: string): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  browse.installing = true
  try {
    const result = await installFromModrinth({
      projectId: browse.selected?.projectId ?? null,
      versionId: versionId ?? null,
      kind: resourceTab.value,
      instanceId: instance.id
    })
    const dependencies = result.installed.filter((item) => item.dependency)
    report(
      `Installed ${result.requested}` +
        (dependencies.length > 0 ? ` and ${dependencies.length} required dependency file(s)` : '') +
        ` into ${tabKindLabel.value.toLowerCase()}.` +
        (result.warnings.length > 0 ? ` ${result.warnings.join(' ')}` : ''),
      result.warnings.length > 0 ? 'warning' : 'success'
    )
    browse.open = false
    await loadResources()
  } catch {
    // Reported in the snackbar.
  } finally {
    browse.installing = false
  }
}

async function chooseModpack(): Promise<void> {
  const picked = await pickFiles('modpack')
  const path = picked.paths[0]
  if (!path) return
  busy.value = true
  try {
    const summary = await inspectModpack(path)
    modpackSummary.value = summary
    modpackPath.value = path
    showModpack.value = true
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

/** Opens the export form, named after the instance it would write. */
function openExport(): void {
  const instance = selectedInstance.value
  if (!instance) return
  exportForm.open = true
  exportForm.name = instance.name
  exportForm.versionId = ''
}

/**
 * Writes the instance out as a pack, into a folder the user picks.
 *
 * The folder is chosen after the form, so a canceled pick leaves nothing behind
 * and the pack never lands somewhere nobody named.
 */
async function runExport(): Promise<void> {
  const instance = selectedInstance.value
  if (!instance) return
  exportForm.open = false
  const picked = await pickFolder()
  if (!picked.path) return
  exportForm.busy = true
  try {
    const result = await exportPack({
      instanceId: instance.id,
      destDir: picked.path,
      name: exportForm.name.trim() === '' ? undefined : exportForm.name.trim(),
      versionId: exportForm.versionId.trim() === '' ? undefined : exportForm.versionId.trim()
    })
    report(
      `Wrote ${result.name} ${result.versionId} to ${result.path}: ${result.files} file(s), ` +
        `${(result.bytes / 1048576).toFixed(1)} MB.`,
      'success'
    )
  } catch {
    // Reported in the snackbar, and in the log while the task runs.
  } finally {
    exportForm.busy = false
  }
}

async function installModpack(): Promise<void> {
  const path = modpackPath.value
  if (!path) return
  showModpack.value = false
  busy.value = true
  try {
    const result = await importModpack(path)
    const unresolved =
      result.unresolved.length > 0
        ? ` ${result.unresolved.length} file(s) need CurseForge's API and were not downloaded; the log lists them.`
        : ''
    report(
      `Imported ${result.summary.name} as ${result.instance.name}: ${result.files} files downloaded, ` +
        `${result.overrides} unpacked from the pack.${unresolved}`,
      result.unresolved.length > 0 ? 'warning' : 'success'
    )
    await selectInstance(result.instance.id)
    await loadResources()
  } catch {
    // Reported in the snackbar.
  } finally {
    busy.value = false
  }
}

function describe(instance: GameInstance): string {
  const version = instance.versionId ?? 'no version yet'
  const loaderLabel = instance.loader && instance.loader !== 'vanilla' ? ` · ${instance.loader}` : ''
  return `${version}${loaderLabel} · ${instance.source}`
}

function instanceProps(instance: GameInstance): { subtitle: string } {
  return { subtitle: describe(instance) }
}

watch(
  () => selectedInstance.value?.id ?? null,
  () => {
    void loadResources()
    void loadOptions()
    void loadRuntime()
    void loadSaves()
  }
)

onMounted(async () => {
  await loadResources()
  await loadOptions()
  await loadRuntime()
  await loadSaves()
  await loadVersions()
})
</script>

<template>
  <!-- One instance at a time: what it is, what is happening to it, and the set
       of actions that change it. -->
  <v-card class="hero-card">
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-cube-outline" size="small" class="mr-2" />
      Instances
    </v-card-title>
        <v-card-text>
          <v-select
            :items="instances"
            item-title="name"
            item-value="id"
            label="Instance"
            :model-value="selectedInstance?.id ?? null"
            :disabled="instances.length === 0"
            :item-props="instanceProps"
            @update:model-value="selectInstanceById"
          />

          <div v-if="selectedInstance" class="d-flex align-center flex-wrap ga-2 mt-3">
            <v-chip
              class="state-chip"
              size="small"
              :color="instanceState.color"
              variant="tonal"
              :prepend-icon="instanceState.icon"
            >
              {{ instanceState.label }}
            </v-chip>
            <span class="text-body-2 text-medium-emphasis">{{ describe(selectedInstance) }}</span>
            <span class="text-body-2 text-medium-emphasis text-truncate">{{ selectedInstance.dir }}</span>
          </div>

          <v-alert v-if="instances.length === 0" type="info" variant="tonal" class="mt-4">
            An instance is one game directory with its own mods, resource packs and worlds. The game
            itself — the version, its libraries and its assets — is downloaded once into the
            launcher's own folder and played by every instance, so a second instance on the same
            version downloads nothing.
          </v-alert>

          <v-alert v-if="note" :type="noteType" variant="tonal" class="mt-4">{{ note }}</v-alert>

          <div v-if="runningTask" class="mt-4">
            <div class="d-flex align-center ga-2 mb-2">
              <span class="status-dot status-dot--live" />
              <span class="text-body-2">{{ runningTask.name }}: {{ runningTask.message }}</span>
              <v-spacer />
              <v-chip class="state-chip" size="small" color="primary" variant="tonal">
                {{ Math.round(runningTask.progress * 100) }}%
              </v-chip>
            </div>
            <v-progress-linear :model-value="Math.round(runningTask.progress * 100)" height="6" rounded />
          </div>
        </v-card-text>
        <v-card-actions class="flex-wrap ga-1">
          <v-btn :disabled="busy" prepend-icon="mdi-plus" @click="showNewInstance = true">New instance</v-btn>
          <v-btn :disabled="busy" prepend-icon="mdi-folder-outline" @click="importFolder">Import a folder</v-btn>
          <v-btn :disabled="busy" prepend-icon="mdi-package-variant-closed" @click="chooseModpack">
            Import a modpack
          </v-btn>
          <v-btn
            :disabled="!selectedInstance || exportForm.busy"
            :loading="exportForm.busy"
            prepend-icon="mdi-export-variant"
            @click="openExport"
          >
            Export as modpack
          </v-btn>
          <v-spacer />
          <v-btn
            variant="text"
            color="error"
            prepend-icon="mdi-trash-can-outline"
            :disabled="!selectedInstance"
            @click="removeSelected"
          >
            Remove
          </v-btn>
        </v-card-actions>
  </v-card>

  <v-tabs v-model="tab" class="mt-6" density="comfortable" align-tabs="start">
    <v-tab value="version" prepend-icon="mdi-download">Version</v-tab>
    <v-tab value="content" prepend-icon="mdi-puzzle-outline">Mods and packs</v-tab>
    <v-tab value="worlds" prepend-icon="mdi-earth">Worlds</v-tab>
    <v-tab value="options" prepend-icon="mdi-tune-variant">Options</v-tab>
  </v-tabs>

  <v-card v-if="tab === 'version'" class="mt-2">
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-download" size="small" class="mr-2" />
      Install a version here
    </v-card-title>
        <v-card-text>
          <p class="text-medium-emphasis">
            Installing goes into
            <strong>{{ selectedInstance?.name ?? 'the selected instance' }}</strong
            >. New instances are created under {{ settings?.instancesDir ?? 'the instances folder' }}.
          </p>

          <v-btn-toggle v-model="versionFilter" density="comfortable" mandatory class="my-4">
            <v-btn value="release" size="small">Releases</v-btn>
            <v-btn value="snapshot" size="small">Snapshots</v-btn>
            <v-btn value="all" size="small">All</v-btn>
          </v-btn-toggle>

          <v-autocomplete
            v-model="selectedVersion"
            :items="visibleVersions"
            item-title="id"
            item-value="id"
            label="Game version"
            :loading="loadingVersions"
            hide-details
          />

          <v-select
            v-model="loader"
            :items="loaderChoices"
            label="Mod loader"
            class="mt-4"
            hide-details
            @update:model-value="onLoaderChange"
          />

          <v-select
            v-if="loader !== 'vanilla'"
            v-model="selectedLoader"
            :items="loaderItems"
            item-title="version"
            item-value="version"
            label="Loader version"
            class="mt-4"
            hide-details
          />

          <p class="text-medium-emphasis mt-4 mb-0">
            Files come from Mojang's own servers, and from Fabric's or Quilt's metadata for a loader,
            into the launcher's shared game folder — which every instance plays from, so one you have
            already downloaded is reused instead of fetched again. Each file is checked against the
            hash its version json declares, and an interrupted download resumes rather than starting
            over.
          </p>
        </v-card-text>
        <v-card-actions>
          <v-btn
            color="primary"
            prepend-icon="mdi-download"
            :disabled="!canInstall"
            :loading="busy"
            @click="install"
          >
            Install
          </v-btn>
          <v-btn
            variant="text"
            prepend-icon="mdi-refresh"
            :disabled="loadingVersions"
            @click="loadVersions(true)"
          >
            Refresh list
          </v-btn>
        </v-card-actions>
  </v-card>

  <v-card v-if="tab === 'content'" class="mt-2">
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-puzzle-outline" size="small" class="mr-2" />
      Mods, packs and shaders
    </v-card-title>
        <v-card-text>
          <v-tabs v-model="resourceTab" density="comfortable">
            <v-tab v-for="kind in tabKinds" :key="kind.value" :value="kind.value">{{ kind.title }}</v-tab>
          </v-tabs>

          <p v-if="instanceResources" class="text-medium-emphasis mt-4 text-body-2">
            {{ resourceFolder }}
          </p>

          <p v-if="!selectedInstance" class="text-medium-emphasis mt-4">
            Select or create an instance first.
          </p>
          <template v-else>
            <p v-if="entries.length === 0" class="text-medium-emphasis mt-4">
              Nothing in this folder yet. Add files, take something from Modrinth, or drop them in
              yourself. Turning one off renames it with a <code>.disabled</code> suffix, which every
              loader and pack list ignores.
            </p>

            <v-alert v-if="entryWarnings.length > 0" type="warning" variant="tonal" class="mt-4">
              <p v-for="warning in entryWarnings" :key="warning" class="mb-2">{{ warning }}</p>
            </v-alert>

            <v-list v-if="entries.length > 0" density="comfortable" class="mt-2 app-stagger">
              <v-list-item v-for="entry in entries" :key="entry.name" :title="entryTitle(entry)">
                <template #prepend>
                  <div class="row-icon" :class="entry.enabled ? 'row-icon--primary' : ''">
                    <v-icon :icon="kindIcon(entry.kind)" size="20" />
                  </div>
                </template>
                <template #subtitle>
                  <span>{{ entrySubtitle(entry) }}</span>
                  <span v-if="entry.metadata?.warnings.length" class="text-warning">
                    <v-icon icon="mdi-alert-outline" size="x-small" />
                    {{ entry.metadata.warnings.join(' ') }}
                  </span>
                </template>
                <template #append>
                  <v-switch
                    :model-value="entry.enabled"
                    color="primary"
                    density="compact"
                    hide-details
                    class="mr-2"
                    :aria-label="`${entry.enabled ? 'Disable' : 'Enable'} ${entry.name}`"
                    @update:model-value="toggleResource(entry)"
                  />
                  <v-btn
                    size="small"
                    variant="text"
                    icon="mdi-trash-can-outline"
                    :aria-label="`Remove ${entry.name}`"
                    @click="deleteResource(entry)"
                  />
                </template>
              </v-list-item>
            </v-list>
          </template>
        </v-card-text>
        <v-card-actions class="flex-wrap ga-1">
          <v-btn
            color="primary"
            prepend-icon="mdi-storefront-outline"
            :disabled="!selectedInstance || busy"
            @click="openBrowse"
          >
            Add from Modrinth
          </v-btn>
          <v-btn
            prepend-icon="mdi-file-plus-outline"
            :disabled="!selectedInstance || busy"
            @click="addResourceFiles"
          >
            Add files
          </v-btn>
          <v-btn
            variant="text"
            prepend-icon="mdi-folder-open-outline"
            :disabled="!selectedInstance"
            @click="openResourceFolder"
          >
            Open folder
          </v-btn>
        </v-card-actions>
  </v-card>

  <v-card v-if="tab === 'version'" class="mt-4">
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-tag-multiple-outline" size="small" class="mr-2" />
      Versions available to this instance
    </v-card-title>
        <v-card-text>
          <p class="text-medium-emphasis text-body-2 mb-3">
            Kept in the launcher's own game folder rather than in the instance: a version is hundreds
            of megabytes of Minecraft's own files, so every instance plays the same copy of it. A
            version already listed here installs in seconds with nothing to download.
          </p>
          <v-alert v-if="installations.length === 0" type="info" variant="tonal" class="mb-2">
            No version downloaded yet. Install one above; it is kept in the launcher's game folder
            and every instance can then play it.
          </v-alert>
          <v-list v-else density="comfortable">
            <v-list-item
              v-for="version in installations"
              :key="version.id"
              :title="version.id"
              :subtitle="`${version.type ?? 'unknown type'} · java ${version.javaMajorVersion ?? 'any'} · assets ${version.assetsIndex ?? 'none'}${version.inheritsFrom ? ' · inherits ' + version.inheritsFrom : ''}`"
            >
              <template #prepend>
                <div
                  class="row-icon"
                  :class="version.type === 'release' ? 'row-icon--success' : 'row-icon--warning'"
                >
                  <v-icon
                    :icon="version.type === 'release' ? 'mdi-tag-check-outline' : 'mdi-flask-outline'"
                    size="20"
                  />
                </div>
              </template>
            </v-list-item>
          </v-list>
        </v-card-text>
        <v-card-actions>
          <v-btn variant="text" prepend-icon="mdi-refresh" :disabled="!selectedInstance" @click="rescan()">
            Rescan
          </v-btn>
        </v-card-actions>
  </v-card>

  <v-card v-if="tab === 'worlds'" class="mt-2">
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-earth" size="small" class="mr-2" />
      Worlds and backups
    </v-card-title>
        <v-card-text>
          <p v-if="!selectedInstance" class="text-medium-emphasis">
            Select an instance first.
          </p>
          <template v-else>
            <p class="text-medium-emphasis">
              Read from this instance's own <code>saves/</code> folder and from each world's
              <code>level.dat</code>, so the name and the version are the game's rather than the
              folder's. Installing a different version or loader backs these up first.
            </p>

            <v-alert v-if="savesInfo && savesInfo.worlds.length === 0" type="info" variant="tonal">
              No worlds yet. Play the instance once, or import one you already have.
            </v-alert>
            <v-list v-else density="comfortable" class="app-stagger">
              <v-list-item
                v-for="world in savesInfo?.worlds ?? []"
                :key="world.folder"
                :title="world.name"
                :subtitle="worldSubtitle(world)"
              >
                <template #prepend>
                  <div class="row-icon" :class="world.problem ? 'row-icon--warning' : 'row-icon--primary'">
                    <v-icon :icon="world.problem ? 'mdi-alert-outline' : 'mdi-earth'" size="20" />
                  </div>
                </template>
                <template #append>
                  <v-btn
                    size="small"
                    variant="text"
                    :disabled="worldsBusy || world.problem !== null"
                    @click="exportWorldNow(world)"
                  >
                    Export
                  </v-btn>
                </template>
              </v-list-item>
            </v-list>

            <v-divider v-if="savesInfo && savesInfo.backups.length > 0" class="my-3" />
            <p v-if="savesInfo && savesInfo.backups.length > 0" class="text-medium-emphasis mb-2">
              Backups, newest first. <strong>before-</strong> ones were taken automatically because the
              game underneath the worlds was about to change. Restoring one puts its worlds back
              beside whatever is in <code>saves/</code> now, never over it.
            </p>
            <v-list v-if="savesInfo && savesInfo.backups.length > 0" density="comfortable">
              <v-list-item
                v-for="backup in savesInfo.backups"
                :key="backup.path"
                :title="backup.name"
                :subtitle="`${backup.kind === 'before-change' ? 'before an install' : 'taken by hand'} · ${backup.at.slice(0, 16).replace('T', ' ')} · ${(backup.bytes / 1048576).toFixed(1)} MB`"
              >
                <template #prepend>
                  <div
                    class="row-icon"
                    :class="backup.kind === 'before-change' ? 'row-icon--warning' : 'row-icon--success'"
                  >
                    <v-icon
                      :icon="backup.kind === 'before-change' ? 'mdi-shield-alert-outline' : 'mdi-shield-check-outline'"
                      size="20"
                    />
                  </div>
                </template>
                <template #append>
                  <v-btn
                    size="small"
                    variant="text"
                    prepend-icon="mdi-folder-open-outline"
                    :aria-label="`Show ${backup.name}`"
                    @click="openPath(backup.path)"
                  >
                    Show
                  </v-btn>
                  <v-btn
                    size="small"
                    variant="tonal"
                    prepend-icon="mdi-upload-outline"
                    :disabled="worldsBusy"
                    @click="restoreBackupNow(backup)"
                  >
                    Restore
                  </v-btn>
                </template>
              </v-list-item>
            </v-list>
          </template>
        </v-card-text>
        <v-card-actions class="flex-wrap ga-1">
          <v-btn
            variant="text"
            prepend-icon="mdi-folder-open-outline"
            :disabled="!selectedInstance"
            @click="selectedInstance && openPath(savesInfo?.dir ?? selectedInstance.dir)"
          >
            Saves folder
          </v-btn>
          <v-spacer />
          <v-btn
            variant="text"
            prepend-icon="mdi-import"
            :disabled="!selectedInstance || worldsBusy"
            @click="importWorldNow()"
          >
            Import a world
          </v-btn>
          <v-btn
            variant="text"
            prepend-icon="mdi-folder-zip-outline"
            :disabled="!selectedInstance || worldsBusy"
            @click="importWorldZip()"
          >
            Import a zip
          </v-btn>
          <v-btn
            color="primary"
            variant="tonal"
            prepend-icon="mdi-shield-plus-outline"
            :disabled="!selectedInstance || worldsBusy"
            @click="backupWorldsNow()"
          >
            Back up now
          </v-btn>
        </v-card-actions>
  </v-card>

  <v-card v-if="tab === 'options'" class="mt-2">
    <v-card-title class="d-flex align-center">
      <v-icon icon="mdi-tune-variant" size="small" class="mr-2" />
      Game and java options
    </v-card-title>
        <v-card-text>
          <p v-if="!selectedInstance" class="text-medium-emphasis">
            Select an instance first.
          </p>
          <template v-else>
            <!-- How it runs comes first: Java and memory are what the process
                 is started with, and the file below is what the game writes. -->
            <div class="d-flex flex-wrap align-center ga-2 mb-1">
              <div class="row-icon row-icon--primary">
                <v-icon icon="mdi-coffee-outline" size="20" />
              </div>
              <h3 class="text-subtitle-1 mb-0">How {{ selectedInstance.name }} runs</h3>
            </div>
            <p class="text-medium-emphasis text-body-2">
              <template v-if="javaInfo?.requiredMajor !== null && javaInfo?.requiredMajor !== undefined">
                <strong>{{ selectedInstance.versionId ?? 'This version' }}</strong> needs Java
                {{ javaInfo.requiredMajor }}.
              </template>
              <template v-else>This version does not name the Java it needs.</template>
              Automatic uses the runtime inside the instance, then one this machine has, and downloads
              Mojang's own when there is none.
            </p>
            <div class="d-flex flex-wrap ga-3 align-center">
              <v-select
                v-model="instanceJava"
                :items="javaItems"
                item-title="title"
                item-value="value"
                label="Java runtime"
                prepend-inner-icon="mdi-coffee-outline"
                density="comfortable"
                hide-details
                class="runtime-field"
              />
              <v-text-field
                v-model="instanceMemory"
                label="Maximum memory (MB)"
                type="number"
                density="comfortable"
                hide-details
                class="memory-field"
                :placeholder="String(settings?.maxMemoryMb ?? 4096)"
              />
              <v-btn
                color="primary"
                variant="tonal"
                prepend-icon="mdi-content-save-outline"
                :loading="savingRuntime"
                @click="saveRuntime"
              >
                Save
              </v-btn>
            </div>
            <p class="text-medium-emphasis text-body-2 mt-2">
              Left empty, memory is the launcher's default ({{ settings?.maxMemoryMb ?? 4096 }} MB).
              The JVM arguments below are added on top of the runtime this one starts.
            </p>

            <v-divider class="my-5" />

            <p class="text-medium-emphasis">
              These are this instance's own <code>options.txt</code>, the file the game writes in
              {{ selectedInstance.dir }}. Only what you change is written, and every other line is
              kept exactly where it was.
            </p>
            <v-alert v-if="options && !options.exists" type="info" variant="tonal" class="mb-4">
              The game has not written its options yet. What you set here is used the first time you
              play.
            </v-alert>

            <!-- One setting per row, read left to right: what it is, its own key
                 underneath, and the control where the value goes. -->
            <v-list density="comfortable" class="options-list py-0">
              <v-list-item v-for="field in options?.fields ?? []" :key="field.key">
                <template #prepend>
                  <div
                    class="row-icon"
                    :class="optionForm[field.key] === '' ? '' : 'row-icon--primary'"
                  >
                    <v-icon :icon="optionIcon(field.key)" size="20" />
                  </div>
                </template>
                <v-list-item-title>{{ field.label }}</v-list-item-title>
                <v-list-item-subtitle>
                  <code>{{ field.key }}</code>
                  <span v-if="optionForm[field.key] === ''">, the game's own default</span>
                </v-list-item-subtitle>
                <template #append>
                  <v-select
                    v-if="field.choices"
                    v-model="optionForm[field.key]"
                    :items="['', ...field.choices]"
                    density="compact"
                    variant="outlined"
                    hide-details
                    class="option-control"
                    :aria-label="field.label"
                  />
                  <v-text-field
                    v-else
                    v-model="optionForm[field.key]"
                    type="number"
                    density="compact"
                    variant="outlined"
                    hide-details
                    class="option-control option-control--number"
                    :aria-label="field.label"
                  />
                </template>
              </v-list-item>
            </v-list>

            <div class="d-flex align-center ga-2 mt-6 mb-2">
              <v-icon icon="mdi-console-line" size="small" />
              <span class="text-subtitle-2">Extra JVM arguments</span>
            </div>
            <p class="text-medium-emphasis text-body-2 mb-2">
              Added to the command as they are. Memory is set in Settings.
            </p>
            <div class="d-flex flex-wrap ga-2 align-center mb-2">
              <v-chip
                v-for="(arg, index) in jvmArgs"
                :key="`${arg}-${index}`"
                closable
                variant="tonal"
                @click:close="removeJvmArg(index)"
              >
                {{ arg }}
              </v-chip>
              <span v-if="jvmArgs.length === 0" class="text-medium-emphasis text-body-2">
                None yet.
              </span>
            </div>
            <div class="d-flex ga-2 align-center">
              <v-text-field
                v-model="newJvmArg"
                label="Add an argument"
                placeholder="-XX:+UseG1GC"
                prepend-inner-icon="mdi-plus"
                density="compact"
                hide-details
                @keyup.enter="addJvmArg"
              />
              <v-btn
                variant="tonal"
                prepend-icon="mdi-plus"
                :disabled="newJvmArg.trim() === ''"
                @click="addJvmArg"
              >
                Add
              </v-btn>
            </div>

            <v-divider class="my-4" />
            <p class="text-medium-emphasis mb-2">
              Any other option can be set by key. Clearing the value removes the key, which hands it
              back to the game's own default.
            </p>
            <div class="d-flex ga-2 align-center">
              <v-text-field v-model="extraKey" label="Option key" density="compact" hide-details />
              <v-text-field v-model="extraValue" label="Value" density="compact" hide-details />
              <v-btn :disabled="extraKey.trim() === ''" @click="setExtraOption">Set</v-btn>
            </div>

            <v-btn variant="text" class="mt-4" :prepend-icon="showEveryOption ? 'mdi-chevron-up' : 'mdi-chevron-down'" @click="showEveryOption = !showEveryOption">
              {{ showEveryOption ? 'Hide' : 'Show' }} every option in the file
              ({{ options?.entries.length ?? 0 }})
            </v-btn>
            <template v-if="showEveryOption">
              <v-text-field
                v-model="optionSearch"
                label="Find an option"
                prepend-inner-icon="mdi-magnify"
                density="compact"
                hide-details
                clearable
                class="mt-2 mb-2"
              />
              <p v-if="filteredEntries.length === 0" class="text-medium-emphasis mb-0">
                No option in the file matches that.
              </p>
              <v-list v-else density="compact" class="options-list">
                <v-list-item v-for="entry in filteredEntries" :key="entry.key">
                  <v-list-item-title>{{ entry.key }}</v-list-item-title>
                  <v-list-item-subtitle>{{ entry.value }}</v-list-item-subtitle>
                  <template #append>
                    <v-btn
                      size="small"
                      variant="text"
                      icon="mdi-trash-can-outline"
                      :aria-label="`Remove ${entry.key}`"
                      @click="removeOption(entry.key)"
                    />
                  </template>
                </v-list-item>
              </v-list>
            </template>
          </template>
        </v-card-text>
        <v-card-actions>
          <v-btn
            color="primary"
            prepend-icon="mdi-content-save-outline"
            :disabled="!selectedInstance || savingOptions"
            :loading="savingOptions"
            @click="saveOptions"
          >
            Save options
          </v-btn>
        </v-card-actions>
  </v-card>

  <v-dialog v-model="showNewInstance" max-width="520">
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon icon="mdi-cube-outline" size="small" class="mr-2" />
        New instance
      </v-card-title>
      <v-card-text>
        <v-text-field
          v-model="newInstanceName"
          label="Instance name"
          hint="Up to 48 characters; it names the folder too."
          persistent-hint
          autofocus
          @keyup.enter="createInstanceFromName"
        />
        <p class="text-medium-emphasis mt-4 mb-0">
          It starts empty. Install a version into it, or import a pack.
        </p>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="showNewInstance = false">Cancel</v-btn>
        <v-btn
          color="primary"
          prepend-icon="mdi-plus"
          :disabled="busy || newInstanceName.trim() === ''"
          @click="createInstanceFromName"
        >
          Create
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="browse.open" max-width="820" scrollable>
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon icon="mdi-storefront-outline" size="small" class="mr-2" />
        Add {{ tabKindLabel.toLowerCase() }} from Modrinth
      </v-card-title>
      <v-card-text>
        <p class="text-medium-emphasis">
          Searching for {{ selectedInstance?.versionId ?? 'the instance version' }}
          <template v-if="selectedInstance?.loader && selectedInstance.loader !== 'vanilla'">
            on {{ selectedInstance.loader }}
          </template>
          , so what is listed fits this instance.
        </p>
        <div class="d-flex ga-2 align-center">
          <v-text-field
            v-model="browse.query"
            label="Search Modrinth"
            prepend-inner-icon="mdi-magnify"
            hide-details
            autofocus
            @keyup.enter="runSearch"
          />
          <v-btn :loading="browse.loading" prepend-icon="mdi-magnify" @click="runSearch">
            Search
          </v-btn>
        </div>

        <div v-if="browse.loading && browse.hits.length === 0" class="d-flex justify-center py-6">
          <v-progress-circular indeterminate color="primary" />
        </div>

        <p
          v-else-if="browse.searched && browse.hits.length === 0"
          class="text-medium-emphasis mt-4"
        >
          Nothing found. Modrinth filters by this instance's version and loader, so a mod built for
          another one will not appear.
        </p>

        <v-list v-if="browse.hits.length > 0" density="comfortable" class="mt-4 app-stagger">
          <v-list-item
            v-for="hit in browse.hits"
            :key="hit.projectId"
            :title="hit.title"
            :subtitle="hit.description"
            :active="browse.selected?.projectId === hit.projectId"
            @click="chooseHit(hit)"
          >
            <template #prepend>
              <div class="row-icon" :class="browse.selected?.projectId === hit.projectId ? 'row-icon--primary' : ''">
                <v-icon :icon="kindIcon(resourceTab)" size="20" />
              </div>
            </template>
            <template #append>
              <span class="text-medium-emphasis text-body-2">
                {{ hit.downloads.toLocaleString() }} downloads
              </span>
            </template>
          </v-list-item>
        </v-list>

        <template v-if="browse.selected">
          <v-divider class="my-4" />
          <p>
            <strong>{{ browse.selected.title }}</strong>: pick a version. Required dependencies are
            installed with it, and every file is checked against the hash Modrinth published.
          </p>
          <v-btn
            v-if="browse.versions.length > 0"
            color="primary"
            class="mb-2"
            :loading="browse.installing"
            @click="installFromBrowse(browse.versions[0].id)"
          >
            Install {{ browse.versions[0].versionNumber }}
            <template v-if="browse.versions[0].versionType !== 'release'">
              ({{ browse.versions[0].versionType }})
            </template>
          </v-btn>
          <v-progress-linear v-if="browse.versionsLoading" indeterminate height="3" />
          <v-list v-else density="compact">
            <v-list-item
              v-for="version in browse.versions"
              :key="version.id"
              :title="`${version.versionNumber} (${version.versionType})`"
              :subtitle="`${version.gameVersions.slice(0, 4).join(', ')} · ${version.loaders.join(', ') || 'any loader'}`"
            >
              <template #append>
                <v-btn size="small" :disabled="browse.installing" @click="installFromBrowse(version.id)">
                  Install
                </v-btn>
              </template>
            </v-list-item>
          </v-list>
        </template>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="browse.open = false">Close</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="showModpack" max-width="620">
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon icon="mdi-package-variant-closed" size="small" class="mr-2" />
        {{ modpackSummary?.name ?? 'Modpack' }}
      </v-card-title>
      <v-card-text v-if="modpackSummary">
        <v-list density="compact">
          <v-list-item title="Format" :subtitle="modpackSummary.format" />
          <v-list-item title="Minecraft" :subtitle="modpackSummary.gameVersion ?? 'not named'" />
          <v-list-item
            title="Loader"
            :subtitle="`${modpackSummary.loader ?? 'vanilla'}${modpackSummary.loaderVersion ? ' ' + modpackSummary.loaderVersion : ''}`"
          />
          <v-list-item title="Files to download" :subtitle="String(modpackSummary.downloadable)" />
          <v-list-item title="Files inside the pack" :subtitle="String(modpackSummary.bundled)" />
        </v-list>
        <v-alert v-if="modpackSummary.unresolved.length > 0" type="warning" variant="tonal" class="mt-4">
          {{ modpackSummary.unresolved.length }} file(s) in this pack are identified only by CurseForge
          project and file id, and this launcher does not call CurseForge's API. Everything else is
          installed, and the log lists what to add by hand.
        </v-alert>
        <p class="text-medium-emphasis mt-4 mb-0">
          A new instance is created for the pack, so it cannot disturb the ones you have.
        </p>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="showModpack = false">Cancel</v-btn>
        <v-btn color="primary" prepend-icon="mdi-download" :loading="busy" @click="installModpack">
          Import
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="exportForm.open" max-width="560">
    <v-card>
      <v-card-title class="d-flex align-center ga-2">
        <v-icon icon="mdi-export-variant" size="small" />
        Export as a modpack
      </v-card-title>
      <v-card-text>
        <p class="text-medium-emphasis">
          Writes <strong>{{ selectedInstance?.name }}</strong> out as a <code>.mrpack</code>: the mods,
          resource packs, shader packs, config and <code>options.txt</code> that make it what it is.
          The game files, the worlds and the backups stay behind, and the index names the game
          version and the loader so an importer knows what to build underneath the pack.
        </p>
        <v-text-field
          v-model="exportForm.name"
          label="Pack name"
          density="comfortable"
          hide-details
          class="mt-4"
        />
        <v-text-field
          v-model="exportForm.versionId"
          label="Pack version"
          density="comfortable"
          hide-details
          hint="Optional. Left empty, the export stamps the date and time."
          persistent-hint
          class="mt-4"
        />
        <p class="text-medium-emphasis mt-6 mb-0">
          Nothing is uploaded anywhere: the file is written into the folder you pick next.
        </p>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="exportForm.open = false">Cancel</v-btn>
        <v-btn color="primary" prepend-icon="mdi-folder-outline" @click="runExport">
          Choose a folder
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
/*
 * The controls that sit inside a list row or a toolbar need a width: without
 * one Vuetify leaves them as wide as their content, which makes a column of
 * rows look ragged rather than aligned.
 */
.runtime-field {
  flex: 1 1 320px;
  max-width: 420px;
  min-width: 240px;
}

.memory-field {
  flex: 0 1 200px;
}

.option-control {
  width: 200px;
}

.option-control--number {
  width: 160px;
}

/* One row per setting, so the list reads as a table rather than a column of
   floating fields. */
.options-list {
  border: 1px solid rgba(var(--v-theme-on-surface), 0.08);
}
</style>

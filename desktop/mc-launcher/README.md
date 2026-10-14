# MC Launcher

A desktop Minecraft launcher with **local profiles only**, built around
**instances**: one game directory each, with its own version, loader, mods,
resource packs and shader packs. It downloads game versions and Fabric or Quilt
loaders from the official metadata services, imports folders and modpacks,
manages what is in each instance's resource folders, and joins offline-mode
servers. Electron + Vue 3 + Vuetify, TypeScript throughout.

It exists because the architecture is worth having in your own hands: an
isolated renderer, a preload bridge, a single generic service RPC channel, a
snapshot state protocol, and a launch pipeline built from the version json.

## What it does

- **Local profiles.** A name (your in-game name) and nothing else. The profile's
  UUID is derived from that name (`UUID.nameUUIDFromBytes("OfflinePlayer:<name>")`,
  an RFC 4122 v3 UUID) — no account is created, stored, or contacted.
- **Downloads and installs.** Pick any version Mojang's manifest lists — releases,
  snapshots or both — optionally with a Fabric or Quilt loader on top, and press
  Install. The client jar, every library the per-OS rules allow, the asset index
  and its objects are fetched from Mojang, and a loader profile is written from
  Fabric's or Quilt's metadata. Native libraries are unpacked into the version's
  `natives` directory, which is what the launch plan needs on older versions.
- **Every file is verified, and nothing half-fetched counts.** A file is only
  renamed into place once its sha1 matches the hash the version json declared;
  a download that is interrupted keeps its `.part` file and resumes with a range
  request next time, and a hash mismatch keeps the bytes and fails loudly instead
  of leaving a corrupt jar behind a working-looking install.
- **Instances.** An instance is one game directory with its own version,
  loader, `mods/`, `resourcepacks/` and `shaderpacks/`. Create one (the folder
  name is derived from its name and cannot collide), select it, install a
  version into it. A profile is only an identity, so several profiles can play
  one instance and one profile can play any instance.
- **Mods, resource packs and shader packs, per instance.** Each folder lists
  what is in it and what the game will load — a mod may be a `.jar` or `.zip`, a
  resource pack or shader pack must be a zip. Turning one off renames it to
  `<name>.disabled`, which every loader and pack list ignores, so the switch
  matches the files on disk rather than a list that can drift. A file the folder
  would not load is refused with the reason (a `.jar` in `resourcepacks/` says
  "did you mean to add it to mods?") instead of being copied and ignored, and
  each folder opens in the OS file manager.
- **Install from Modrinth.** Mods, resource packs and shader packs are found by
  search and installed into the selected instance, filtered by its game version
  and loader (a Quilt instance is offered Fabric mods, because Quilt runs them).
  Every file is checked against the sha1/sha512 the publisher declared, and the
  mod's required dependencies are followed up to three levels and installed
  with it — a mod that silently needs another one is the most common way a
  modded launch fails. A dependency with no version for this instance is named
  in a warning instead of being installed for a different game version. No API
  key and no account are involved.
- **Each jar is read, not guessed at.** A mod's own metadata decides what the
  list says: `fabric.mod.json`, `quilt.mod.json`, `META-INF/mods.toml`,
  `META-INF/neoforge.mods.toml` and the legacy `mcmod.info` give the real name,
  version and loader. A Forge mod in a Fabric instance, a mod that asks for a
  different Minecraft version, or a second copy of the same mod id is flagged in
  the folder listing, where it can be fixed. A version range the launcher cannot
  read produces no warning rather than a wrong one.
- **Modpack import.** A Modrinth `.mrpack` is read from its index: Minecraft
  version, loader, and every file with a URL and hashes are installed, entries
  the pack excludes from the client are skipped, and its `overrides/` tree is
  unpacked into the instance. A CurseForge pack's files are identified only by
  project and file id, which needs their keyed API, so its overrides are
  imported and the rest is reported rather than half-installed. The pack is
  inspected before anything is written, and always goes into a new instance.
- **Finds Java, and provides it if it is not there.** The runtime inside the game
  directory (`runtime/<component>`, matched against the `javaVersion` the version
  json names), then `JAVA_HOME`, then the platform's JDK folders, SDKMAN and
  PATH. It picks the smallest runtime that is new enough. When the machine has
  none — normal on Windows — the launcher downloads Mojang's own runtime for that
  version into the instance, exactly as the official launcher does, and launching
  does it by itself rather than refusing. Settings shows what the instance needs,
  what is installed there, and what is on the machine.
- **A runtime is a directory, not a system install.** `<instance>/runtime/...`
  means two instances can want different Java versions and both get them, and
  nothing outside the launcher's own folders is touched.
- **Runs an installation that is already on disk.** It scans an instance's
  `versions/`, resolves `inheritsFrom` chains (vanilla + a mod loader), evaluates
  the per-OS `rules` on libraries, builds the classpath, and substitutes the
  version json's launch placeholders.
- **Takes an installation from somewhere else.** *Import a folder* accepts a
  game directory — which it then uses in place, because copying one is several
  gigabytes — or a single version folder, which it copies in. That is how a
  version downloaded elsewhere gets in.
- **Server list and join.** Saved addresses plus a queued join. Modern versions
  get `--quickPlayMultiplayer host:port`; older ones get `--server/--port`.
- **Per-instance game and java options.** Each instance's own `options.txt` is
  shown and patchable — fullscreen, graphics, FOV, render distance, FPS cap, GUI
  scale and the rest, plus any other key by name — and the file is patched, not
  rewritten, so keys the launcher has never heard of survive. The instance also
  carries its own extra JVM arguments, added before the memory limit so the
  memory setting still wins.
- **Log view, task list, settings** (instances folder, java binary, memory,
  window size), and a preview of the exact java command before launching.

## What it deliberately does not do

- **No account integration.** There is no Microsoft/Xbox Live login, no session,
  and no token. The game receives `--accessToken 0` and `--userType legacy`,
  which is what an offline profile passes.
- **No authentication bypass.** Nothing here fabricates, replays or alters a
  session. Online-mode servers verify a session with Mojang's session servers;
  a local profile has none, so those servers will refuse the join. That is
  expected, and it is the reason the server list can only be useful against
  servers that you or someone else configured with `online-mode=false`.
- **No CurseForge.** Their API needs a key and their terms have conditions this
  app does not meet, so a CurseForge pack is imported for its overrides and its
  file list is reported rather than fetched. Modrinth, which answers without a
  key, is fully supported for search and install.
- **No marketplace or modpack browsing.** There is no storefront: a modpack is
  still imported from an archive you downloaded. What happens after a project is
  chosen — the version that fits, the hashes, the dependencies — is done properly.
- **No Forge or NeoForge installation.** Both publish installers that have to be
  run as a Java process and edit the version json themselves. Fabric and Quilt
  publish a finished profile json instead, which is why they are the two that
  are supported; install Forge with its own installer and rescan if you want it.
- **No OptiFine.** Their site has no metadata API and their terms discourage
  automated downloads.

Use it with a copy of the game you own.

## Where it lives

This app is part of the [Tool-Hubs](../../README.md) repository, at
`desktop/mc-launcher`. It is **not a Gradle module and is deliberately not in
`settings.gradle.kts`** — it is an Electron app on the Node toolchain, so the
Android build never touches it and it never touches `core/` or `tools/`.

Its version is its own, in [`package.json`](package.json), and moves
independently of Chime and Network Switcher.

## Requirements

- Node.js 22.12+ (developed on 24)
- A JDK for the game: the version json records what it wants
  (`javaVersion.component` and `javaVersion.majorVersion` — 17 for ≤1.20.4-era
  versions, 21, 25 and up after that). It does **not** have to be on `PATH` or
  installed at all: the launcher looks in the game directory, `JAVA_HOME` and the
  usual install locations, and downloads Mojang's runtime when there is none.
- Network access for installing. Nothing else needs it: launching an installed
  version is entirely offline, and no account is contacted either way.

## Development

```bash
npm install
npm run dev        # electron-vite dev, with HMR for the renderer
npm run verify     # typecheck + selfcheck + tests + build
npm run selfcheck  # launcher logic checks (launch plan, rules, downloads, state store)
npm run netcheck   # the same install path against the live Mojang/Fabric/Quilt APIs
npm run test       # renderer tests against a stubbed bridge
npm run build      # typecheck, then bundle main/preload/renderer into out/
npm run dist:linux # package with electron-builder (also dist:win, dist:mac)
```

Packaging only runs on a machine for the platform it is packaging, so CI does it:
[`.github/workflows/desktop.yml`](../../.github/workflows/desktop.yml) verifies
the app on Linux and then packages on Ubuntu, Windows and macOS whenever a
commit touches `desktop/`, and on demand:

```bash
gh workflow run desktop.yml --ref main
```

The installers (Windows `.exe`, `.dmg`, `.deb`, `.AppImage`) are uploaded as
run artifacts with one-day retention. Packaging is the only part of this app
that CI does for you — running the app, launching the game and loading natives
always need a desktop.

## Layout

```
src/main/            Electron main process
  index.ts           window creation, IPC wiring, service registration
  window-controller.ts
  services/          profile, instance, installation, install, modrinth, modpack,
                     java, server, launch, task, log, settings
  minecraft/         version json, rules, classpath, arguments, launch plan,
                     download, install plan, loader metadata, modpack indexes,
                     modrinth api, jar metadata, resource folders, game
                     options, java discovery, mojang java runtimes, zip, manifest
src/preload/         contextBridge: serviceChannels, windowController, bootstrap, taskMonitor
src/renderer/        Vue 3 + Vuetify UI
src/shared/          types and channel constants shared by all three
scripts/selfcheck.ts logic checks, runnable under plain Node
scripts/netcheck.ts  the installer against the live metadata services
```

The renderer never touches Node. It calls `window.serviceChannels.call(service,
method, params)`, which lands in `ServiceRegistry.call` in the main process;
unknown services and methods throw rather than being ignored. State flows back
as a snapshot event, so the UI keeps a working copy instead of guessing.

## Verification status

Verified locally on Linux with Node 24.21:

- `npm run typecheck` — clean for main, preload, shared and the Vue renderer.
- `npm run selfcheck` — 84 checks: the offline UUID, rule evaluation,
  inheritance, classpath and natives resolution (including a library listed twice
  with the natives on the second listing), argument substitution, the launch
  plan and its failure paths, zip reading and extraction (including a modpack's
  `overrides/` tree and an archive that tries to climb out of the instance),
  install planning, loader metadata, the downloader (resume, checksum rejection,
  retries, byte progress), instance creation and naming, resource classification
  and the `.disabled` toggle, importing a folder, both modpack index formats and
  what each refuses, java version parsing and runtime selection, Modrinth search
  facets and responses, version selection, required-dependency following and the
  version ranges mods publish, jar metadata for all four loader formats
  (including the legacy one) and the warnings they produce, reading and patching
  `options.txt` and splitting JVM arguments the way a shell would, Mojang's
  runtime index and manifest (including that the runtime is installed exactly
  where discovery looks for it), profile/server/settings validation, the state
  store and the migration from the old single-directory state file.
- `npm run netcheck` — the same install path against live services: Mojang's
  manifest and a real version json, a real asset index, libraries and assets
  downloaded and hash-checked (664 KB), a real native jar unpacked (1.16.5's
  LWJGL), the 39.6 MB client jar fetched and verified, real Fabric and Quilt
  loader profiles parsed, Modrinth searched and resolved for the current release
  (a real Sodium build downloaded with its hash verified and its name read back
  out of the jar), and Mojang's own Java runtime downloaded, unpacked, marked
  executable, run to confirm its version, and then found again by the resolver.
- `npm run test` — 6 renderer tests: boot from the bridge, snapshot rendering, a
  UI action reaching the service bridge, error surfacing, navigation, and the
  install picker installing what it offers into the selected instance.
- `npm run build` — main, preload and renderer bundles build.

## License

MIT. This is an original implementation: no third-party source or assets were
copied into it.

It does stand on the shoulders of one: **XMCL** (X Minecraft Launcher,
[voxelum/x-minecraft-launcher](https://github.com/voxelum/x-minecraft-launcher)),
which is MIT. The IPC architecture here — a single generic service channel, a
snapshot state protocol, the bootstrap/commit/revalidate handshake — was studied
from it, and its source is now the reference for how features are meant to
behave: the Modrinth client, the per-version Java runtime download from Mojang's
own index, and the store and instance pages still to come. Reading a reference
and writing your own code is what happens here; if that ever changes and code is
adapted rather than learned from, the MIT attribution goes in this section first.

**Not verified here:** the app was never launched and no game was ever started.
The build environment is a headless container with no display, so `npm run dev`
and an actual launch were not exercised. Argument ordering against a real loader
version, native library loading at runtime, and the java spawn path all need a
run on a desktop.

**Verified against the real services, but not as a whole install:** `netcheck`
downloads and verifies real files, but it stops short of a full 3000-asset,
multiple-hundred-megabyte install and never runs the game afterwards.

**Also not verified:** the packaged installers. `electron-builder` cannot
cross-package, so the `.exe` is built by CI on `windows-latest` and has not been
run here. It is unsigned, so expect a SmartScreen warning on first launch.

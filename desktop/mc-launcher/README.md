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
- **Crash help instead of a stack trace.** Each instance's own `crash-reports/`
  and `logs/latest.log` are read and turned into findings — out of memory, the
  wrong Java, the graphics driver, a mod built against another version, a mixin
  that failed, two copies of one mod, a missing dependency with the mod ids to
  install, an incomplete installation, a dropped connection — each with the line
  it was read from and what to do about it. Reports are listed newest first and
  are never modified, and a log with no failure in it produces no findings.
- **State you can see, in the title bar and in every list.** The profile that is
  playing is shown in the title bar with a dot that pulses, next to a Stop button
  and a chip for whatever install, download or launch is still working with its
  percentage; the Play page shows the same state in place of the launch button
  while the game runs. Versions are tagged as releases or snapshots, worlds and
  their backups carry their own icons, resources show their kind and whether the
  game will load them, servers show what the last ping said, and each crash
  finding is stamped with its severity. Nothing is spelled out that an icon
  already says, and nothing is drawn that the icon font does not define.
- **An interface that moves, the way XMCL's does.** The launch pill has the play
  control set into it and spins it once when clicked; pages fade up as they
  arrive; cards transition their border towards the primary colour on hover
  instead of jumping to an elevation; rows arrive one after another; and the app
  sits on a background of a drifting grid and a slow light sweep, all drawn in
  CSS. `prefers-reduced-motion` turns every one of those off.
- **Two themes, blur and rounded corners.** The window follows the desktop's
  light/dark preference by default and can be pinned to either in Settings; the
  preference is re-read while the app is open, so a desktop that switches at
  dusk takes the launcher with it. Surfaces are translucent and blur what is
  behind them — the grid and the light sweep show through a card, and more of
  the page shows through the bar and drawer while it scrolls under them — and
  every control is rounded by default rather than one at a time.
- **Servers: pinged, and shared with the game.** A server in the list is pinged the
  way the game's own multiplayer screen does it — handshake, status request, one
  JSON answer — so the list shows its version, player count, round trip, icon and
  its description with the colour codes stripped; a refusal, a timeout or an
  answer that is not a status is reported as offline *with the reason*. The
  launcher's servers are also written into the instance's own `servers.dat` and
  read back out of it, so a server added while playing shows up here and one
  added here shows up in the game. Servers only the game knows are kept exactly
  as they are, and a `servers.dat` this launcher cannot read is reported rather
  than overwritten.
- **Worlds.** Each instance's `saves/` folder is listed with what each world's own
  `level.dat` says about it — the name the game shows, the version that last wrote
  it, when it was last played, the game mode, hardcore — and a world that cannot be
  read is listed with the reason instead of hidden. A world exports to a zip you
  can send to someone (streamed, so a huge world needs no huge memory) and imports
  from a folder, a `.zip` or a `.mcworld`; an archive holding two worlds is refused
  rather than half-imported, and a name that is already taken gets a number.
- **Backups of those worlds.** Any install that changes the game underneath them —
  a version, a loader, a modpack — zips `saves/` into `<instance>/backups/` first,
  before anything is written, named `before-…` so a folder of backups still reads
  as what it is later. **Back up now** does the same by hand, and an install into
  an instance with no worlds writes nothing at all.
- **Launching repairs what it is about to run.** Before a game starts, the
  installer's plan is run again and anything absent or the wrong size is
  fetched — a deleted jar, a half-finished download, assets missing after a game
  directory was copied from another machine, natives that were never unpacked.
  A complete installation is checked without a single request, and the log says
  what a repair had to fetch. Java is resolved the same way: a runtime already
  installed for the instance is used, then the machine's own, and if neither
  answers Mojang's runtime is downloaded for the version — including for versions
  old enough that their json names no runtime at all, which are matched by the
  Java their release needs rather than by whatever the machine happens to have.
- **Instances, one at a time.** An instance is one game directory with its own
  version, loader, `mods/`, `resourcepacks/` and `shaderpacks/`. The page leads
  with the instance itself — its state, its version, its loader and its folder —
  and the actions that change it (create, import a folder, import a pack, export
  a pack, remove). Everything else is a tab: installing and listing versions,
  mods and packs, worlds and backups, and game and Java options, so one screen
  holds one subject instead of a paragraph standing next to a control. A profile
  is only an identity, so several profiles can play one instance and one profile
  can play any instance.
- **Mods, resource packs and shader packs, per instance.** Each folder lists
  what is in it and what the game will load — a mod may be a `.jar` or `.zip`, a
  resource pack or shader pack must be a zip. Turning one off renames it to
  `<name>.disabled`, which every loader and pack list ignores, so the switch
  matches the files on disk rather than a list that can drift. A file the folder
  would not load is refused with the reason (a `.jar` in `resourcepacks/` says
  "did you mean to add it to mods?") instead of being copied and ignored, and
  each folder opens in the OS file manager.
- **A Modrinth page, which opens on the catalogue.** Four tabs — mods,
  resource packs, shaders and modpacks — each listing the most downloaded content
  for them before anything is typed, so the page is never an empty box; a search
  word and a sort (relevance, downloads, followers, newest, recently updated)
  narrow it, and with an instance selected it lists only what fits that instance's
  game version and loader. A result card opens the project's own page, so the
  published description, its gallery and its version list are one click away
  rather than behind a small button. The game version is read *out of* what the instance
  runs, so a loader profile such as `fabric-loader-0.16.9-1.21.4` is filtered by
  `1.21.4` rather than by a version Modrinth has never heard of. **Check
  connection** asks Modrinth whether it answers and reports what it said — the
  count of projects and the round trip, or the reason it could not, which is what
  separates a rate limit from a proxy that refuses the request. Nothing matched
  and nothing answered are shown as the two different answers they are. Result cards lead to each project's own page: its
  published description, gallery, client/server requirements, licence, links to
  source, issues, wiki and Discord, and its whole version list. Installing a
  modpack from there downloads the `.mrpack` and imports it as a new instance.
  With no instance selected and a resource tab open, the page says so and offers
  to go and pick one instead of quietly installing nothing.
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
- **Modpack export, the other half of it.** An instance writes itself out as a
  `.mrpack` that any launcher reading the format can install: its mods, resource
  packs, shader packs, `config`, `defaultconfigs`, `scripts` and `options.txt`
  are carried under `overrides/`, and the index names the game version and the
  loader so an importer knows what to install underneath the pack. It is
  self-contained rather than linked, because the launcher does not record where a
  file came from and a made-up download URL would be worse than a bigger archive;
  the game files, the worlds, the backups and the logs stay behind. The pack is
  named, versioned (a date stamp when left empty) and written into a folder you
  pick, and an instance with nothing of its own to carry is refused with the
  reason rather than written as an empty file.
- **Finds Java, and provides it if it is not there.** The runtime inside the game
  directory (`runtime/<component>`, matched against the `javaVersion` the version
  json names), then `JAVA_HOME`, then the platform's JDK folders, SDKMAN and
  PATH. It picks the smallest runtime that is new enough. When the machine has
  none — normal on Windows — the launcher downloads Mojang's own runtime for that
  version into the instance, exactly as the official launcher does, and launching
  does it by itself rather than refusing. Settings shows what the instance needs,
  what is installed there, and what is on the machine. An instance that was given
  a runtime of its own uses that one first, and one whose chosen path has since
  been uninstalled falls back to the search rather than refusing to start.
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
- **Its own Java and its own memory, per instance.** Which runtime an instance
  starts on and how much it is allowed to take are chosen on that instance, from
  an *Automatic* that resolves the runtime its version asks for, the runtimes
  this machine has, or a path picked by hand. One launcher-wide Java and one
  launcher-wide memory limit cannot serve a machine that plays both 1.12.2 and
  1.21: the first only starts on Java 8. Leaving either empty means the
  launcher's default in Settings, which is what a new instance gets.
- **A Downloads page.** Every install, download and launch the launcher starts,
  running or finished: its state, percentage, elapsed time, the file it is
  fetching and the bytes that have arrived so far, and the reason a failed one
  failed. Finished tasks clear on request; a running one is left alone. An
  install reports bytes as they arrive rather than only spinning, so a long
  download can be told apart from a stalled one, and the store shows the running
  install above its results.
- **Log view, task list, settings** (theme, instances folder, java binary and
  the default memory, window size), and a preview of the exact java command
  before launching.

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
- **No account, no store account and no uploads.** The Modrinth page browses and
  installs from the public catalogue, which answers without a key, and a modpack
  can also be imported from an archive you downloaded yourself. Nothing is ever
  uploaded: an exported pack is written into a folder you pick.
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
src/renderer/        Vue 3 + Vuetify UI (Play, Modrinth, Instances, Servers,
                     Settings, Logs)
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
- `npm run selfcheck` — 115 checks: the offline UUID, rule evaluation,
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
  where discovery looks for it, and which component answers for a version that
  names none), planning a whole version chain with its asset index, the check
  that decides which files a launch has to fetch, a Java path in Settings that
  no longer answers being passed over, profile/server/settings validation, the
  state store and the migration from the old single-directory state file, NBT
  read and written back (every tag type, gzipped and plain, and a truncated file
  refused by name), the zip writer round-tripped through the zip reader, and the
  worlds path: a world described by its own `level.dat`, a backup, an export and
  both imports, including the name collision and the backup taken before an
  install that then fails, then the server list: the varints and the two ping
  packets byte by byte, a status response read out of its frame, a description
  flattened and its colour codes stripped, a status turned into the line the list
  shows, the addresses the game writes (including bracketed IPv6), and
  `servers.dat` merged, round tripped through a real file, and refused when it is
  not a server list at all,  and the crash reader: a report's own description and
  the deepest `Caused by`, the mod list and the missing-dependency block in the
  shapes loaders actually write, and a clean log that produces nothing, plus the
  pack export: which files an instance carries (and which of its own it leaves
  behind), the index and its dependencies, and a whole instance written out and
  read back, including the instance with nothing in it that is refused.
- `npm run netcheck` — the same install path against live services: Mojang's
  manifest and a real version json, a real asset index, libraries and assets
  downloaded and hash-checked (664 KB), a real native jar unpacked (1.16.5's
  LWJGL), the 39.6 MB client jar fetched and verified, real Fabric and Quilt
  loader profiles parsed, Modrinth searched and resolved for the current release
  (a real Sodium build downloaded with its hash verified and its name read back
  out of the jar, plus a real project page and a modpack search), and Mojang's own
  Java runtime downloaded, unpacked, marked executable, run to confirm its
  version, and then found again by the resolver. Plus a real status ping:
  `mc.hypixel.net` answered in 70 ms during the last run, with its version and
  14870/200000 players.
- `npm run netcheck --launch` — opt-in, because it installs a whole version and
  takes a couple of minutes: 1.12.2 (1327 files, including the natives it still
  needs) is installed, the client jar, an asset and the natives directory are
  deleted, and the launch-time check fetches all three back and then reports
  nothing left to do on a second pass. `PATH` and `JAVA_HOME` are then blanked so
  no machine runtime can answer, the launcher downloads Java 8 for it, and the
  game is started with the command the launcher builds. It reaches its own
  graphics code — the LWJGL natives load from the instance's natives directory —
  and stops there because this environment has no display and no X11 client
  libraries. That is as far as a headless machine can go; the window itself has
  only been checked on a desktop.
- `npm run test` — 9 renderer tests: boot from the bridge, snapshot rendering, a
  UI action reaching the service bridge, error surfacing, navigation, the install
  picker installing what it offers into the selected instance, the worlds card
  listing a world and asking for a backup, the Modrinth page opening on the
  catalogue and then opening a project page, and the store loading the catalogue
  with no instance selected and no search word.
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

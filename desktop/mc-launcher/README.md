# MC Launcher

A small desktop launcher for an existing Minecraft installation, with **local
profiles only**. Electron + Vue 3 + Vuetify, TypeScript throughout.

It exists because the architecture is worth having in your own hands: an
isolated renderer, a preload bridge, a single generic service RPC channel, a
snapshot state protocol, and a launch pipeline built from the version json.

## What it does

- **Local profiles.** A name (your in-game name) and nothing else. The profile's
  UUID is derived from that name (`UUID.nameUUIDFromBytes("OfflinePlayer:<name>")`,
  an RFC 4122 v3 UUID) — no account is created, stored, or contacted.
- **Runs an installation that is already on disk.** It scans
  `<gameDir>/versions`, resolves `inheritsFrom` chains (vanilla + a mod loader),
  evaluates the per-OS `rules` on libraries, builds the classpath, and
  substitutes the version json's launch placeholders.
- **Server list and join.** Saved addresses plus a queued join. Modern versions
  get `--quickPlayMultiplayer host:port`; older ones get `--server/--port`.
- **Log view, task list, settings** (game directory, java binary, memory,
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
- **No game download or extraction.** It will not fetch versions, libraries,
  assets or natives, and it will not unpack native jars. If a version is
  incomplete, the launcher says exactly what is missing and stops; it does not
  fill the gap. Install and run a version once with the official launcher, then
  rescan.

Use it with a copy of the game you own.

## Where it lives

This app is part of the [Tool-Hubs](../../README.md) repository, at
`desktop/mc-launcher`. It is **not a Gradle module and is deliberately not in
`settings.gradle.kts`** — it is an Electron app on the Node toolchain, so the
Android build never touches it and it never touches `core/` or `tools/`.

Its version is its own, in [`package.json`](package.json), and moves
independently of Chime and Network Switcher. `0.1.1` is its first version here.

## Requirements

- Node.js 22.12+ (developed on 24)
- A Minecraft installation on disk (the folder holding `versions/`, `libraries/`
  and `assets/`), installed by the official launcher
- A JDK for the game: 17 for ≤1.20.4-era versions, 21 for 1.20.5+; the version
  json records what it wants (`javaVersion.majorVersion`)

## Development

```bash
npm install
npm run dev        # electron-vite dev, with HMR for the renderer
npm run verify     # typecheck + selfcheck + tests + build
npm run selfcheck  # launcher logic checks (launch plan, rules, state store)
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
  services/          profile, installation, server, launch, task, log, settings
  minecraft/         version json, rules, classpath, arguments, launch plan
src/preload/         contextBridge: serviceChannels, windowController, bootstrap, taskMonitor
src/renderer/        Vue 3 + Vuetify UI
src/shared/          types and channel constants shared by all three
scripts/selfcheck.ts logic checks, runnable under plain Node
```

The renderer never touches Node. It calls `window.serviceChannels.call(service,
method, params)`, which lands in `ServiceRegistry.call` in the main process;
unknown services and methods throw rather than being ignored. State flows back
as a snapshot event, so the UI keeps a working copy instead of guessing.

## Verification status

Verified locally on Linux with Node 24.21, and re-run unchanged after the app
was moved into this repository:

- `npm run typecheck` — clean for main, preload, shared and the Vue renderer.
- `npm run selfcheck` — 32 checks over the offline UUID, rule evaluation,
  inheritance, classpath/natives resolution, argument substitution and the
  launch plan (including the two failure paths), plus profile/server/settings
  validation and the state store.
- `npm run test` — 5 renderer tests: boot from the bridge, snapshot rendering,
  a UI action reaching the service bridge, error surfacing, and navigation.
- `npm run build` — main, preload and renderer bundles build.

## License

MIT. This is an original implementation: the architecture was studied from a
shipped Electron application, but no third-party source or assets were copied
into it. Keep it that way — anything taken from a GPL project changes the
licence of what you add.

**Not verified here:** the app was never launched. The build environment is a
headless container with no display, so `npm run dev` and an actual game launch
were not exercised. Argument ordering against a real loader version, native
library loading, and the java spawn path all need a run on a desktop.

**Also not verified:** the packaged installers. `electron-builder` cannot
cross-package, so the `.exe` is built by CI on `windows-latest` and has not been
run here. It is unsigned, so expect a SmartScreen warning on first launch.

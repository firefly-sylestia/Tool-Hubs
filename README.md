# Tool-Hubs

Tool-Hubs is a collection of focused tools. The Android tools share one Kotlin + Jetpack Compose
codebase: each is a standalone app module under `tools/`, with the shared visual language and
primitives living in `core/`. The desktop launcher under `desktop/` is the one exception — it is an
Electron app on a separate toolchain and does not build through Gradle.

## Modules

| Module | App | Status |
| --- | --- | --- |
| `:tools:chime` | Chime — alarms, reminders and a chiming clock | **Active development** |
| `:tools:network-switcher` | 5G Reconnect — network observability and automatic 5G recovery | **Active development** |
| `:tools:wallpaper-studio` | Wallpaper Studio — scenario-based wallpaper automation | **New** |
| `:tools:data-warden` | Data Warden — mobile data protection | Frozen |

Shared code:

| Module | Purpose |
| --- | --- |
| `:core:designsystem` | Theme tokens, colors, typography and the shared visual system |
| `:core:ui` | Reusable composables, navigation chrome and UI primitives |
| `:core:common` | Small shared helpers used across tools |

Desktop (not a Gradle module, so it is not part of the Android build):

| Path | App | Status |
| --- | --- | --- |
| `desktop/mc-launcher` | MC Launcher — a desktop launcher for an existing Minecraft installation | Newly imported |

> **Current focus: `:tools:chime`, `:tools:network-switcher` and `:tools:wallpaper-studio`.** The calculator, Music Analyzer and
> Music Visualizer apps have been removed from the repository, and Data Warden is left untouched —
> the shared theme tokens, header and metric primitives keep their existing values so it goes on
> rendering exactly as it did. See [`AGENTS.md`](AGENTS.md) for the working rules.

## Network Switcher (5G Reconnect)

5G Reconnect watches the radio and, when the phone falls back from 5G to LTE, re-triggers the
network so the device reacquires 5G. It uses [Shizuku](https://shizuku.rikka.app/) to run the
telephony commands, so it needs no root.

It also tracks observed 4G/5G traffic, speed, daily history and network transitions, and exposes
all of that through a Compose dashboard. See
[`tools/network-switcher/README.md`](tools/network-switcher/README.md) for the full feature and
architecture description, and [`docs/NETWORK_SWITCHER_FUTURE_TODO.md`](docs/NETWORK_SWITCHER_FUTURE_TODO.md)
for the roadmap.

## Chime (alarms, reminders, chiming clock)

Chime is an alarm clock built around the two things a phone alarm usually gets wrong: getting out of
bed, and keeping time without becoming a nuisance.

- **Dismissal is a choice, not a tap.** An alarm can require a button, a shake streak, or a QR or
  barcode scan — with an optional saved code so only *that* sticker dismisses it. The ring screen is
  full-screen over the lock screen and cannot be swiped away.
- **The clock strikes.** A synthesized pendulum bell rings the hour as many times as the hour, either
  every hour or only at times you pick. Quiet hours (22:00–07:00 by default) keep it silent at night,
  and the schedule is skipped rather than muted, so nothing wakes the phone in the first place.
- **The time is spoken on request or with a strike**, in words — "it's twenty past seven" — because
  engines read `07:20` as digits.
- **Reminders share the same full-screen treatment**, and the next thing up is shown as a silent
  countdown notification that ticks on the platform's own chronometer.

The bell is generated in code (inharmonic partials rendered to PCM) rather than shipped as an audio
file, and QR scanning runs on-device through CameraX and the bundled ML Kit barcode model.

## Wallpaper Studio (scenario-based wallpaper automation)

Wallpaper Studio keeps a local library of photos and applies a chosen image to the Home screen, Lock screen or both when a scene matches.

- **Unlock:** switch after Android reports that the keyguard has been dismissed.
- **Power:** switch when the charger is connected or removed, and when Android broadcasts low battery.
- **Wi-Fi:** switch when the active Wi-Fi network connects or disconnects.
- **Time:** schedule a daily scene using Android's battery-aware alarm delivery.
- **Conflict handling:** scenes have priorities; the highest matching scene wins, and a failed image falls through to the next eligible scene.
- **Quick actions:** up to four enabled scenes are published as Android app shortcuts, which can also be selected in compatible automation tools when they expose app shortcuts.
- **Privacy:** photos are selected through Android's system photo picker. Their persistable URI grants and scene rules stay on-device; the app does not upload images. Event-based scenes use a low-importance foreground-service notification only while an event scene is enabled; time-only scenes do not keep the monitor running.

Android provides a general user-present event, not the biometric method used for an unlock. Face Unlock therefore cannot be a distinct third-party trigger. On compatible Galaxy devices, Samsung Routines+ may expose an unlock-with-fingerprint condition; users can pair it with a Wallpaper Studio scene shortcut when the installed One UI / Good Lock version offers that action. Daily time alarms are inexact while the device is in deep Doze and may run later.

The app uses the shared Tool-Hubs Compose design system with a restrained, One UI-inspired layout. Build it with `gradle :tools:wallpaper-studio:assembleRelease`.

## MC Launcher (desktop)

An Electron + Vue 3 Minecraft launcher built around **instances**: one game directory each, with its
own loader, mods, resource packs, shader packs and worlds, all playing from **one shared copy of the
game**. It **downloads and installs** game versions from Mojang's manifest and Fabric or Quilt
loaders from their metadata into a launcher-owned folder that every instance plays from — verifying
every file against the hash its version json declares, resuming interrupted downloads, and
unpacking native libraries — and reuses any copy already on disk instead of downloading it again.
It resolves the version json (`inheritsFrom` chains, per-OS library rules), builds the classpath, shows
the exact java command, and launches. It finds a Java runtime itself (a runtime it has already downloaded, `JAVA_HOME`,
or an installed JDK) instead of assuming one is on PATH, downloads Mojang's own when none answers,
and every instance can be given its own memory limit and java path — one machine that plays both
1.12.2 and 1.21.1 needs two. It imports a folder (a game directory is used in place and its files
are reused from there, a single version folder goes into the shared game folder) and a modpack
(Modrinth `.mrpack`, or a CurseForge pack's `.zip`, into a new instance that installs the game and
loader the pack names). A version that fails to start is reported on the Play page with what the

game itself said. Mods, resource packs and shader
packs are turned on and off from the app, which renames them rather than keeping a list, and mods,
resource packs and shader packs can be searched and installed from Modrinth — filtered by the
instance's version and loader, with every file hash-checked and required dependencies followed. Each
jar is read, so the list shows a mod's real name, version and loader and flags one that will not load.
The window follows the desktop's light/dark preference or is pinned to one, over translucent, rounded
surfaces.

It is **local profiles only**: the UUID is the standard offline derivation, there is no account,
session or token, so it can only join servers configured with `online-mode=false`. There is no
CurseForge integration (its API needs a key), and no Forge, NeoForge or OptiFine installation.

Its own README, including the full layout and requirements, is at
[`desktop/mc-launcher/README.md`](desktop/mc-launcher/README.md). Its version lives in
[`desktop/mc-launcher/package.json`](desktop/mc-launcher/package.json) and moves independently of the
Android tools.

## Building

Requirements: JDK 17, the Android SDK with `compileSdk 37`, and Shizuku on the device for anything
that touches the radio.

```bash
# Release APK for the currently active tool
gradle :tools:chime:assembleRelease
gradle :tools:network-switcher:assembleRelease
gradle :tools:wallpaper-studio:assembleRelease

# Debug APK during development
gradle :tools:network-switcher:assembleDebug
```

There is no Gradle wrapper checked in; use your local `gradle` (CI pins `9.3.1`).

The desktop launcher is built with Node 24 instead:

```bash
cd desktop/mc-launcher
npm ci
npm run verify     # typecheck + logic checks + renderer tests + bundle
npm run dist:win   # package: also dist:linux and dist:mac
```

Release builds sign with the real keystore when `RELEASE_KEYSTORE_PATH` (plus
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) is set, and fall back to a
throwaway debug key otherwise so fork and pull-request builds still work.

## CI

[`.github/workflows/android.yml`](.github/workflows/android.yml) builds the release APK for every
module on `main` and `dev` pushes, on `v*` release-tag pushes and on pull requests, and uploads the
APKs as artifacts (one-day retention). A `prune` job clears artifacts older than a day and all but
the ten newest caches, because Actions storage is one account-wide quota. CI is the only place
builds run — see [`AGENTS.md`](AGENTS.md).

[`.github/workflows/desktop.yml`](.github/workflows/desktop.yml) does the same for the desktop
launcher, but only for commits that touch `desktop/`. It verifies the app on Linux and then packages
it on Ubuntu, Windows and macOS, so the Windows `.exe` (unsigned, NSIS), the `.dmg`, the `.deb` and
the `.AppImage` are downloadable from a run's artifacts (one-day retention).

```bash
# build installers from the current state of main
gh workflow run desktop.yml --ref main
```

## Docs

- [`AGENTS.md`](AGENTS.md) — working rules for AI agents in this repository.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — versioning, release and commit process.
- [`CHANGELOG.md`](CHANGELOG.md) — released versions and their changes.
- [`desktop/mc-launcher/README.md`](desktop/mc-launcher/README.md) — the desktop launcher's layout,
  launch pipeline and what it deliberately does not do.
- [`docs/NETWORK_SWITCHER_FUTURE_TODO.md`](docs/NETWORK_SWITCHER_FUTURE_TODO.md) — planned redesign and roadmap.
- [`TODO.md`](TODO.md) — delivery checklist for completed passes.

# Tool-Hubs

Tool-Hubs is a collection of focused Android tools that share one Kotlin + Jetpack Compose
codebase. Each tool is a standalone app module under `tools/`, with the shared visual language and
primitives living in `core/`.

## Modules

| Module | App | Status |
| --- | --- | --- |
| `:tools:network-switcher` | 5G Reconnect — network observability and automatic 5G recovery | **Active development** |
| `:tools:data-warden` | Data Warden — mobile data protection | Frozen |
| `:tools:calculator` | Calculator | Frozen |
| `:tools:music-analyzer` | Music Analyzer | Frozen |
| `:tools:music-visualizer` | Music Visualizer | Frozen |

Shared code:

| Module | Purpose |
| --- | --- |
| `:core:designsystem` | Theme tokens, colors, typography and the shared visual system |
| `:core:ui` | Reusable composables, navigation chrome and UI primitives |
| `:core:common` | Small shared helpers used across tools |

> **Current focus: `:tools:network-switcher` only.** The other tools are deliberately untouched —
> their theme tokens, shared header and metric primitives are frozen at their existing values so
> they keep behaving exactly as they did. See [`AGENTS.md`](AGENTS.md) for the working rules.

## Network Switcher (5G Reconnect)

5G Reconnect watches the radio and, when the phone falls back from 5G to LTE, re-triggers the
network so the device reacquires 5G. It uses [Shizuku](https://shizuku.rikka.app/) to run the
telephony commands, so it needs no root.

It also tracks observed 4G/5G traffic, speed, daily history and network transitions, and exposes
all of that through a Compose dashboard. See
[`tools/network-switcher/README.md`](tools/network-switcher/README.md) for the full feature and
architecture description, and [`docs/NETWORK_SWITCHER_FUTURE_TODO.md`](docs/NETWORK_SWITCHER_FUTURE_TODO.md)
for the roadmap.

## Building

Requirements: JDK 17, the Android SDK with `compileSdk 37`, and Shizuku on the device for anything
that touches the radio.

```bash
# Release APK for the currently active tool
gradle :tools:network-switcher:assembleRelease

# Debug APK during development
gradle :tools:network-switcher:assembleDebug
```

There is no Gradle wrapper checked in; use your local `gradle` (CI pins `9.3.1`).

Release builds sign with the real keystore when `RELEASE_KEYSTORE_PATH` (plus
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) is set, and fall back to a
throwaway debug key otherwise so fork and pull-request builds still work.

## CI

[`.github/workflows/android.yml`](.github/workflows/android.yml) builds the release APK for every
module on `main` and `dev` pushes and pull requests, and uploads the APKs as artifacts. CI is the
only place builds run — see [`AGENTS.md`](AGENTS.md).

## Docs

- [`AGENTS.md`](AGENTS.md) — working rules for AI agents in this repository.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — versioning, release and commit process.
- [`CHANGELOG.md`](CHANGELOG.md) — released versions and their changes.
- [`docs/NETWORK_SWITCHER_FUTURE_TODO.md`](docs/NETWORK_SWITCHER_FUTURE_TODO.md) — planned redesign and roadmap.
- [`TODO.md`](TODO.md) — delivery checklist for completed passes.

# 5G Reconnect (`:tools:network-switcher`)

5G Reconnect watches the phone's radio and, when the modem drops from 5G to LTE, re-triggers the
network so the device reacquires 5G. It also acts as a network observability dashboard: observed
4G/5G traffic, speed, transitions and incidents, all in one place.

It talks to the telephony stack through [Shizuku](https://shizuku.rikka.app/) — no root required.

- **App label:** 5G Reconnect
- **Application id:** `com.sylestia.toolhubs.networkswitcher`
- **Min SDK:** 26 · **Target/compile SDK:** 37
- **Version:** see `versionCode` / `versionName` in [`build.gradle.kts`](build.gradle.kts)

## Features

### Automatic 5G recovery
- Detects a real LTE fallback and re-triggers the radio to reacquire 5G.
- Non-standalone 5G (LTE data type, NR advertised) is correctly treated as 5G, so NSA networks do
  not trigger a recovery loop.
- Retry back-off with a circuit breaker: repeated failures pause recovery and auto-resume with a
  probe. Back-off is persisted against wall-clock time so a reboot does not stall it.
- Environment failures (Shizuku not running, binder died, permission revoked) are distinguished from
  radio failures and retried quickly instead of consuming the retry budget.
- Optional pause while a call is active, and while a backup SIM is carrying data.

### SIM failover
- No-service failover: move mobile data to a backup SIM when the monitored SIM has no service.
- RAT-driven failover: while the monitored SIM is on LTE, move data to a backup SIM and move it back
  when 5G returns. A cooldown and a persisted active flag stop it re-firing every tick.

### Data guard
- Optional daily LTE limit with a progress meter; a dedicated notification fires once per day when
  today's observed LTE traffic crosses the threshold.

### Telemetry and history
- Today's and lifetime observed 4G/5G traffic, live speed and daily peak speed.
- Per-day buckets surfaced as a 7-day history.
- Minute-level interval history with fake/excluded interval review.
- Events page with a timeline graph and duplicate grouping.

### Controls
- Foreground watcher service with an ongoing notification showing the radio and watcher state.
- Quick Settings tile to toggle the watcher.
- Restores the enabled state across reboots and app updates.
- Light/dark appearance control with a night-qualified window theme.

## Screens

The app is a five-page bottom-navigation hub:

| Page | Icon | Contents |
| --- | --- | --- |
| Watch | `NetworkCheck` | Live radio state, speed, signal and session telemetry |
| Events | `Timeline` | Transition/incident timeline with duplicate grouping |
| Data | `DataUsage` | Today, live speed, 4G warning meter, 7-day history |
| History | `History` | Minute-level interval review and fake-interval controls |
| Tune | `Tune` | Automation, data guard and diagnostics |

## Architecture

All source lives in `src/main/java/com/sylestia/toolhubs/networkswitcher/`.

| File | Responsibility |
| --- | --- |
| `MainActivity.kt` | Compose UI, navigation and the dashboard pages |
| `NetworkState.kt` | `RadioState` + `RadioWatcher`, the single source of radio truth shared by the UI and the watcher |
| `AutoReconnectService.kt` | Foreground watcher service: state machine, recovery, notifications |
| `AutoReconnectBootReceiver.kt` | Restores the watcher after boot / package replace |
| `NetworkReconnectTileService.kt` | Quick Settings tile |
| `NetworkUsageTracker.kt` | Traffic/speed sampling and 4G/5G attribution |
| `UsageHistoryStore.kt` | Bounded minute-level history persisted to disk |
| `ShizukuHealth.kt` | Shizuku readiness state machine, exception-safe |
| `NetworkSwitcherBridge.kt` | Binds the Shizuku user service |
| `NetworkModeService.kt` | `INetworkModeService` implementation running under Shizuku |
| `src/main/aidl/.../INetworkModeService.aidl` | User-service interface (bump its version on AIDL changes) |

## Permissions

`READ_PHONE_STATE`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE` (+ `_SPECIAL_USE`),
`POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, plus a
Shizuku provider entry.

## Requirements

- Android 6.0 (API 26) or newer.
- [Shizuku](https://shizuku.rikka.app/) installed and running for anything that changes the network.
- Non-standalone 5G is only distinguishable on Android 11+; below that, recovery is inert by design.
- `cmd phone set-default-data-subscription` is vendor-dependent and may be rejected by a device even
  through Shizuku.

## Known limitations

- Android exposes aggregate mobile traffic, not carrier-billed bytes split by RAT, so the 4G/5G
  split is an observation rather than a billing counter. Exact "data saved" cannot be derived.
- The data guard and traffic accounting need the foreground watcher running.
- Some OEMs restrict boot foreground-service starts and battery-optimisation exemptions; the app can
  guide users but cannot guarantee the exemption.
- Network causes are sometimes inferred and are labelled as likely, never stated as fact.

## Building

Do not build in the agent environment — CI is the compiler. Locally:

```bash
gradle :tools:network-switcher:assembleDebug
gradle :tools:network-switcher:assembleRelease
```

See [`../../AGENTS.md`](../../AGENTS.md) for versioning and commit rules, and
[`../../docs/NETWORK_SWITCHER_FUTURE_TODO.md`](../../docs/NETWORK_SWITCHER_FUTURE_TODO.md) for the
roadmap.

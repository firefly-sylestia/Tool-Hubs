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

### Live dashboard
- A live graph on the Watch page with four metrics (speed, traffic, latency, signal) and four windows
  (1, 5, 15, 30 minutes), drag-to-inspect. Speed and traffic are sampled by the watcher; latency and
  signal only while the screen is open, and the card says which. Windows with no sample are drawn as a
  break, never joined up into a line that looks like a quiet period.
- Network Health: a weighted, explainable score over 5G uptime, LTE fallbacks, radio stability,
  recovery success and speed, and flagged data. Every factor, its weight and its evidence are shown,
  and factors without evidence are dropped and the rest re-weighted instead of scoring zero.
- Recovery analytics: success rate, average/fastest/slowest confirmed recovery, current and longest
  failure streak, fallback count and time, longest fallback, 5G uptime, longest 5G session, radio
  churn per hour and a seven-day breakdown.
- "Why did it change?": the last radio transition explained in words, with its evidence and a
  confidence label. Causes the app recorded itself are labelled *recorded*; everything the modem never
  reports is labelled *likely*, because Android exposes no reason for a re-selection.

### Activity and usage timelines
- The Activity page groups the log into 24 hourly buckets, draws them as a stacked incident timeline
  and filters the list to the hour you pick. Filters cover All / Success / Failed / Network / SIM /
  Recovery, and tapping an event opens a recorded BEFORE → EVENT → ACTION → AFTER sequence.
- The usage timeline offers 1 hour / 6 hours / 24 hours / 7 days, stacks 4G under 5G, compares the two
  for the whole range, and opens the exact one-minute intervals behind any bar. Intervals marked fake
  stay listed so a correction can be undone.

### Plausibility filtering
Android's mobile counters are an aggregate observation, not a carrier billing record, and they can
leap upward when the interface set changes without ever going backwards. Three rules keep impossible
figures off the screen:

- A sampling window shorter than half a second is never turned into a rate. The watcher ticks once a
  second, so a much shorter window means the sample was re-scheduled, and dividing a burst by a few
  milliseconds is what used to produce gigabit-per-second "spikes".
- A rate above `NetworkUsageTracker.MAX_PLAUSIBLE_BPS` is a counter discontinuity, not traffic. Its
  bytes are excluded, the speed peak is left untouched, and the watcher records a visible event.
- Rates between `SUSPECT_SPIKE_BPS` and the ceiling are still counted — they are possible on fast 5G —
  but are flagged so they appear in the History review list.

On top of that, every sample is reconciled against the device's own aggregate counter. The Data page's
`Counter check` card shows the device total for the session next to the bytes attributed to 4G and 5G,
what was deliberately set aside, and the residual (traffic used while the watcher was off or on Wi-Fi).
A negative residual would mean the app credited more than the device moved, which is the one thing that
can actually prove the split wrong; a positive one is expected.

Each interval also carries a unique id. The review list addresses a row by id, so sharing the minute
bucket across records made "mark interval fake" flip a different record — the id is now unique and the
list sorts by `(startAt, id)` so ordering is stable between reads.

### Controls
- Foreground watcher service with an ongoing notification showing the radio and watcher state, the
  live cooldown countdown, the failed-attempt count, the Shizuku state and a contextual action
  (Pause, Resume, or Retry now). Entering a cooldown also posts a one-off alert.
- Watcher status is composed from state in one place, so a settings refresh or a Shizuku reconnect
  cannot hide an active cooldown from the notification or the app.
- Quick Settings tile to toggle the watcher.
- Restores the enabled state across reboots and app updates.
- Light/dark appearance control with a night-qualified window theme.

## Screens

The app has four bottom-navigation tabs plus a settings page that is pushed on top of them:

| Page | Icon | Contents |
| --- | --- | --- |
| Watch | `NetworkCheck` | Live radio state, live graph (speed/traffic/latency/signal), health score, why-it-changed, session telemetry |
| Events | `Timeline` | Interactive 24-hour incident timeline, six filters, expandable incident detail |
| Data | `DataUsage` | Today, live speed, recovery analytics, 4G warning meter, 7-day history |
| History | `History` | Usage timeline with bar-to-interval drill-down and fake-interval controls |
| Settings | gear in the page header | Appearance, recovery, privileged access, SIM choice, data guard, diagnostics |

The tab bar keeps every slot the same width and slides one accent pill between them, so a tap is a
single animation rather than a re-measure of the whole bar. Settings is deliberately *not* a tab: it
is reached from the header button on any page, closes with the system back gesture, and hides the tab
bar while open. `SwitcherNav.kt` owns this chrome and lives in the module rather than in `:core:ui`,
because the shared `HubBottomNav` is still used by the frozen tools.

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
| `LiveTelemetry.kt` | Process-wide ring buffer for the live graphs, plus the latency probe |
| `NetworkInsights.kt` | Health score, recovery analytics and the likely-cause explanations |
| `NetworkGraphs.kt` | The interactive live, incident and usage-timeline graphs |
| `SwitcherNav.kt` | Module-local bottom navigation, scroll-reactive page header and settings header |
| `SwitcherBackdrop.kt` | Module-local animated backdrop (orbiting light, drifting grid and particles) |
| `ShizukuHealth.kt` | Shizuku readiness state machine, exception-safe |
| `NetworkSwitcherBridge.kt` | Binds the Shizuku user service |
| `NetworkModeService.kt` | `INetworkModeService` implementation running under Shizuku |
| `src/main/aidl/.../INetworkModeService.aidl` | User-service interface (bump its version on AIDL changes) |

## Permissions

`READ_PHONE_STATE`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE` (+
`_SPECIAL_USE`), `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, plus a Shizuku provider entry.

`INTERNET` is used for exactly one thing: the live latency graph measures a single TCP handshake to a
public resolver while the Watch screen is open. Nothing is uploaded, no other code path touches the
network, and the probe stops when the screen closes.

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
- The latency graph measures a TCP handshake, not an ICMP ping: Android gives an unprivileged app no
  ICMP echo, so the two are not the same measurement and the card says so.
- Live latency and signal history exists only for the time the Watch screen was open. Speed and
  traffic history is extended by the watcher, but it lives in memory for the half hour the graphs
  cover — older traffic is in the persisted minute intervals on the History page.

## Building

Do not build in the agent environment — CI is the compiler. Locally:

```bash
gradle :tools:network-switcher:assembleDebug
gradle :tools:network-switcher:assembleRelease
```

See [`../../AGENTS.md`](../../AGENTS.md) for versioning and commit rules, and
[`../../docs/NETWORK_SWITCHER_FUTURE_TODO.md`](../../docs/NETWORK_SWITCHER_FUTURE_TODO.md) for the
roadmap.

# Tool Hubs delivery checklist

## Desktop launcher import — MC Launcher 0.1.1

- [x] The Electron + Vue 3 launcher sits at [`desktop/mc-launcher`](desktop/mc-launcher) with its own
      `package.json`, lockfile and Node toolchain. It is intentionally not a Gradle module and is not
      referenced by `settings.gradle.kts`; no `core/*` or `tools/*` file was touched to make room for it.
- [x] [`desktop.yml`](.github/workflows/desktop.yml) verifies the app on Linux and packages installers
      on Ubuntu, Windows and macOS, path-filtered to `desktop/` so Android runs are unaffected.
- [x] Version `0.1.1` in `desktop/mc-launcher/package.json`, the file that is the source of truth for
      this tool's version from here on.
- [x] Packaging fixed at the cause after CI's first attempt: no `GITHUB_TOKEN` in the packaging step and
      `publish: null` in `electron-builder.yml`, which is what stopped electron-builder resolving a
      publisher it could not build and crashing after the installer was written.
- [x] `homepage`, `repository` and a `.deb` maintainer in the package metadata, which the `deb` target
      requires and the `AppImage` run had hidden.
- [x] Re-verified after the move: `npm run verify` — typecheck clean, 32 launcher logic checks, 5
      renderer tests, main/preload/renderer bundles built.

### Still to verify on a desktop
- [ ] `npm run dev` and a real game launch: the java spawn path, native library loading and argument
      ordering against a real mod-loader version. The container has no display.
- [ ] The packaged Windows `.exe` — `electron-builder` cannot cross-package, so CI builds it and nobody
      has run it yet. The first successful CI package run and its download are still pending.

## Chime 0.2.0 — interface rebuild

- [x] Rebuilt every Chime screen on `ToolHubsTheme` and the `:core:ui` kit. The app was rendering
      inside a bare `MaterialTheme`, so it fell back to framework greys and platform dialogs; pages
      are now one gutter of glass cards with icon-led headings and the app's own nav bar.
- [x] Replaced `TimePickerDialog` / `DatePickerDialog` with Chime's own snapping wheel and calendar
      bottom sheets, which follow the theme and the locale's first day of the week.
- [x] Clinched clocks: a system / 12-hour / 24-hour choice threaded through the clock face, the
      lists, the next-up card, both notifications and the spoken time.
- [x] The bell and the spoken time are independent switches, so either or both can ring, and the
      schedule (off / hourly / set times) decides when.
- [x] Strike counts are decided at arm time and travel with the intent, and a "set time" is a single
      chime rather than a count of the hour.
- [x] Dismiss codes are scanned to register them instead of typed.
- [x] Permission block for notifications, full-screen alarms, exact alarms and the camera, with
      Android 14's `canUseFullScreenIntent()` checked rather than assumed.
- [x] Version bumped to `versionCode 3` / `versionName 0.2.0`.

### Still to verify on a device
The 12/24-hour strings, the wheel snapping, the full-screen grant flow and the scan-to-register
round trip need a device; CI only proves the module compiles.

## Observability pass — live graph, health, explanations, timeline redesigns

### Live graph (Watch)
- [x] New `LiveTelemetry` ring buffer (in memory, ~30 minutes at the watcher's cadence), fed by the
      usage tracker for speed and byte deltas. A gap in the buffer is drawn as a break in the line,
      never joined up, so "the watcher was off" cannot look like "the network was quiet".
- [x] Four metrics: Speed, Data, Latency, Signal. Speed buckets keep the fastest sample so a brief
      peak is not averaged away; Data buckets sum the observed byte deltas.
- [x] Four windows: 1 / 5 / 15 / 30 minutes, with drag-to-inspect and a crosshair that shares one
      bucket index with the readout above it.
- [x] Latency is a real TCP handshake to a public resolver, measured only while the screen is open,
      and labelled as a round trip rather than a ping. Signal is Android's 0–4 level, labelled as a
      coarse bucket rather than a dBm value.

### Network health and recovery analytics
- [x] Explainable health score with per-factor points, weights and evidence, graded
      Excellent / Good / Fair / Poor.
- [x] Factors without evidence are dropped and the remaining weights renormalised, so a fresh install
      reports "Collecting" instead of a fabricated score.
- [x] Recovery analytics: attempts, successes, failures, success rate, average, fastest, slowest,
      current and longest failure streak, fallback count and duration, longest single fallback, 5G
      uptime, longest 5G session, radio churn per hour, and a seven-day breakdown.
- [x] The watcher now records fastest/slowest confirmed recovery and the longest failure streak; it
      previously stored only a mean, which hid both extremes.

### "Why did it change?"
- [x] The most recent radio transition is explained in words with its evidence listed and a
      confidence label: **recorded** when the app's own log proves it (a data-SIM move, a recovery it
      triggered), **likely** when it is inference.
- [x] Supporting rows: previous and new state, exact time, signal level, recovery attempts in the
      preceding two minutes and the 12-hour fallback count.
- [x] The card states plainly that Android does not report the modem's reason for a re-selection.

### Activity page redesign
- [x] Replaced the hourly bar sparkline with an interactive incident timeline: hourly buckets stacked
      by kind, severity colouring, tap or drag to select an hour, and a clear-hour-filter action.
- [x] Six filters (All / Success / Failed / Network / SIM / Recovery) as a scrollable chip row, since
      six options do not fit a segmented control. The hour filter is applied before the type filter.
- [x] Recorded incident detail that opens in place as BEFORE → EVENT → ACTION → AFTER, showing exact
      timestamps, the group span and the next event in the log. Per-event signal/thermal snapshots are
      not shown because the watcher never stored them; inventing them would be worse than omitting them.
- [x] Page-level stats for events, groups, failures, recovery events and hours with failures.
- [x] The obsolete "feature recommendations" card, which recommended features that now exist, was
      replaced with advice that names a switch that exists and reads the whole log, not the filter.

### Usage timeline redesign
- [x] Ranges are now 1 hour / 6 hours / 24 hours / 7 days (previously 6h / 24h / 7d with a 15-minute
      hidden bucket size).
- [x] Bars are stacked as 4G under 5G, with a 4G-vs-5G comparison and busiest-bucket figure for the
      whole range.
- [x] Tapping or dragging a bar opens the exact one-minute intervals it was built from; the previous
      chart compressed minutes into a line and left the exact records in an unconnected list.
- [x] Fake intervals are excluded from the totals but still listed, so marking a row fake does not hide
      it from the user who just marked it and needs to undo it.

### Packaging
- [x] Added `INTERNET` for the latency probe only, with the reason stated in the manifest and README.
- [x] Version bumped to `versionCode 15` / `versionName 0.11.0`.

### Not done in this pass
The remaining roadmap items (Smart Modes, visual automation rules, Safe Mode, Diagnostics and
Explain-this panels, Network Test, SIM comparison, weekly report, Network Story) are untouched and
still listed in `docs/NETWORK_SWITCHER_FUTURE_TODO.md`.


## Data accuracy, warnings & SIM failover — previous pass

### Observed traffic & speed
- [x] The dashboard reads *today's* bucket instead of the lifetime total, so the 4G/5G figures reset
      at midnight and tomorrow starts from zero.
- [x] Per-day buckets (`usage_lte_bytes:<yyyy-MM-dd>`) are surfaced as a 7-day history, each row
      carrying that day's traffic split and peak speed.
- [x] Peak speed is tracked per day (`usage_peak_bps:<day>`) and a live speed sample
      (`usage_current_bps`) is recorded alongside it, so the peak no longer climbs forever.
- [x] Cross-session over-count fixed: `TrafficStats` keeps counting while the watcher is stopped, so
      restoring the previous session's counter dumped every byte used while the app was off onto the
      last radio label. Baselines are now session-local and a delta is only attributed between two
      consecutive samples of the same session.
- [x] Bytes observed while the network is unknown are still dropped rather than assigned to 4G or 5G.

### 4G data warning
- [x] New "Data guard" settings card: enable a daily LTE limit and set the threshold in GB.
- [x] The watcher posts a dedicated "Data warnings" notification once per day, the first time today's
      observed LTE traffic crosses the limit, and records a `warning` history event.
- [x] The Data page shows a live progress meter against the limit.

### RAT-driven SIM failover
- [x] New option: while the monitored SIM is on LTE, move mobile data to a backup SIM; when the
      monitored SIM reports 5G again, move it back.
- [x] A 60-second cooldown plus a persisted ACTIVE flag stop one LTE stretch from firing the switch
      on every tick. State only flips once the Shizuku command confirms, so a failed attempt simply
      retries after the cooldown.
- [x] Kept separate from the existing "no service" SIM failover.

### UI
- [x] Bottom nav refreshed with clearer icons (`NetworkCheck` / `Timeline` / `DataUsage` / `Tune`) and
      an icon-size spring on the selected tab.
- [x] The radio orb now carries five animated signal bars that shimmer and light up per generation —
      five for 5G, three for LTE — so the LTE/5G state reads at a glance.
- [x] Data page reorganised: Today card, live speed + daily peak, 4G warning meter and a 7-day history.

### Notifications
- [x] The ongoing notification no longer goes stale: `publishState()` now writes the status and pushes
      the *same* text to the tray in one call. Previously some call sites stored a status without
      notifying (so the tray stayed on "Starting background watcher…") while others notified with a
      different string than they stored (so the tray contradicted the app).
- [x] Headline is now explicit about the radio and the watcher: "5G Reconnect · on 5G / on 4G /
      no service / paused", with a sub-text for background vs manual.
- [x] Updates are guarded until `startForeground` has run, so the tray never gets a detached copy of
      the service notification during cold start.
- [x] Short-lived progress lines (an in-flight attempt, a SIM switch result) update the tray without
      overwriting the persisted status.

### Packaging
- [x] Version bumped to `versionCode 3` / `versionName 0.3.0`.
- [x] Release builds sign with the real keystore when `RELEASE_KEYSTORE_PATH` is present, and fall
      back to the throwaway debug key otherwise so fork PR builds still work. The workflow decodes
      the `KEYSTORE_BASE64` secret and exports the signing environment.

## Known platform limitations — data guard
Documented rather than left as unfinished work:
- [x] `TrafficStats` is device-wide and is not split by subscription, so the observed 4G/5G split stays
      an observation. Cross-session gaps are now excluded instead of being inflated.
- [x] The data warning needs the foreground watcher to be running; the Tune card says so.
- [x] RAT-driven SIM switching needs Shizuku and a vendor that honours
      `cmd phone set-default-data-subscription`; the result is recorded either way.

## 5G Reconnect (network-switcher) — previous pass

### Shizuku detection
- [x] `ShizukuHealthMonitor.refresh()` is now exception-safe. `Shizuku.checkSelfPermission()` throws
      when the binder dies between `pingBinder()` and the permission read; that escaped `refresh()`,
      cancelled the watcher coroutine and left monitoring dead until the service was restarted.
- [x] Health changes are pushed to observers instead of only being polled, so a recovered binder
      resets the retry budget immediately.
- [x] Every UI call site of `refresh()` is safe to run from composition.
- [x] The user-service bridge keeps the Shizuku process warm, retries one bind timeout, and reports
      "Shizuku isn't running" distinctly from a radio failure.

### Auto recovery
- [x] Reconnect attempts are gated on Shizuku being `READY`. Previously a missing tool consumed the
      retry budget and extended the back-off, so fixing Shizuku left the watcher parked for minutes —
      the "toggle it off and on again" symptom.
- [x] The circuit breaker actually opens. The old failure branch assigned `circuitOpen = false`, so
      the "paused after repeated failures" state and its auto-resume probe were unreachable.
- [x] Back-off is persisted against wall-clock time as well as `elapsedRealtime`. After a reboot
      `elapsedRealtime` restarts at zero, so a stale value used to stall recovery for ~1.4 hours.
- [x] Environment failures (binder/bind/permission) do not count as radio failures and retry quickly.
- [x] The watcher loop cannot die: every tick runs inside `runCatching`, and absorbed errors are
      recorded at most once a minute.
- [x] Paused state survives a `START_STICKY` service recreation.
- [x] Enabling automatic reconnect while Shizuku is down now arms the watcher instead of refusing.

### Auto 5G detection
- [x] Detection moved into a single `RadioWatcher`/`RadioState` pair shared by the screen and the
      watcher, so they can no longer disagree about the radio.
- [x] The 5G override flag is `@Volatile`; it was a plain field written on the main executor and read
      from a background dispatcher.
- [x] Android 11 (API 30) is handled explicitly via `PhoneStateListener`, Android 12+ via
      `TelephonyCallback`, and older releases are labelled "5G state not detectable" instead of being
      misread as a permanent LTE fallback.
- [x] Non-standalone 5G (LTE data type, NR advertised) is treated as 5G, so the watcher no longer
      re-triggers the radio in a loop on NSA networks.

### UI
- [x] Full redesign on a shared Aurora design system: animated aurora backdrop, glass cards,
      breathing radio orb with expanding halo rings, spring-pressed buttons, floating nav, staggered
      reveals and animated page transitions.
- [x] Light and dark palettes are complete Material 3 schemes, and switching appearance cross-fades
      every canvas-level role instead of hard-cutting.
- [x] A night-qualified window theme removes the white cold-start flash in dark mode, and status-bar
      icon brightness follows the in-app choice.

## Known platform limitations
Documented rather than left as unfinished work:
- [x] Exact "data saved" cannot be derived from Android traffic counters; it needs a carrier plan
      model. The product reports observed traffic by network state instead.
- [x] `cmd phone set-default-data-subscription` is vendor-dependent and can be rejected by a device
      even via Shizuku; the UI records the returned result.
- [x] Non-standalone 5G is only distinguishable on Android 11+. Below that, recovery is inert by
      design rather than guessed at.
- [x] Some OEMs restrict boot foreground-service starts and battery optimisation exemptions. The app
      can guide users but cannot guarantee exemption.

## Deliberately untouched
Data Warden was outside the scope of this request. Its theme tokens, shared header and metric
primitives are frozen at their previous values so it renders and behaves exactly as before. The
calculator, Music Analyzer and Music Visualizer modules have since been deleted from the repository
at the user's request.

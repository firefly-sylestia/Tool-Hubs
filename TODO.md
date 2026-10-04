# Tool Hubs delivery checklist

## Data accuracy, warnings & SIM failover — latest pass

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
The calculator, Data Warden and Music Analyzer were outside the scope of this request. Their theme
tokens, shared header and metric primitives are frozen at their previous values so they render and
behave exactly as before.

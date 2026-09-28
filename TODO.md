# Tool Hubs delivery checklist

## 5G Reconnect (network-switcher) — this pass

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

# Changelog

All notable changes to Tool-Hubs are recorded here, newest first.

Network Switcher is the actively developed module. Some earlier release bumps were made without a
version-named commit, so the entries below `0.7.1` are reconstructed from commit history and grouped
conservatively rather than split into invented version numbers.

Each entry corresponds to the `versionName` in
[`tools/network-switcher/build.gradle.kts`](tools/network-switcher/build.gradle.kts). See
[`CONTRIBUTING.md`](CONTRIBUTING.md) for how versions are chosen.

## [0.8.0]

### Fixed
- Impossible traffic and speed spikes are no longer shown or counted. A burst faster than the
  cellular link can physically go is treated as a counter discontinuity: its bytes are excluded, the
  speed peak is left alone, and the event is recorded instead of silently dropped.
- Sampling windows shorter than half a second are never turned into a rate, which is what used to
  manufacture gigabit-per-second readings out of a re-scheduled sample.
- The daily and lifetime speed peaks can no longer be poisoned by a single impossible sample, and
  legacy over-ceiling values are clamped when displayed.
- A recovery cooldown is now visible everywhere. Watcher status is derived from state instead of
  written as a literal at each call site, so changing a setting or Shizuku reconnecting no longer
  wipes the cooldown message from the notification and the app.
- The cooldown countdown refreshes instead of freezing on the moment it started, and entering a
  cooldown posts a real alert notification.
- Shizuku failures no longer consume the attempt budget. Three transient bind errors inside the burst
  window used to trip the retry ceiling and park recovery on the full cooldown even though nothing
  was wrong with the network.
- Changing a setting no longer resumes a watcher the user paused. Settings are applied on the next
  resume instead, and resuming reloads them.

### Changed
- Bottom navigation redesigned: equal-width tabs with a single sliding accent pill, so a tap is one
  animation instead of a full re-measure of the bar.
- Settings is no longer a navigation tab. It is opened by a header button and pushed as its own page
  with back-out, and the tab bar now carries only the four screens you move between.
- The ongoing notification shows the live cooldown, the failed-attempt count, the Shizuku state and
  a contextual action: Pause, Resume, or Retry now while cooling down.
- Settings gained a status-notification card that warns when notifications are disabled, since an
  invisible foreground notification is easily mistaken for a watcher that is not running.
- The Data page's dead "History" shortcut now opens the History page.

## [0.7.3]

- Redesigned the usage-history graph and added interval review.
- Redesigned the Events page with a timeline graph and duplicate grouping.
- Fixed usage-history Compose colors and the usage-history empty state.
- Fixed Network Switcher release compile errors.
- Added the project README, agent rules, contributing guide and roadmap docs.

## [0.7.2]

- Added a minute-level network usage history store.
- Added interval history with manual fake/excluded interval controls and fixed fake interval
  accounting; impossible traffic counter spikes are rejected.
- Added the usage-history graph and the interval review screen.
- Moved usage-history file work off the UI thread and throttled pruning.
- Fixed the usage-history empty state.

## [0.7.1]

- Pause 5G retries while a backup SIM is carrying data.
- Added the backup SIM 5G recovery pause toggle.

## [0.7.0 and earlier]

Reconstructed from commit history — these releases were bumped without a version-named commit, so
their boundaries are approximate.

- Added call pause: automation pauses during a call, with a toggle shown independently of SIM count.
- Clamped legacy impossible speed readings.
- Used the framework API for Samsung data SIM switching; backup SIM switching now verifies its
  result and supports a Samsung fallback.
- Added experimental Samsung RF recovery controls, Samsung band selection via Shizuku, thermal
  service bridge controls and tactile thermal action buttons — later hidden behind the experimental
  set.
- Data accuracy and SIM failover pass (`0.3.0`): today's usage bucket with a 7-day history, per-day
  peak speed, session-local traffic baselines, the daily 4G data guard with a warning notification
  and progress meter, and RAT-driven SIM failover.
- Reliability pass: exception-safe Shizuku health monitoring, recovery gated on Shizuku readiness,
  a working circuit breaker, wall-clock-persisted back-off, and a watcher loop that cannot die.
- Auto 5G detection moved into a single shared `RadioWatcher`/`RadioState` pair, with explicit
  Android 11 `PhoneStateListener` handling and non-standalone 5G treated as 5G.
- Aurora design system: animated backdrop, glass cards, breathing radio orb, light and dark Material
  3 palettes, and a night-qualified window theme.
- Notification status drift fixed, daily usage accounting corrected, and release signing made
  keystore-aware with a debug-key fallback.
- Quick Settings tile, boot/package-replace restore, and the foreground watcher service.

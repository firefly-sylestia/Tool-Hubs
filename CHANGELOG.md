# Changelog

All notable changes to Tool-Hubs are recorded here, newest first.

Network Switcher is the actively developed module. Some earlier release bumps were made without a
version-named commit, so the entries below `0.7.1` are reconstructed from commit history and grouped
conservatively rather than split into invented version numbers.

Each entry corresponds to the `versionName` in
[`tools/network-switcher/build.gradle.kts`](tools/network-switcher/build.gradle.kts). See
[`CONTRIBUTING.md`](CONTRIBUTING.md) for how versions are chosen.

## [0.11.1]

### Fixed
- The 0.11.0 release did not compile, which is why its CI run failed: the live graphs read theme
  colours inside their `Canvas` draw lambdas (not a composable scope), the reset-scope enum was
  nested inside the tracker's companion object where the UI could not name it, and a companion
  function was called through the same-named property. All three are corrected at the cause.
- The "why did it change?" card no longer offers an RSRP figure. `SignalStrength.cellSignalStrength`
  does not resolve against this project's compile SDK, so the card reports Android's 0–4 signal level
  on its own rather than a dBm value it could not actually read.
- Artifact uploads can no longer fail an otherwise good build, and retained APKs drop from 7 days to
  1: repeated runs had filled the account's artifact storage quota, which failed the upload step on
  every module.

## [0.11.0]

### Added
- Live graph on the Watch page: switchable Speed / Data / Latency / Signal over a 1, 5, 15 or 30
  minute window, with drag-to-inspect. Speed and traffic come from the watcher; latency and signal are
  sampled only while the screen is open, and the card says so instead of drawing a gap as if the radio
  had been quiet. Where no sample was taken the line breaks and goes dashed rather than being joined up.
- Network Health score on the Watch page. It is weighted from 5G uptime, LTE fallbacks, radio
  stability, recovery success and recovery speed, and flagged data, and it shows every factor with the
  evidence behind it. A factor with no evidence is dropped and the rest are re-weighted, so a fresh
  install is not scored as broken; below that threshold it reports Collecting rather than a number.
- Recovery analytics on the Data page: success rate, average, fastest and slowest confirmed recovery,
  current and longest failure streak, fallback count and time, longest single fallback, 5G uptime,
  longest 5G session, radio changes per hour, and a seven-day breakdown. Fastest, slowest and the
  longest failure streak are now recorded by the watcher; previously only a mean existed, which hid
  both the recovery that took a minute and the streak that never ended.
- "Why did it change?" on the Watch page. The last radio transition is explained in words with its
  evidence listed. A data-SIM move or a recovery the app triggered itself is reported as **recorded**;
  everything the modem never reports is reported as **likely** and says so, because Android exposes no
  reason for a re-selection.

### Changed
- The Activity page was rebuilt around an interactive incident timeline: 24 hourly bars stacked by
  kind, tap or drag an hour to filter the list to exactly that hour, severity colouring, and an
  incident detail that opens in place as a recorded BEFORE → EVENT → ACTION → AFTER sequence. Filters
  grew from four to six (All / Success / Failed / Network / SIM / Recovery), and tapping an event
  expands it instead of doing nothing.
- The obsolete "feature recommendations" card, which was recommending features that now exist, was
  replaced with advice that names a switch that actually exists and is based on the whole log rather
  than the current filter.
- The usage timeline was rebuilt: 1 hour / 6 hours / 24 hours / 7 days ranges, bars stacked as 4G
  under 5G, 4G-vs-5G comparison for the whole range, and a busiest-bucket figure. Tapping a bar opens
  the exact one-minute intervals it was built from, with the fake/excluded rows still listed so a
  correction can be undone. The old chart only compressed minutes into a line and left the exact
  records in a separate list with no link between them.

### Notes
- Added the `INTERNET` permission. It is used for one thing: the live latency graph measures a single
  TCP handshake to a public resolver while the Watch screen is open. Nothing is uploaded, and the
  probe stops as soon as the screen is closed.

## [0.10.0]

### Fixed
- The no-service SIM failover could never revert. It recorded nothing about which SIM the data came
  from or that a failover was even in progress, so there was no code path back and the user stayed on
  the backup SIM until they changed it by hand. The move is now recorded and handed back as soon as
  the monitored SIM has service again.
- The LTE failover only reverted when the monitored SIM reported 5G, which it cannot do while the
  backup SIM holds the data connection — so again it never came back. Data is now handed back when
  5G returns *or* after a few minutes regardless, and a timed hand-back suppresses the next outbound
  switch so the two SIMs cannot trade the data connection every cooldown.
- Backup-SIM failover now works on a phone with two SIMs and untouched settings. Both SIMs previously
  had to be chosen explicitly, so the feature silently did nothing. The monitored SIM defaults to
  whichever carries data and the backup to the other active SIM.
- The notification no longer reads "monitoring 4G / LTE" throughout a failover. It states where the
  data connection actually is, and carries a "Move data back" action for the case the automatic path
  cannot cover.

### Added
- Wi-Fi failover: if the monitored radio stays on 4G or loses service, the Wi-Fi radio is raised so
  the phone can auto-join a saved network, and turned back off once 5G returns. It goes through
  Shizuku because apps have been barred from toggling Wi-Fi since Android 10, and the setting says
  plainly that Android gives no way to pick the network.
- Auto-correct on the counter-check card. It marks flagged spikes fake first, and only then trims the
  totals to match the device counter, reporting exactly which of the two it did. It never silently
  rewrites history, and it stays hidden while the books already balance.

## [0.9.0]

### Fixed
- "Mark interval fake" now works on every row. Interval ids were the minute bucket, so several
  records could share one id and toggling flipped a different record than the one tapped — which is
  why the button appeared dead for some rows. Ids are now unique, and the history list sorts ties by
  id so ordering is stable between reads too.
- Marking or resetting a single radio no longer disturbs the other one.

### Added
- Interactive usage graph: drag across it to scrub, with a crosshair, a highlighted point and a
  readout of the inspected bucket, plus start/end time labels.
- "Mark every spike as fake" clears all flagged intervals in one pass and reports how many changed.
- Per-field reset. The Data page now clears only what you choose — everything, the 4G figure, the 5G
  figure, or the speed peak — instead of one blanket button that threw away the good numbers too.
- Counter check card: the device's own aggregate mobile counter against the app's attribution for the
  session, split into attributed, deliberately set aside, and not seen by the watcher. This is the
  check that makes the 4G/5G split falsifiable — a negative residual would prove it wrong.
- Session reconciliation feeds the totals, so rejected bursts and unattributable bytes are accounted
  for rather than silently vanishing.

### Changed
- New module-local backdrop: orbiting light, three aurora blooms on independent periods, a grid and
  particle field that drift upward forever, a travelling sheen, a slow scanline and a vignette, all
  driven by continuous infinite transitions at different speeds so the motion never reads as one loop.
- All page headers rebuilt. The icon badge is now fully opaque with an accent border — it used to
  wash its accent over the animated backdrop, which is what made the glyph look glitchy — and the
  whole band reacts to the page's scroll position: the badge contracts, the title scales from its left
  edge, the subtitle dissolves and a gradient filament under the header fills as you scroll, with a
  breathing title colour on its own loop.
- The Data page's Reset/History row is split up, and History is a full-width action.

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

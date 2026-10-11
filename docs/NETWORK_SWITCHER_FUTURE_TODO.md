# Network Switcher — Future Redesign & Feature TODO

This document records the planned user-facing redesign and feature roadmap. These are planned additions only; this commit does not implement them.

## 0. Design direction
- [ ] Reposition Network Switcher as a personal network observability + control dashboard.
- [ ] Keep normal users away from modem/telephony complexity while retaining a deep technical mode.
- [ ] Premium instrument-panel visual language: large network state, live graphs, technical micro-values, thin timeline/grid details, restrained glow/motion, tactile cards.
- [ ] Preserve the safety model: reversible actions, clear explanations, and no claims beyond what Android/modem APIs can actually establish.

## 1. Navigation redesign
- [ ] **Watch** — live network state and telemetry.
- [ ] **Activity** — events, incidents, recovery timeline.
- [ ] **Data** — usage and interval history.
- [ ] **Tune** — automation, protection, diagnostics.
- [ ] Move History under Data instead of keeping it as a separate bottom-nav destination.

## 2. Watch / Network Command Center
- [x] Large 4G/5G/unknown state.
- [x] Live speed (aggregate). Upload and download are not shown separately: the app's plausibility
      rules and its reconciliation work on the summed counter, and splitting them would double the
      accounting surface for no stated use.
- [x] Live latency (TCP handshake round trip, not ICMP — Android allows an unprivileged app no ping).
- [x] Signal strength (Android's 0–4 level, plus RSRP as evidence on the why-did-it-change card).
- [x] Current SIM/data subscription (settings, and the failover status in the notification).
- [ ] Session duration — the Data page shows accumulated 5G time and fallback time, but no live
      "this session has lasted N minutes" counter.
- [x] Last transition and recovery result.
- [x] Compact health summary.

### Live graph
- [x] Switchable Speed / Data / Latency / Signal metrics.
- [x] 1m / 5m / 15m / 30m ranges. The 10-second range was dropped: the watcher samples once a
      second, so a 10-second window is a handful of samples and reads as noise rather than signal.
- [ ] Smooth, restrained animation — the graph redraws on each sample with an animated accent, but
      there is no per-point interpolation between frames.
- [x] Clearly distinguish live samples from historical data: a contiguous run is drawn solid, and a
      break in the ring buffer is drawn dashed and dim rather than joined up.

## 3. Network Health
- [x] Explainable health score based on 5G uptime, LTE fallbacks, recovery success and speed, radio
      stability and flagged data anomalies. Latency is not in the score: it is only measured while the
      Watch screen is open, so it would score a phone on how often its owner looked at it.
- [x] Categories: Excellent / Good / Fair / Poor.
- [x] Show the factors behind the score, with their points, weights and evidence, and drop factors
      that have no evidence instead of scoring them zero.

## 4. 5G Stability
- [x] 5G uptime.
- [x] LTE fallback count.
- [x] Successful/failed recoveries.
- [x] Longest 5G session.
- [x] Longest LTE fallback.
- [x] Recovery success rate.
- [x] Average/fastest/slowest recovery time.
- [x] Daily/weekly trend (seven days, from the retained log).

## 5. Network Session Timeline
- [ ] Visual timeline for 5G, LTE, SIM changes, recovery attempts, calls, thermal state changes, and manual actions.
- [ ] Tap an event to inspect surrounding telemetry.
- [ ] Show before → event → action → result.
- [ ] Group repeated transitions.

## 6. "Why did my network change?"
- [x] Human-readable likely reason for the most recent significant transition.
- [x] Possible causes covered: no service, coverage/reselection, a data-SIM move, a Wi-Fi failover and
      the app's own recovery action. Thermal state is not covered: the watcher records no thermal
      reading, so it cannot be evidence, and guessing it would be exactly the kind of claim this
      section is meant to avoid.
- [x] Inferred causes are labelled **likely**; causes the app's own log proves are labelled **recorded**.
- [x] Supporting data shown: previous and new state, exact time, signal level, RSRP, recovery attempts
      in the preceding two minutes, and the fallback count over the preceding hour.

## 7. Activity / Incident Center
- [x] Evolve Events into a graph-first activity/incident screen.
- [x] Keep duplicate grouping.
- [x] Expand repeated groups (tap to open the incident detail in place).
- [x] 24-hour activity timeline, hourly, stacked by kind.
- [x] Filters: All / Success / Failed / Network / SIM / Recovery, plus a selectable hour.
- [x] Severity and incident counts, including hours with failures.
- [x] Preserve export/clear history.

## 8. Incident Detail
- [x] Exact timestamp.
- [x] Network before/after.
- [ ] Signal, speed, latency, thermal state **at the time of the event**. The watcher never stored a
      per-event snapshot of any of these, so the detail view shows the recorded sequence instead of
      values it would have to invent. Adding the snapshots means writing them at event time first.
- [x] SIM/data subscription (via the data-SIM events around the change).
- [x] Action taken.
- [x] Result.
- [x] Recovery duration (group span).
- [x] Present as a visual sequence rather than a raw log.

## 9. Data redesign
- [x] Time-machine style usage dashboard.
- [x] Daily 4G/5G totals.
- [x] Usage timeline.
- [x] 4G vs 5G comparison for the selected range.
- [x] 1h / 6h / 24h / 7d ranges. Nothing longer: interval history is capped at 14 days on disk, so a
      longer window would silently drop its own oldest data.
- [x] Tap any graph point (bar) for the exact intervals behind it.
- [x] Show start/end, network, bytes, peak speed, suspicious/fake state.
- [x] Preserve manual fake/exclusion controls.

## 10. Smarter fake-spike detection
- [ ] Keep the hard ceiling for impossible readings.
- [ ] Add contextual anomaly detection.
- [ ] Detect impossible deltas, jump/drop patterns, counter resets, RX/TX inconsistency, impossible sustained throughput, duplicate intervals, and transition-related counter discontinuities.
- [ ] Confidence levels: Review / Likely counter glitch / Very likely invalid.
- [ ] Never silently delete questionable data.
- [ ] Let the user review and mark intervals fake.

## 11. Network Moments
- [ ] Fastest connection.
- [ ] Best signal.
- [ ] Longest 5G session.
- [ ] Fastest recovery.
- [ ] Longest LTE fallback.
- [ ] Most unstable period.

## 12. Protection Center
Group related toggles:
- [ ] 5G Recovery.
- [ ] LTE Failover.
- [ ] Call Protection.
- [ ] Thermal-related protection/experiments.
- [ ] Backup SIM Protection.
- [ ] Data Spike Protection.
- [ ] Explain what each option does and does not guarantee.

## 13. Smart Mode
- [ ] Balanced.
- [ ] 5G Priority.
- [ ] Stability First.
- [ ] Battery Saver.
- [ ] Custom.
- [ ] Make each profile explain its tradeoffs.

## 14. Automation Rules
- [ ] Visual WHEN → IF → THEN rules.
- [ ] LTE for N seconds → 5G recovery.
- [ ] No service for N seconds → backup SIM.
- [ ] Call active → pause switching.
- [ ] Backup SIM active → pause 5G recovery.
- [ ] Show cooldowns and retry limits beside rules.

## 15. Diagnostics
- [ ] Current RAT.
- [ ] 5G detection state.
- [ ] LTE state.
- [ ] Carrier.
- [ ] Active SIM.
- [ ] RSRP / RSRQ / SINR where available.
- [ ] Latency.
- [ ] Current speed.
- [ ] Thermal state.
- [ ] Expandable raw information for power users.

## 16. Explain-this UX
Add explanations for:
- [ ] RSRP.
- [ ] RSRQ.
- [ ] SINR.
- [ ] NR/5G.
- [ ] LTE.
- [ ] Thermal status.
- [ ] Recovery.
- [ ] Suspicious spike.
- [ ] SIM failover.
- [ ] Cooldown.
Each explanation answers: what it is, whether the current value is good/bad, and what Network Switcher can actually do.

## 17. Network Test
- [ ] Manual latency/download/upload/signal/RAT/stability test.
- [ ] Save results locally.
- [ ] Compare previous results.
- [ ] Clearly distinguish speed-test results from passive telemetry.

## 18. SIM Comparison
- [ ] Compare SIM 1 vs SIM 2 for network, signal, speed, latency, 5G availability, uptime, and recovery performance.
- [ ] Clearly show which SIM currently carries data.

## 19. Safe Mode
- [ ] Temporarily disable automatic switching, 5G recovery, SIM failover, and experimental thermal actions.
- [ ] Continue passive monitoring, graphs, history, and diagnostics.
- [ ] Make Safe Mode prominent.

## 20. Recovery Analytics
- [x] Attempts.
- [x] Successes/failures.
- [x] Success rate.
- [x] Average/fastest/slowest recovery.
- [x] Failure streaks (current and longest).
- [ ] Cooldown/retry statistics — the live cooldown countdown is shown, but no history of how often
      the breaker opened or how many retries each cooldown needed.
- [x] Trend over time (seven days).

## 21. Weekly Network Report
- [ ] 5G uptime.
- [ ] LTE fallback count.
- [ ] Recovery success rate.
- [ ] Total mobile data.
- [ ] Fastest speed.
- [ ] Best signal.
- [ ] Longest 5G session.
- [ ] Network health.
- [ ] Compare against the previous week.

## 22. Network Story
- [ ] Generate a factual plain-language daily summary.
- [ ] Include stability, 5G time, LTE fallbacks, recovery results, fastest connection, and suspicious readings needing review.
- [ ] Base every statement on recorded telemetry.

## 23. Data integrity / engineering requirements
- [ ] Never claim an unprovable modem cause.
- [ ] Separate measured facts from inferred explanations.
- [ ] Prevent duplicate interval records across service restarts.
- [ ] Keep history bounded.
- [ ] Keep file operations off the UI thread.
- [ ] Test counter resets and network transitions.
- [ ] Test dual-SIM changes.
- [ ] Test call pause/resume.
- [ ] Test backup-SIM pause/recovery.
- [ ] Test suspicious/fake interval accounting.
- [ ] Verify release builds before version bumps.

## 24. Suggested implementation order

### Phase 1 — Stability
- [ ] Fix build regressions.
- [ ] Usage-accounting tests.
- [ ] Event-grouping tests.
- [ ] Release CI verification.
- [ ] Service-restart verification.

### Phase 2 — Information architecture
- [ ] Watch / Activity / Data / Tune navigation.
- [ ] Move History into Data.
- [ ] Consolidate Protection settings.

### Phase 3 — Live dashboard
- [ ] Network Command Center.
- [ ] Live graphs.
- [ ] Network Health.
- [ ] 5G Stability.

### Phase 4 — Observability
- [ ] Session timeline.
- [ ] Incident Center.
- [ ] Incident Detail.
- [ ] Why-did-this-change explanations.

### Phase 5 — Data intelligence
- [ ] Time-machine usage UI.
- [ ] Better anomaly detection.
- [ ] Network Moments.
- [ ] Recovery analytics.

### Phase 6 — Automation
- [ ] Smart Modes.
- [ ] Visual automation rules.
- [ ] Safe Mode.

### Phase 7 — Power-user tools
- [ ] Diagnostics.
- [ ] Explain-this.
- [ ] Network Test.
- [ ] SIM Comparison.

### Phase 8 — Long-term retention
- [ ] Weekly reports.
- [ ] Network Story.
- [ ] Long-term trends.

## Definition of done
- [ ] A normal user understands the current network in under 5 seconds.
- [ ] A user can understand why a recent 5G → LTE transition happened without reading raw logs.
- [ ] A power user can inspect technical radio information.
- [ ] Suspicious usage can be found by exact interval and manually excluded.
- [ ] Automatic switching is explainable and configurable.
- [ ] Experimental/dangerous actions are reversible and clearly labeled.
- [ ] The UI feels like one coherent product rather than accumulated diagnostic screens.

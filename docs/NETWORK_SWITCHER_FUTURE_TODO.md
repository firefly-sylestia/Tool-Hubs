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
- [ ] Large 4G/5G/unknown state.
- [ ] Live download/upload speed.
- [ ] Live latency.
- [ ] Signal strength.
- [ ] Current SIM/data subscription.
- [ ] Session duration.
- [ ] Last transition and recovery result.
- [ ] Compact health summary.

### Live graph
- [ ] Switchable Speed / Ping / Signal / Data metrics.
- [ ] 10s / 1m / 5m / 30m ranges.
- [ ] Smooth, restrained animation.
- [ ] Clearly distinguish live samples from historical data.

## 3. Network Health
- [ ] Explainable health score based on 5G uptime, LTE fallbacks, recovery success, signal stability, latency, failures, and data anomalies.
- [ ] Categories: Excellent / Good / Fair / Poor.
- [ ] Show the factors behind the score.

## 4. 5G Stability
- [ ] 5G uptime.
- [ ] LTE fallback count.
- [ ] Successful/failed recoveries.
- [ ] Longest 5G session.
- [ ] Longest LTE fallback.
- [ ] Recovery success rate.
- [ ] Average/fastest/slowest recovery time.
- [ ] Daily/weekly trend.

## 5. Network Session Timeline
- [ ] Visual timeline for 5G, LTE, SIM changes, recovery attempts, calls, thermal state changes, and manual actions.
- [ ] Tap an event to inspect surrounding telemetry.
- [ ] Show before → event → action → result.
- [ ] Group repeated transitions.

## 6. "Why did my network change?"
- [ ] Human-readable likely reason for significant transitions.
- [ ] Possible causes: weak signal, reselection, no service, thermal condition, SIM/data change, manual action, recovery action, unknown.
- [ ] Explicitly label inferred causes as **likely**.
- [ ] Show supporting signal, speed, thermal, duration, and recovery data.

## 7. Activity / Incident Center
- [ ] Evolve Events into a graph-first activity/incident screen.
- [ ] Keep duplicate grouping.
- [ ] Expand repeated groups.
- [ ] 24-hour activity timeline.
- [ ] Filters: All / Success / Failed / Network / SIM / Recovery.
- [ ] Severity and incident counts.
- [ ] Preserve export/clear history.

## 8. Incident Detail
- [ ] Exact timestamp.
- [ ] Network before/after.
- [ ] Signal, speed, latency, thermal state.
- [ ] SIM/data subscription.
- [ ] Action taken.
- [ ] Result.
- [ ] Recovery duration.
- [ ] Present as a visual sequence rather than a raw log.

## 9. Data redesign
- [ ] Time-machine style usage dashboard.
- [ ] Daily 4G/5G totals.
- [ ] Usage timeline.
- [ ] 4G vs 5G comparison.
- [ ] 6h / 24h / 7d / longer ranges.
- [ ] Tap any graph point for the exact interval.
- [ ] Show start/end, network, bytes, peak speed, suspicious/fake state.
- [ ] Preserve manual fake/exclusion controls.

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
- [ ] Attempts.
- [ ] Successes/failures.
- [ ] Success rate.
- [ ] Average/fastest/slowest recovery.
- [ ] Failure streaks.
- [ ] Cooldown/retry statistics.
- [ ] Trend over time.

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

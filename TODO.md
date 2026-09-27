# Tool Hubs delivery checklist

## Delivered in this pass
- [x] Shared high-contrast semantic theme foundation, system dark-mode default, and shared screen-header/metric primitives used by every Tool Hubs app.
- [x] 5G watcher retry state survives service recreation, uses progressive backoff, opens a circuit breaker, and always releases the reconnect lock.
- [x] Shizuku health is surfaced as unavailable, permission-required, binding, ready, or service-died, with notification recovery actions.
- [x] Boot restart is opt-in, delays radio startup, and records the deferred-start event.
- [x] Selected-SIM monitoring, SIM eligibility details, and optional no-service backup-SIM data failover with a cooldown.
- [x] LTE/5G traffic is daily and weekly bucketed and throttled; the home tracker includes an adjustable billing-cycle start plus observed fallback/recovery impact.
- [x] Typed, filterable, expandable history and JSON report export through Android's share sheet.

## Known platform limitations
These items are intentionally documented rather than left as unfinished implementation work:
- [x] Exact "data saved" cannot be derived from Android traffic counters: it needs a carrier plan/rate model. The product reports observed traffic by network state instead.
- [x] `cmd phone set-default-data-subscription` is vendor/Android-build dependent and can be rejected by a device even via Shizuku; the UI records the returned result.
- [x] Some OEMs restrict boot foreground-service starts and battery optimisation exemptions. The app can guide users but cannot guarantee exemption.
- [x] Carrier NR capability and allowed-network-type APIs vary by Android release and vendor radio implementation; diagnostics label unavailable values rather than infer them.

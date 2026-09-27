# Tool Hubs delivery checklist

## Delivered in this pass
- [x] Shared high-contrast semantic theme foundation.
- [ ] Reusable dashboard component library and full per-app screen redesign.
- [x] 5G watcher retry state survives service recreation, uses progressive backoff, and opens a circuit breaker after repeated failures.
- [x] Shizuku health is surfaced as unavailable, permission-required, binding, ready, or service-died.
- [x] Automatic reconnect is guarded with `finally`; failures can no longer leave the watcher permanently locked.
- [x] Boot restart is opt-in and delayed to give the radio and Shizuku time to initialise.
- [x] LTE/5G traffic is bucketed by day and written at a throttled cadence.
- [x] Structured history event types and filtering chips.
- [ ] Expandable event diagnostics and CSV/JSON export report.

## Platform-limited follow-ups
- [ ] Exact "data saved" cannot be derived from Android traffic counters: it needs a carrier plan/rate model. The product reports observed traffic by network state instead.
- [ ] Some OEMs restrict boot foreground-service starts and battery optimisation exemptions. The app can guide users but cannot guarantee exemption.
- [ ] Carrier NR capability and allowed-network-type APIs vary by Android release and vendor radio implementation; diagnostics must label unavailable values rather than infer them.

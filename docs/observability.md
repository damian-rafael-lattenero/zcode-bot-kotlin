# Observability — E2: the daemon's metrics (Micrometer/Prometheus)

Written 2026-11-03 (E2). The daemon exposes a Prometheus scrape endpoint
when the `metrics` knob is on; every metric below derives from a real
event that already exists in the code — the honest-indicators law. No
decorative counters, no cached gauges: a gauge reads its truth at scrape
time.

## The knobs (read ONCE at boot — a change is a kickstart)

Both live in `~/.zcode/bot-kotlin/daemon.json`, the same file as the
other knobs:

- `"metrics": true|false` — **shipped OFF**. OFF is the strict zero: no
  registry, no listener, no clock read on any hot path — the daemon is
  byte-for-byte the pre-E2 one. A typo is OFF loudly (one warn line in
  `daemon.jsonl`).
- `"metrics_port": 9102` — the listener's port, default **9102**. Read
  even with the switch off (a typo must be loud whatever the switch
  says); garbage or out-of-range falls back to the default loudly.

The endpoint binds **127.0.0.1 ONLY** — the exposition never leaves the
machine; Prometheus scrapes it locally or not at all. One route:
`GET http://127.0.0.1:9102/metrics` → the Prometheus text format
(v0.0.4). Non-GET is 405, other paths are 404.

## The inventory

Rendered names (Micrometer's dots become underscores; counters gain
`_total`, timers become `_seconds_*`). Lanes: `lane="main"` is the
commands throat, `lane="firehose"` the render throat.

| Metric | Type | The real event that feeds it |
|---|---|---|
| `bot_turns_started_total` | counter | a `task_run_started` stream frame first-witnessed by the daemon (resubscribe bursts of a live turn, and replays of an already-sealed turn, publish nothing) |
| `bot_turns_sealed_total{outcome="completed"\|"failed"}` | counter | `task_complete` / `task_error` frames |
| `bot_turn_duration_seconds_count/_sum/_max{outcome}` | timer | the witnessed start→seal duration; a turn first seen at its own end (subscription attached mid-flight) counts on the counter but NOT on the timer — nothing invented |
| `bot_bus_written` | gauge | `BusSink.written` — bus appends that landed (`event_bus` ON) |
| `bot_bus_failures` | gauge | `BusSink.failures` — appends rolled back after a failed write |
| `bot_memory_injected_total` | counter | a memory block actually PREPENDED to a prompt (`memory_inject` ON) |
| `bot_memory_inject_failures_total` | counter | the inject's fetch timed out, the brain was down, or answered non-2xx |
| `bot_memory_fetch_seconds_count/_sum/_max` | timer | the bounded block fetch (the 1.5 s deadline included — a cut is a real duration) |
| `bot_brain_request_seconds_count/_sum/_max` | timer | every brain HTTP leg (RAG, memory, bus status, …) through the gateway |
| `bot_brain_failures_total` | counter | a brain request with no usable answer: timeout, dead brain, non-2xx |
| `bot_poller_cycles_total` | counter | one completed pass of the getUpdates reader (`poll` ON) — whatever it fetched, 0 messages when the breaker ate the cycle |
| `bot_poller_inbound_total` | counter | an owner update accepted through the poller: a text message or an attachment |
| `bot_poller_callbacks_total` | counter | an owner button tap accepted through the poller |
| `bot_outbox_depth{lane}` | gauge | the lane's outbox file array size, read at scrape (lock-free: every write lands as an atomic rename) |
| `bot_cooldown_active{lane}` | gauge (1/0) | the lane's 429 cooldown window active at scrape (the shared `tg-cooldown` mtime) |
| `bot_zconn_transitions_total{direction="down"\|"up"}` | counter | the episode machine's SPOKEN transitions — the same ⚠️/✅ notices the chat receives |
| `bot_zconn_down` | gauge (1/0) | the persisted episode machine's `down`, read at scrape (`state/zconn.json`) |

Families appear only when their surface exists: the bus gauges need
`event_bus` ON, the memory taps need `memory_inject` ON, the poller
taps need `poll` ON, and the outbox/cooldown gauges exist only in LIVE
mode. A missing family means "that surface never ran", not a scrape
error.

## Shape of the OFF law

The facade (`modules/daemon/.../metrics/Metrics.kt`) is the only metrics
surface the rest of the daemon sees. The wiring decides ONCE at boot:
`MetricsStack.fromKnob` returns the no-op facade when OFF (the two
request timers are block-shaped, so even the no-op costs no clock read;
the stateful turn tap is simply never built) and the Micrometer-backed
one when ON. Micrometer lives ONLY in `modules/daemon` — the channel
and transport modules stay standard-library-pure (the poller's tap is a
plain lambda).

## The Grafana half (E4, later)

The parity plan's dashboards (§3 of docs/parity-and-engineering.md) read
exactly these families: cooldown + breaker storm = `bot_cooldown_active`
and `bot_brain_failures`; outbox depth = `bot_outbox_depth`; channel
health = `bot_zconn_transitions_total` and `bot_zconn_down`; turn
latency = `bot_turn_duration_seconds_*`; ingest health =
`bot_bus_written` vs `bot_bus_failures`.

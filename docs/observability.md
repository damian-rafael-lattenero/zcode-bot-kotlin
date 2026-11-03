# Observability — the daemon's E2 metrics and E3 traces

Written 2026-11-03 (E2; E3 same day). The daemon exposes a Prometheus
scrape endpoint when the `metrics` knob is on; every metric below derives
from a real event that already exists in the code — the honest-indicators
law. No decorative counters, no cached gauges: a gauge reads its truth at
scrape time. E3 adds the TRACES half: OpenTelemetry spans behind the
`otel` knob, the same honest law. E2 measures, E3 traces — the two never
double-instrument a tap.

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

## E3 — the traces (OpenTelemetry, shipped OFF)

The ladder's E3 rung: one trace per turn, exported as OTLP/HTTP. The
spans derive from the SAME real stream frames the bus producer taps (the
daemon's own wiring, one tap point — `TurnSpanTracker` folds the frames
into the sealed `SpanTapEvent` union, `OtelSpanTaps` turns events into
spans). Nothing is re-queried, nothing is invented, and the channel
module never learns a span exists.

### The knobs (read ONCE at boot, same `daemon.json`)

- `"otel": true|false` — **shipped OFF**. OFF is the strict zero: no SDK,
  no exporter, no batch thread, no tracker — the daemon is byte-for-byte
  the pre-E3 one (the OFF test proves even the exporter factory is never
  constructed). A typo is OFF loudly (one warn line in `daemon.jsonl`).
- `"otel_endpoint": "http://127.0.0.1:4318/v1/traces"` — the OTLP HTTP
  endpoint spans are POSTed to. Read even with the switch off (a typo
  must be loud whatever the switch says). Absent → the OTLP standard
  (port 4318, `/v1/traces`); a non-http(s) URL falls back to the default
  loudly. The daemon **exports and never binds** — unlike the `/metrics`
  listener, there is no socket of ours anywhere.

### What a trace looks like

One trace per real turn — root span `bot.turn` with a child `bot.tool`
per tool call:

    bot.turn  (sid=conv-1, bot.turn.fork_turn=t7, bot.turn.title=…,
               bot.turn.outcome=completed, duration = the turn's own)
      bot.tool  (bot.tool.name=Bash, bot.tool.ok=true, bot.tool.chars=6)
      bot.tool  (bot.tool.name=Write, bot.tool.ok=true, bot.tool.chars=120)

Attribute inventory — each present ONLY when a real frame carried the
value: root has `bot.sid` (always; the frame's own task id),
`bot.turn.fork_turn`, `bot.turn.title` (the stream's
`session_info_update`), `bot.turn.midflight=true` (see below),
`bot.turn.outcome` (`completed`/`failed`), `bot.turn.error` (the failed
seal's own text), status ERROR on a failed seal; a child has `bot.sid`,
`bot.tool.name`, `bot.tool.ok` (the close's own verdict —
`completed` vs everything else, the bus's law), `bot.tool.chars` (the
close's content length in code points), `bot.tool.unclosed=true` (the
turn sealed before the tool's verdict arrived — the span ends at the
seal, the verdict honestly unknown), status ERROR only on a real failed
verdict. **No `model` attribute**: the stream frames never name the
model, and a per-turn cli-db read is I/O E3 refuses — the honest absence.

The timestamps are the events' own stamps: the span's duration IS the
real thing's duration (the birth's `startedAt` when the frame carried
it; the seal and the tool closes at their reception stamps).

**The mid-flight choice (documented, tested):** a turn the daemon never
saw born (a subscription attached mid-turn, e.g. a `/task` pick) still
gets its root span — starting at the WITNESSING moment, with
`bot.turn.midflight=true` and no backdating from the terminal frame's
`startedAt` (E2's nothing-invented law; that stamp's turn-vs-task
semantics are unverified). The reader gets the observation window, not a
lie about the turn's length. A tool the daemon never saw OPEN gets no
span (its start would be invented); a replayed frame (resubscribe burst,
already-sealed turn) spans nothing — the bus producer's dedupe laws,
ported verbatim.

### Export: batch OTLP, telemetry never breaks a turn

The SDK runs a `BatchSpanProcessor` → `OtlpHttpSpanExporter` → the
configured endpoint. Spans queue in memory; the batch's OWN thread
POSTs them — a slow or dead collector is one log line (the batch
worker's), never a broken turn (the live-io invariant, guarded twice:
the thread's isolation plus `GuardedSpanTaps`, which turns any edge
failure into one warn line). The daemon's shutdown hook closes the
provider: the batch's last export lands before the JVM dies.

### Pointing it at a collector / Jaeger locally

Jaeger all-in-one speaks OTLP/HTTP on 4318 natively:

    docker run --rm -p 16686:16686 -p 4318:4318 jaegertracing/all-in-one:latest

then in `~/.zcode/bot-kotlin/daemon.json`:

    { "otel": true, "otel_endpoint": "http://127.0.0.1:4318/v1/traces" }

…kickstart the daemon (a knob change is a restart) and open
http://127.0.0.1:16686 — service `zcode-bot-daemon`. Any OTel-collector
compatible backend works the same way (point `otel_endpoint` at the
collector's `/v1/traces`); the daemon only ever POSTs, so the collector
being down costs nothing but one log line per failed batch.

## The Grafana half (E4, later)

The parity plan's dashboards (§3 of docs/parity-and-engineering.md) read
exactly these families: cooldown + breaker storm = `bot_cooldown_active`
and `bot_brain_failures`; outbox depth = `bot_outbox_depth`; channel
health = `bot_zconn_transitions_total` and `bot_zconn_down`; turn
latency = `bot_turn_duration_seconds_*`; ingest health =
`bot_bus_written` vs `bot_bus_failures`.

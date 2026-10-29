# Feature parity & engineering pillars

Written 2026-10-29, from the full reconnaissance of the Python bot
(45 commands, ~80 features, 36 knobs + 11 legacy aliases, 6 modes) and of
the fork's bots channel (stream kinds, service surface, reply modes).
Companion of the bot repo's `docs/kap-first-migration.md` (phases) and
ADR-022 (live mode). Decisions approved by Damian the same day.

## 1. What the channel gives us directly (live mode)

Stream kinds and services the daemon consumes raw — each replaces a
hand-rolled mechanism of the Python era:

| Capability | Source (fork) | Replaces in the Python bot |
|---|---|---|
| Real-time reasoning | `agent_thought_chunk` (same stream the UI renders) | the live-io sidecar tail (F73) and the cot-live watcher's `session/messages` polling — the CoT becomes a DIRECT subscription |
| Tool args BEFORE completion | `tool_input_delta` → `tool_call_update` with parsed preview (docs/streaming-tool-input-preview.md) | "⚡ preparing:Edit · N lines" via sidecar toolChars |
| Tool outputs | `tool_call_update.content` | Bash 🔧 outputs from the store / stream stitching (F86) |
| Turn end | `task_complete` (stopReason + usage) | the stream-vs-log-scan race (ADR-015) |
| Permissions | `permission_request` + inline keyboards + ACK-gated state | the 🔐 hook bridge + replay (partially; hooks stay as fallback) |
| Questions / plan approval | `elicitation_request` (multi-question, ExitPlanMode schema) | the ❓ bot-q file bridge |
| Subagents | same stream, `parentToolUseId` on chunks/tool calls | rollout file assignment + spawn-file labels (agent_live's [F] half) |
| Tokens & cost per request | `usage_update`, `task_complete.usage`, `getTaskTokenUsage` | /usage and /ctx scanning rollout jsonl with growing windows |
| Network episodes | `task_network_debug_status` (attempt/maxAttempts/headers) | zconn tailing the CLI log for `model.network.failed` (F85) |
| Account quota | `IUsageStatsService` (5h pool, weekly, nextResetTime) | /quota's leveldb cache + api.z.ai endpoint hack |
| Tasks & titles | `listTasks` / `getTaskMeta` / `session_info_update` | the sqlite reads (tasks-index, session_entry) |
| Model / mode / thought level | `setModel` / `setMode` / `setConfigOption` | setModel/setMode on the owned runtime + `_restore_model` |
| Compact | `compactSession` | /compact's own protocol call |
| Prompting | `sendPrompt` (with attachments, `stopGeneration`) | the whole owned worker (in live mode) |
| Inbound attachments | channel supports 4×5MB (photo/voice/video/file → prompt attachments) | NEW — the Python bot never had this |

What the channel does NOT give (and why our Telegram renderer survives):
no message editing in place (the official bot sends new bubbles only) and
no outbound documents/photos. The CoT boxes with in-place expansion, the
blinking line, the bundles, /cot as a document — that is OUR rendering on
top of the raw stream. It is the differentiator over the official bot.

## 2. Verdict matrix (per command/feature)

Legend: **KEEP** (ports as-is, mechanism unchanged) · **DIRECT** (replaced
by the channel/service — better than today) · **RENDER** (ours, now fed by
the stream instead of files) · **LIVE-DROPS** (obsolete in live mode,
headless-only) · **NEW** (gained).

### Commands

| Command | Today | Live mode | Verdict |
|---|---|---|---|
| /task /all_tasks | sqlite + index files | `listTasks`/`getTaskMeta` | DIRECT |
| /run /retry | spawn worker | `createTask`/`sendPrompt` | DIRECT |
| /stop | marker→session/stop→kill -9 ladder | V4 `stopGeneration` | DIRECT (kill-loop class dies) |
| /model /thinking /mode | own runtime setModel/Mode + restore | service setters | DIRECT |
| /compact /all_compact | own protocol call | `compactSession` | DIRECT |
| /status | state files + logs + heal line | service queries + stream + health | DIRECT (richer) |
| /quota | leveldb + api.z.ai hack | `IUsageStatsService` via the socket | DIRECT |
| /usage /ctx | rollout scanning | `usage_update` + `getTaskTokenUsage` | DIRECT (real-time) |
| /subagents /sa | file index + detached view | stream `parentToolUseId` + our view | DIRECT + RENDER |
| /fork | own runtime `session/fork` | no service method (yet) | KEEP headless / fork-extension later |
| /api | raw protocol on own runtime | raw service call via socket | KEEP (debug) |
| /cot | ephemeral resume + history | snapshot/history via service if enough, else headless | TRANSFORM (decide at B4) |
| /last | last-reply.txt | unchanged | KEEP |
| /diff (+log/commit/dirty) | local git + render | unchanged | KEEP |
| /queue /qgo /qdel /interrupt /qkeep | state files + worker control | daemon routing: stop+front-run composes the same UX | KEEP (daemon-side) |
| /verbosity /bot_mode | presets over 9 knobs | presets reinterpret over stream events | KEEP |
| /handshake | pipe health + re-attach | channel connection + heal + hb | KEEP (evolves) |
| /brain /memory | agent-brain HTTP | unchanged | KEEP |
| /deploy /nuclear /ping /help /start | as today | as today | KEEP |
| /set_webhook family | transport switch | unchanged (daemon transport) | KEEP |
| /cotb /cotr /cotx /agb /agr /agx | cache + in-place edits | cache + in-place edits, fed by stream | RENDER |

### Live features

| Feature | Verdict |
|---|---|
| CoT boxes (sealing, numbering, born-open, reader window, bundles) | RENDER — fed by `agent_thought_chunk`; the sidecar dies |
| The blinking line (states, tool clock, preparing preview, writing tail) | RENDER — fed by `tool_call_update`/`tool_input_delta` |
| 🔵 tool boxes / steps / trace documents / no-CoT notice | RENDER |
| LiveTurnView for phone turns | REPLACED by the stream (the app's own turn renders in its UI; we render ours) |
| Spectator (Mac turns) | LIVE-DROPS — the channel streams ANY turn, phone or Mac |
| cot-live watcher (Mac CoT) | LIVE-DROPS — same |
| agent_live per-agent boxes/lines/linger/respawn | RENDER + DIRECT (stream carries the events; our per-agent rendering and the 2h linger stay) |
| zconn episodes | DIRECT (`task_network_debug_status`), our first-reason/sputter logic ports as pure logic |
| 👀 reaction / typing / 📎-attachments | KEEP + NEW (real inbound attachments) |

### Operations (the armor)

| Feature | Verdict |
|---|---|
| Outbox + shared 429 cooldown (mtime) + breaker | KEEP (transport is ours; KAP CircuitBreaker) |
| Healer 3 layers + hb + bot-recorder | KEEP, re-scoped: "channel connection" replaces "watcher alive" |
| Auditor invariant checks | KEEP, re-scaped to live-mode invariants |
| Warm worker, session rotation, orphan supervisor, HEADLESS_GUARD | LIVE-DROPS (headless mode keeps them) |
| turn-end race, kill-loop /stop, perm replay for channel-served turns | LIVE-DROPS (by design) |
| Nuclear, deploy door, reload, housekeeping, code_changed | KEEP |
| Queue (max 5), interrupt-and-redirect | KEEP (daemon routing composes stop+send) |
| Bus publisher (ADR-019), memory_inject (ADR-020), Diff Ask events | KEEP (memory_inject prepends before forwarding to sendPrompt) |
| Hooks ▶️ ✅ ❌ 🟢 | KEEP as fallback + for app sessions not via channel; 🔐 ❓ become stream-primary |

### New, for free

Inbound attachments (voice notes!), inputOrigin tagging (desktop|mobile),
multi-device sync semantics, /reconnect-style channel health.

## 3. Observability (decided 2026-10-29)

**Logging**: kotlin-logging (oshai) over SLF4J → Logback +
logstash-logback-encoder. JSONL append-only logs with MDC
(sid/turn/task/component) — the same shape as the bus, so agent-brain's
FileTailer can ingest them later. AsyncAppender with neverBlock (the
observability-never-breaks-a-turn law, same as the bus tap). Size+time
rolling with total caps (the 294 MB lesson). `MDCContext`
(kotlinx-coroutines-slf4j) propagates context across coroutines — the
JVM's contextvars. Live tail with lnav (JSON-aware).

**Traces**: OpenTelemetry SDK — spans per turn with children per tool
call: the whole turn as a waterfall in Jaeger/Tempo. The "visible
pipeline" requirement becomes a first-class artifact.

**Metrics**: Micrometer in the daemon, /metrics on the localhost Ktor
server (B2), scraped by Prometheus; Grafana dashboards (cooldown, breaker,
outbox depth, channel health, turn latency, hb ages) in the agent-brain
docker-compose family.

**Alerting**: the bot reports to itself — daemon alerts to the owner chat
(meta-monitoring, the /handshake+healer philosophy), Grafana alerts
optional on top.

Landing order: logging skeleton with the daemon (B1), metrics with Ktor
(B2), OTel spans with live mode (B3), dashboards + chaos drills (B4),
runbook refresh (B5).

## 4. The test pyramid (tiers)

1. **T1 unit** — pure decision layer (episode machines, sealing, routing).
2. **T2 property/laws** — kotest-property laws for every state machine
   (ConnTrack episodes, box sealing, queue FIFO) — the KAP standard.
3. **T3 integration** — daemon against a fake channel service + state-file
   fixtures (current AND legacy Spanish shapes, from contract-types).
4. **T4 contract** — state formats, bus events, and the socket channel
   protocol pinned by tests both sides can run.
5. **T5 e2e daemon** — full pipeline against fake Telegram + fake channel.
6. **T6 black-box REAL** — Telethon reading the owner chat, like
   agent-brain's tier 5 (the /nuclear-class drills).
7. **T7 chaos/fault injection** — the F-codes as reproducible tests: 429
   storms, dead channel mid-turn, stuck watcher, socket refused.
8. **Golden replay** — the recorder's traces as fixtures proving
   byte-level parity at every cutover.

## 5. Engineering pillars — the complete map

| Pillar | Status in this plan |
|---|---|
| Unit tests | T1+T2 (property-based, not just examples) |
| Integration tests | T3 |
| E2E | T5 |
| Black-box e2e (real) | T6 Telethon |
| Telemetry + logging | §3 (decided) |
| Docs + runbook | port the runbook at B5; ADRs continue in the bot repo |
| Architecture map | this doc + kap-first-migration.md + a dependency-graph like the fork's |
| Chaos/fault injection | T7 — the missing pillar most projects skip |
| Golden/replay testing | the recorder (B0) — formalized as a tier |
| Security | socket channel auth (local token, 0600), owner fail-closed (ports), secret scanning before pushes, no tokens in repos |
| Versioning & releases | semver + changelog + release checklist; truth gates like kap's verify-claims |
| Incident culture | F-code glossary + postmortems continue; TECH_DEBT.md pattern |
| Backups/DR | deploy backups (existing pattern) + golden traces as the safety net |
| Performance budgets | JMH where hot (render core), load smoke for the daemon |
| Dependency hygiene | version catalog + versions plugin + Renovate/Dependabot |

The last four were the ones missing from the list ("¿me falta algo más?"):
chaos drills, golden replay, security of the new surfaces, and
release/truth-gate discipline. The rest you already practiced in Python —
this plan just gives each practice a tier or a tool.

## 6. Phase mapping (deltas to the roadmap)

- **B1** (+logging skeleton E1): unchanged watchers, plus the JSONL/MDC
  logging stack from day one.
- **B2** (+metrics E2): Ktor brings /metrics; cooldown/breaker gauges.
- **B3a**: the socket channel ALSO exposes service queries (listTasks,
  quota, task meta) — that is what makes /task /quota /usage DIRECT.
- **B3b** (+traces E3): live routing; spans per turn.
- **B4** (+dashboards E4, chaos T7): render core on KapStream; Grafana
  dashboards; the F-code chaos drills.
- **B5** (+runbook E5): Python retirement; runbook and postmortem
  culture fully ported.

# Fork↔Daemon Interface Map

> Reference for Python-parity work. Produced 2026-10-29 by a read-only survey agent, verified against code (evidence cited inline).
> Branches: ZCode fork `damian` @ `9b814dcb` (B3a deployed) · Kotlin daemon `zcode-bot-kotlin` · Python reference `~/Projects/bot`.
> Companion docs: `~/Projects/ZCode/docs/fork/DAEMON-CHANNEL.md` (channel spec), `docs/plan-brujula.md` (roadmap).

## 1. The fork's surfaces (everything a client can touch)

### 1.1 The daemon-local channel (B3a) — the primary surface

- **Spec:** `/Users/lattenero/Projects/ZCode/docs/fork/DAEMON-CHANNEL.md`
- **Normative shapes:** `/Users/lattenero/Projects/ZCode/packages/shared/src/daemon-channel.ts` ("if this text and that file disagree, the file wins")
- **Server:** `/Users/lattenero/Projects/ZCode/packages/services/src/bots/daemonChannel.ts` (`createDaemonLocalChannel()`)
- **Host wiring:** `packages/services/src/node.ts` (owner = utility-process Host, same `BotsService` instance as the embedded bots)
- **Tests:** `packages/services/test/daemonChannel.test.ts`

**Frames (protocol v1):** `hello/hello_ok` (auth; server `{pid, startedAt, appVersion}`, `bot {id, enabled}`, `capabilities[]`; idempotent re-hello = health check) · `inbound/inbound_result` (free text into the composer's pipeline; `replies[]` = system bubbles; `duplicate:true` on messageId dedupe) · `subscribe/subscribed` + `unsubscribe` (per `taskId`+`workspacePath`, both REQUIRED — `daemonChannel.ts:465-471`) · `stream_event` (raw V4 `ZCodeStreamEvent` + `taskId`, `seq` monotonic per connection, `at` host receive time) · `query/query_result` (11 kinds, table below) · `ping/pong` · `error {code, fatal?}` / `bye {reason}`.

**Queries → fork service → bot command mapping:**

| `query.kind` | Fork service | Feeds |
| --- | --- | --- |
| `listTasks {workspacePath}` | `IZCodeTaskService.listTasks` → `ZCodeTaskMeta[]` | open-conversation resolver, `/tasks` marks |
| `taskList {listKind: pinned\|archived\|timeline\|active, workspaceScopes, search, limit}` | `listTaskList` (global; `forkedFromTaskId` = parent) | alternative `/all_tasks` source |
| `activeTaskIds {workspacePath}` | `listActiveTaskIds` — sessions-index, not tasks-index | "what runs NOW", `/status`, `/stop` list |
| `taskMeta {taskId}` | `getTaskMeta` | titles, `updatedAt` |
| `taskSnapshot {taskId, messageLimit, byteBudget, toolLimit}` | `getTaskSnapshot` | post-reconnect resync, final answer, future `/cot` |
| `taskUsage {taskId}` | `getTaskTokenUsage` | `/usage` `/ctx` (post-MVP) |
| `quota` | `IUsageStatsService.getEntitlementSnapshot` (5h pool, `remaining.percentage`, `nextResetTime`) | `/quota` |
| `botStatus {}` | daemon bot config + `BotContextState` | `/status`, the router's context truth |
| `stop {taskId, runId?}` | `stopGeneration` — the SAME V4 stop as the UI button | `/stop`, push-or-enqueue |
| `stopAll {workspacePath?}` | `listActiveTaskIds` × `stopGeneration` | `/nuclear` (half) |
| `compact {taskId, ...}` | `compactSession` | `/compact` (future) |

**The inbound path:** `inbound` → dedupe by `messageId` (TTL 2 min) → `BotsService.handleInboundMessage` with FIXED actor `{provider:"daemon", botId:"daemon-local", chatType:"private"}` (`daemonChannel.ts:409-435`) → draft/task context → `sendPromptInBackground` (`botsService.ts:7145`) → V4 `sendText` → the CLI agent's **CommandInbox** FIFO. A task mid-turn rejects free text with the `taskRunning` reply (`botsService.ts:7680`).

**CRITICAL — inbound targeting (the router question). Evidence that inbound takes NO task target:**
1. `DaemonChannelInboundRequest` fields are exactly `type, id, text, commandText?, messageId?, attachments?, receivedAt` — no taskId, no actor, no conversation (`packages/shared/src/daemon-channel.ts:51-62`).
2. The actor is fixed server-side; inbound always lands in the daemon bot's context (`BotState.activeTaskId`).
3. The botsService `/task <taskId>` command (task.set, `botsService.ts:9444`) rebinds `context.activeTaskId` — but REFUSES with `taskRunning`/blockReason while the previously-bound task runs (`:9451-9461`).
4. `task`, `stop`, `help`, `message`, `approve` are unconditionally allowed regardless of `allowedCommands` (`botConfigHelpers.ts:90-117`) — the daemon bot's minimal allowedCommands does NOT block the bind.

**Router conclusion:** targeting = per-bot context, mutated only by `/task <id>` (or `/new`), confirmed via `botStatus`. No per-message addressing. Race-free binding would need an additive `bindTaskId`/`taskId` field on `inbound` (additive rule: no version bump) or a `bindTask` query — QUEUED as the next natural fork change; the Kotlin workaround is bind-by-command + botStatus confirm.

### 1.2 The embedded Bots feature (`botsService`) — service-level surface

`packages/services/src/bots/botsService.ts` + interface `bots.ts:129-213`. Operations: status/config/workspace refs/bind codes/save/test/states/**handleInboundMessage**/provider callbacks/automation. The daemon channel consumes exactly three: `handleInboundMessage`, `getConfig/saveBot` (add-if-absent), `getBotStates`.

- **Commands a bot chat understands** (`commandParser.ts`): `/leave /history /queue-cancel /enable /disable /bind /help /cancel /status /new /reconnect /workspace|project /model /mode /thoughtlevel|think /task /reply /stop /permission /elicitation|answer /approve /deny`. These ride `inbound` — the daemon must NOT forward its own commands as text (Kotlin intercepts first).
- **Context state:** `BotState` (`packages/shared/src/bots.ts:189-212`): `workspacePath, mode, activeTaskId, draftOptions, pendingPermissionOptions, pendingElicitation, updatedAt` — what `botStatus` returns.

### 1.3 Sidecars, logs, and sqlite — read-only surfaces

| Surface | Path | Written by | Status for the daemon |
| --- | --- | --- | --- |
| live-io sidecar | `<workspace>/.zcode/live-io-<session>.jsonl` | agent CLI | **Obsolete in live mode** (spec §3.3); only loss vs sidecar: full raw tool-input (channel gives parsed input + `raw.streamingRawInputLength`; complete input arrives with the final `tool_call`) |
| CLI log | `~/.zcode/cli/log/zcode-*.jsonl` | agent CLI | post-mortem only |
| tasks index | `~/.zcode/v2/tasks-index.sqlite` (`tasks`: task_id, title, workspace_path, pinned, deleted, archived, workspace_key) | fork | **direct read** by the daemon (`TasksHistoryIo`, `SpectatorIo.taskLabel` — xerial ro) |
| CLI usage db | `~/.zcode/cli/db/db.sqlite` (`model_usage`, `session`, `session_entry` incl. `runtime/model_selection`) | CLI | **direct read** (usage-backed task lists, titles, model/thinking of a session) |
| sessions-index | fork-internal | fork | via `activeTaskIds` query only |
| Python-era state | `~/.zcode/hooks/zcode_bot/state/*`, `telegram-remote.json` | Python bot | read by the Kotlin spectator for the shadow/legacy world |

## 2. What each MVP command consumes (Kotlin today vs Python reference)

Kotlin: `modules/channel/.../BotCommands.kt` (dispatch), wired in `daemon/ChannelWiring.kt` (~:165-182, with `history = TasksHistoryIo.read(...)`; this wiring is hot — landed during the survey).

| Command | Kotlin today | Python (real code) | Where the fork has the missing data |
| --- | --- | --- | --- |
| `/tasks` (Py `/task`) | `TasksHistoryIo` — direct sqlite join (usage ⋈ tasks-index, frozen SQL) + `botStatus` + `activeTaskIds` for 🔄/🎯; pick = `/task <id>` inbound + confirm | `handlers_tasks._cmd_task` → `reporting.task_list_report("projects")` → `tasks_repo.ctx_tasks` (same SQL, caps 10/20/30); buttons `t:<sid>`; `/task new [project]` | Data parity EXACT. Missing: inline buttons, nested subagent rows (`agent_live.task_agent_rows`), `/tasks new` |
| `/all_tasks` | same with `all=true`, cap 20 | `_cmd_all_tasks` → `task_list_report("all")` | idem |
| `/status` | `botStatus` + `activeTaskIds` + title + live view peek | `reporting.status_report`: active + **modelId + reasoningLevel + mode** (`tasks_repo.model_selection_payload` — cli db) + subagents + workers/queues/poller health | Model/thinking line: direct read `cli/db/db.sqlite` (`session_entry` type `runtime/model_selection`) — same file TasksHistoryIo opens |
| `/stop` | `query stop {activeTaskId}` (V4 stopGeneration) | lists phone-owned runs + queues; `/stop go` = kill -9 own runtimes; Mac runs not stoppable from phone | Semantics changed by design (live mode). Missing: stop LIST (activeTaskIds), stop-by-id |
| `/nuclear` | `query stopAll` + daemonReset, immediate | ☢️ confirm button → stops everything + **closes/reopens the app** + restarts bot (`nuclear.py`) | App restart NEVER via channel (golden rule; `restartApp` = new query only if Damian asks). Missing: confirmation, daemon-stack restart half |
| `/quota` | `query quota` → entitlement; CREDIT_LIMIT windows, ⚠️≥75% | z.ai API + cache | Parity via channel (no API key needed) ✓ |
| `/help` | static curated eight | curated ~30 | — |
| `/subagents` | live lanes from stream (`parentToolUseId` != null) | `agent_live.subagents_list` (sidecar index; buttons); `/sa` view; `/cotb /agx /agr` expandable boxes | Live lanes ok. Missing: pick-view, expandable CoT boxes, history (via `taskSnapshot` — verify parenting in snapshot) |

**Views:** live CoT tail exists (`LiveTurn.cotTail`, cap 2500cp, thinking-phase only). Python's richer stack (`reasoning.py` 1931 lines: per-box CoT cache, reader window, hot CoT, agent bundles) has no counterpart yet. `/cot` document maps to `taskSnapshot {messageLimit}` + `send_document` (transport lacks send_document today).

## 3. Gap matrix (highlights)

| Capability | Python | Lives in | Reaches daemon via | Missing |
| --- | --- | --- | --- | --- |
| Free text → chosen conversation | active sid (only_active_task) | `BotState.activeTaskId` | inbound + `/task <id>` bind + botStatus poll | race-free targeting; bind refused while old task runs; no queue when busy |
| Push-or-enqueue on busy turn | «¿empujar o encolar?» buttons | — | `taskRunning` reply + `stop` query + stream `task_complete` | the whole flow (buttons + pending queue) |
| /tasks buttons | inline keyboard | — | — (send path has no markup yet) | inline keyboard EVERYWHERE (structural piece) |
| /status model line | model_selection_payload | cli db | nothing | direct read |
| /nuclear confirm | ☢️/🙅 buttons | — | nothing | confirmation flow |
| Subagent deep views | /sa, /cotb /agx | stream + snapshot | partial | viewer + expandable boxes + send_document |
| api-retry pill | fork logs | session_info_update / task_network_debug_status | stream → ApiRetryPill | — ok |

## 4. Channel rules (and the fork-change vs direct-read decision form)

- **Auth:** token `~/.zcode/daemon-local.token` (0600, 64 hex, rotates every host boot); client re-reads per attempt; hello ≤10s; socket 0600. Kill-switch `ZCODE_DAEMON_LOCAL_CHANNEL=0`; gate #2 = Bots-dialog toggle.
- **Reconnection:** inbound at-least-once + server dedupe by messageId (TTL 2 min); client dedupe by update_id. Stream continuous, NEVER replayed: re-subscribe + `taskSnapshot` resync; `seq` gap = loss. `inbound_result` may arrive late — correlate by id, never block.
- **Limits:** one JSON object per line, ≤24 MiB (`bad_request` fatal). Attachments ≤4×5 MiB.
- **Multi-host:** first live host wins; missing socket = degraded/headless STATE, not error.
- **Decision form when a datum doesn't travel:**
  1. Read-only file? (tasks-index, cli db, cli log, sidecar) → **direct read** (precedent: `TasksHistoryIo`, `SpectatorIo`).
  2. Live Host process state? → **channel**: new `query.kind`, or additive optional field on `inbound` (e.g. `bindTaskId` — the router fix), or new frame only if push semantics needed.
  3. Red line (restart/kill the app, replay stream, fabricate heartbeats)? → **never the channel**; daemon-side or owner-approved explicit query.

## Executive summary — TOP 5 Python-parity gaps

1. **Inbound targeting (router fix).** No `taskId` on inbound; bind only via `/task <id>` (refused with `taskRunning` while the old task runs — botsService.ts:9444-9461). Today: bind-by-command + botStatus polling. Queued: additive `bindTaskId` field (next natural fork deploy).
2. **No markup anywhere.** /tasks /all_tasks data parity is exact (same sqlite, frozen SQL) but the send path has no inline keyboard — the structural piece that unlocks task-pick buttons, nuclear confirm, cot buttons, push-or-enqueue.
3. **/subagents + historical CoT.** Live lanes only; missing pick-view, expandable CoT boxes, `/cot` document (`taskSnapshot` + `send_document`).
4. **/nuclear without confirmation** (and the app-restart half will never be channel-side — golden rule).
5. **/status incomplete** — model·thinking·mode line is a direct cli-db read away.

Key paths: spec `docs/fork/DAEMON-CHANNEL.md` (fork) · contract `packages/shared/src/daemon-channel.ts` · server `packages/services/src/bots/daemonChannel.ts` · task.set `botsService.ts:9444` · Kotlin router `modules/channel/.../BotCommands.kt` · client `ChannelClient.kt` · wiring `daemon/ChannelWiring.kt` · live view `LiveTurn.kt` · history `daemon/history/TasksHistoryIo.kt` · Python: `~/Projects/bot/src/zcode_bot/commands/handlers_tasks.py`, `handlers_info.py`, `handlers_system.py`, `reporting.py`, `tasks_repo.py`.

# Plan Compass — one system, five repos, five things

> Created: 2026-10-30 · Status: v1.17 — updated 2026-11-03 evening (see «Real state» below)
> Decided execution order: **U0 → U1a (parallel) → U2 → U1b → U3 → U4 → U5 → U6 → U7 → U8 → U9 → U10 → U11**
> The compass: Fazt, «To be an AI engineer you don't need to build your own ChatGPT» (10/27/2026, 2:56) — https://www.youtube.com/watch?v=dWyB3EcpNLQ
> This plan unifies and obeys the doctrine that already exists: `docs/parity-and-engineering.md` §7 · `~/Projects/bot/docs/kap-first-migration.md` (+ ADRs 021-025) · `~/Projects/agent-brain/plan.md` (+ ADRs 001-008) · `~/Projects/kap/SPEC-5.0-concurrency.md` · `~/Projects/ZCode/FORK.md` + `docs/fork/ARCHITECTURE.md`.

## Real state at 2026-11-03 (evening)

> This block overrides the checkboxes below: several stayed unticked
> although the work is done and deployed. Verified against repos, code and
> the Mac, not against the plan's text.

**Alive today**
- `daemon.json` = `send: live`, `poll: true`, `event_bus: true`, `memory_inject: true`; the Kotlin daemon is the only getUpdates reader. 21-command menu, channel UP (`hello_ok` v1, caps=22), fork app running 3.17.2.
- Python retired: 0 `telegram-*`/`bot-healer` LaunchAgents loaded. The `bot` repo stays as the parity reference and the rollback door.
- healthchecks.io beacon healthy; the daemon's own healer (`com.zcode.bot-healer`) ticking; daily comparator writing reports.
- `agent-brain`: Postgres+Redpanda in colima (started OUTSIDE ZCode), `serve` as LaunchAgent `com.zcode.agent-brain`, brain-mcp installed in ZCode (manual registry, MCP stdio verified), eval 20/20; the full 5-repo index runs as the one-shot LaunchAgent `com.zcode.agent-brain-index`.
- Bus verified live: real events ingested (0 malformed, 0 duplicates) and `rag_query` over MCP returning real chunks with citations. `memories` at 0 until the first real phone turn (today's events are Mac-session tool calls, with no `turn.completed`).
- kap: 4.3 + 5.0 on master (`7b3836d` → `0177877`; Schedule semiring, Supervisor OneForOne/AllForOne, kap-stream module, JMH vs RxJava 3 — 1,188 tests). agent-brain Phase A deployed: `/bus/status` now reports the `ProcessHandle`'s `restarts`/`heartbeatAgeMs`.
- Nushell: our own scripts are typed `.nu` with a nightly pinned by shebang.
- Operating rule (11/3): every process that must outlive ZCode runs OUTSIDE — a Ghostty tab for interactive work, a LaunchAgent for services, colima included.

**Phases**
- U0 closed (kap 4.3 included).
- U1a DONE: `index-repos` + brain-mcp installed; the full index keeps running.
- U1b DONE and deployed (11/3): BusEvent producer (`event_bus` ON), `/brain` `/memory`, `memory_inject` ON. RAG verified; the real phone turn → memory is missing.
- U2/U2.5 closed. U3: channel and live mode done; E3 (OTel) is missing and so is the decision on `/mode live|headless` (probably obsolete: the headless model was discarded).
- kap 4.3 and 5.0 DONE. U11 in flight (the 5 DX issues; the blog is already published and live on gh-pages).
- agent-brain: Phase A DONE and deployed; Phase C in flight; D, E and G remain (G consumes kap 5.0: Ref/Supervisor/KapStream).
- U5 render core = the next big risk.

**Next checkpoint (in order)**
1. Close U1b live: phone turn → `memories > 0`; the «remembered the next day» gate is verified tomorrow.
2. Merge U11 (kap) + Phase C (agent-brain); deploy the brain if needed.
3. Dogfooding 5.0: KapStream feeding the brain's event tailer (the SPEC's condition for calling 5.0 «done»).
4. E2 metrics / E3 OTel for the daemon.
5. U5 render core B4.

## 0. The compass (the video)

To be an AI engineer you don't need to train models or build your own ChatGPT; you need **five things built on top of the existing models**:

1. **An MCP for your own system** — an MCP server (open standard, any language) so any compatible assistant can use your data/tools.
2. **A RAG that answers with your documents and cites the source** — your own indexing + a vector DB or pgvector.
3. **Memory for your agent** — a separate program where the agent stores conversations and searches; all your tools connect to the same memory (Mem0/Letta style).
4. **Evals** — «unit tests for AI answers»: real questions with expected answers, run against expensive/cheap/local models, with pass/fail and cost (Promptfoo style).
5. **Your own harness** — the program around the model: your own tools (find files, run commands, browse) and the think → use a tool → look at the result → repeat loop. «Claude Code and Codex are exactly this.»

The sentence that makes it a compass: **«they are not five loose projects, it is one single app that grows with each one»**.

### Is this real? Is it enough? (verified with searches, 10/30/2026)

Yes, it is the 2026 consensus — today's roadmaps (roadmap.sh/ai-engineer, KodeKloud, dataskew, etc.) list exactly the same path: fundamentals → LLM APIs → RAG → agents/MCP → evals → production. Data per thing:

- **MCP**: Anthropic's open standard (end of 2024) adopted across the industry — ~28% of the Fortune 500 in <18 months, a CIO-agenda topic. The open challenges are **security** ones (CVEs, malicious servers): learning MCP includes learning its attack surface.
- **RAG**: still essential but evolved — the 2026 consensus is **context engineering** (the window is RAM, not storage): hybrid RAG + reranking, **Adaptive RAG** (a classifier that routes by complexity), Agentic RAG, GraphRAG. Long context did not kill RAG: it wins on cost, citation and scale.
- **Memory**: a production discipline with its own benchmarks («State of AI Agent Memory 2026»); Mem0 (a memory layer) vs Letta (a stateful runtime, git-based «Context Repositories» since Feb 2026). Exactly what agent-brain already does (extraction + decay + recall).
- **Evals**: Promptfoo/DeepEval/LangSmith/OpenAI Evals are standard practice — including **red teaming** (Promptfoo ships it). This repo's parity comparator IS a migration eval harness.
- **Harness**: a recognized learning path (open-source courses, «loop engineering, memory, eval»); Claude Code and Codex are the references. Building your own is the recurring recommendation.

**What is beyond the video** (the complementary learning map): context engineering as the umbrella discipline · security/red teaming (prompt injection) · multi-agent orchestration · model selection (LLM/SLM, local with Ollama) · structured outputs/function calling · fine-tuning vs RAG (when each) · cost/latency budgets · agent observability (OTel).

## 1. The map: five repos, one system

| Repo | Role in the system | State today | Its own route sheet |
|---|---|---|---|
| `ZCode` (fork, `damian` branch) | The desktop harness (Electron) + the future «daemon local» channel (B3a) | Clean; 30 fork commits; Waves 1-8 closed and deployed | `FORK.md`, `docs/fork/ARCHITECTURE.md` |
| `bot` (Python) | The original Telegram↔ZCode harness; the source of truth retired in B5 | Clean; 1012 tests; the parity week accumulating since 10/29 12:37 | `docs/kap-first-migration.md` + ADRs 001-025 |
| `zcode-bot-kotlin` | The replacement: harness on KAP (strangler by file contracts) | **B2a transport uncommitted** (11 files, no tests) + toolchain 25 | README + `docs/parity-and-engineering.md` |
| `agent-brain` | The brain: bus + RAG + memory + evals + MCP, all Kotlin on kap | **Phase B uncommitted**; P0-P6 done, P7 planned | `plan.md` (F→B→A→C→D→E, G interleaved) + ADRs |
| `kap` | The ecosystem's stdlib (Maven Central 4.2.0, 1112 tests) | Clean; v4.2.0 released today | `SPEC-5.0-concurrency.md` + 8 open issues |

**Telegram (investigated)**: there are two mechanisms in the fork — (a) the external bot by files (`live-io-*.jsonl` sidecars, CLI logs, hooks; the one Damian uses) and (b) upstream's embedded «Bots» feature (`telegramProvider.ts`, complete and tested, NOT used). ADR-022 already evaluated (b) and decided: the Kotlin daemon owns Telegram; the fork gains a «daemon local» channel (unix socket) that injects through the same path as the composer. In force.

## 2. The five things → where they live and what is missing

| Thing (video) | Where it lives | State | The real gap | First action |
|---|---|---|---|---|
| 1. MCP | `agent-brain/brain-mcp`: `rag_query`, `memory_search`, `memory_recent`, `bus_status` | Code done (P5) | **Not installed in Damian's ZCode** (`make plugin` pending) | Install it and use it from ZCode |
| 2. RAG | `agent-brain` P2: hybrid pgvector + RRF + SQL dedup + decay | Code done | **No live data**: `event_bus` off; and **there is no repo indexer** (the RAG Damian wants: ask his chats AND the Mac's repos) | Turn `event_bus` on + deploy; then the repo indexer |
| 3. Memory | `agent-brain` P3 (ADR-008) + injection into turns (bot ADR-020) | Code done | `memory_inject` off; `/brain` `/memory` never used live | Turn the knobs on + try |
| 4. Evals | `agent-brain` P6 (EvalRunner, ADR-006 tiers) + comparator B1c (the cutover gate) + 21 golden vectors + replay | Running (gates B1) | EvalRunner→Resource (brain phase B); the real tier-5 never ran; the comparator unscheduled | Close phase B; schedule the comparator |
| 5. Harness | The whole system: fork (desktop) + bot→Kotlin daemon (phone) | Migration B2 in flight | B2 transport unfinished; B3a/B3b live mode; K1/B4/B5 | Finish B2a (tests + commit) |

**Compass reading**: the five things are already built (or under construction) — the plan is not to start from zero, it is to **turn on, finish and unify**: the brain is off (knobs), the harness is half migrated (B2), and the evals are already gating the cutover.

## 3. Fixed rules (already decided; this plan obeys them)

- **FP-first binding** (§7): pure by default; **KAP is the stdlib** — whatever is missing is implemented IN kap with laws and a version bump («extend-and-bump»); Arrow for errors; laws before features.
- **Denotational always** (reinforced 10/30, binding): pure values, pure functions, ADTs, monads/applicatives/functors — everything abstract, pure and denotational. The impure/dirty/mutable only when there is no alternative, with a written justification at the visible edge. **Zero manual concurrency**: `while(true)` and homemade loops are replaced by kap operators (`repeat`, `awaiting`, `awaitUntil`, `Process`, `Schedule`); a permanent sweep for these patterns (an anti-while(true) gate in CI, like agent-brain F1).
- **Permanent real feedback** (reinforced 10/30, binding): the full pyramid unit → integration → e2e → **blackbox e2e** (real apps up, docker if needed, real Telegram with real messages and logs) — the T1-T7 pyramid of parity-and-engineering.md §4. Nothing is taken for granted without a real run.
- **Nushell everywhere** (owner's decision 10/30, binding — after the proof of concept `deploy-fork-and-retire.nu`, commit `062e531`): EVERY new script is a `.nu` — including the ones that would have been bash or Python. The newest version with static typing (nightly with `type`, interpreter pinned by absolute shebang), official documentation, **Kotlin style**: types everywhere, immutable by default, try/catch/finally, record matching, pipelines first, zero embedded bash. Gate: clean `nu-check` + REPL verification of every construct. **All our own .sh files were migrated to .nu on 2026-11-02**; vendored/upstream `.sh` (ZCode skills, husky, diff-ask fixtures) stay as they are because they are not ours and migrating them breaks merges. Knowledge base: `~/Projects/nu/` (numbered learnings + symlinks to the canon).
- **Strangler, not big-bang**: file contracts as the only boundary; golden vectors pin the contracts; Python is not touched until B5.
- **Real gates**: the parity week (`comparator --week` = 7 consecutive PARITY) + ≥2 weeks of recorder traces → only then the cutover; rollback = the knob back.
- **ADR-022 (live mode)**: the Kotlin daemon owns Telegram; the fork forwards free text to the real conversation over a unix socket.
- **Observability E1-E5** mapped to phases: E1✓(B1) · E2 metrics@B2 · E3 traces@B3b · E4 dashboards@B4 · E5 runbook@B5.
- **Commit + push always** (10/28 rule); suite + detekt green before committing; one narrative commit per phase.

## 4. The unified route sheet

### U0 — Close what is open (days)
- [x] `zcode-bot-kotlin`: **B2a complete** (10/30): 4 commits pushed — `e2d514a` (docs: plan compass U0-U11) · `7f00b28` (B2a transport: the full module + **79 tests** pinning the contracts against the real Python — outbox bytes produced by `_outbox_put`, truncate goldens of formatting.py, cross-process flock with a python3 child holding the POSIX lock, breaker F79e case by case, the full 429/cooldown/outbox/send path with the API mocked; PyText→contract-types as a rename; toolchain 25 + detekt 2.0.0-alpha.6 with regenerated reports) · `a04b36b` (B1c+: the daily comparator's LaunchAgent) · `60980ed` (B1b+: the spectator's turn body as an algebraic schedule). CI green (run 40923476271). Daemon redeployed: heartbeats + `transport selftest OK: bot=@glm_lattenero_2_bot` — the transport's first real getMe. Suite 168 tests, detekt 0 findings.
- [x] `agent-brain`: phase B closed and pushed (10/30, commit `642f2ee`): B1+B2+B3 complete — a single `inTransaction` bracket over kap's `bracketCase` (rollback on cancellation too, autocommit restored NonCancellable), EvalRunner as a `Resource` (the eval gate leaves zero residual bases), brain-mcp's stdout guard as a `Resource`. Gates: 132 tests + bbLocal + detekt + replay goldens + eval gate 20/20 with the local model + compose healthy. Findings on the way already scheduled by plan.md (A1 Resource.zip, A4 shutdown→awaiting, C4 http blocking, E7 injected clock).
- [x] Schedule the daily comparator as a **LaunchAgent** (decided 10/30; done: `com.zcode.bot-comparator` loaded, StartInterval 86400, a wrapper that never fails launchd — INSUFFICIENT and DIVERGENT are valid verdicts, not crashes; reports in `~/.zcode/bot-kotlin/parity/`). **First run: DIVERGENT** — a second real divergence caught (see the investigation item below).
- [x] **Manual-concurrency/while(true) sweep** (audit done 10/30): 3 MUST-FIX in zcode-bot-kotlin (Outbox flock `while(true)` in the WIP, SpectatorWatch polling loop, TgBreaker's homemade Mutex+vars), 1 mild in agent-brain (Traces' global `AtomicLong` — waiting for kap `Ref`), 0 in kap (only an example-documentation apart). `GlobalScope`: zero in all three repos. **SendPolicy: canonical use of `kap.Schedule`** (it does not duplicate the algebra).
- [x] Audit fixes in zcode-bot-kotlin (done 10/30): Outbox flock → `Kap.retry(doWhile and spaced(100ms).withMaxDuration(10s))` with the exact IOException (semantics verified against kap's Schedule.kt; in `7f00b28`) · anti-while(true)/do{} gate in ci.yml (hit classified: recorder, exempt) · SpectatorTurn → the `Keep/MarkerGone/Takeover` tick ADT under `repeat(doUntil and spaced(1s) and times(2099))` — the 35 min cap now has its own test (in `60980ed`) · daemon redeployed. Small pending item: refactor the recorder's `do{}`+`Thread.sleep` (dev-only CLI; launchd owns the cadence).
- [x] **The comparator's divergence #2** (caught 10/30 day 1; resolved the same day, commit `e455af8`): (a) zconn — the port was faithful; the **memories differed**: Python loaded a stale `first_down` from 10:12 (a wart that is never cleared) and treated the 11 s cut as a silent sputter, while the shadow was born at 11:23 with a virgin track and announced (a reborn Python would have announced the same) → fix: the shadow's first arm **adopts Python's live track** (`hooks/state/zconn.json`, one-shot read, pure `armTrack`); (b) spectator — a **comparator bug**: Python's pin fell 0.468 s «before coverage» (pwlog truncates to seconds, the turn's header was the coverageStart) → fix: *coverage never separates twins* (rescue-match in the boundary zone, in-coverage first, no-steal pinned in a test). 6 tests with the day's real numbers (suite 174). Window restarted clean: pre-fix archived in `shadow-archive/`, shadow re-armed by adoption, daemon+comparator redeployed, INSUFFICIENT 0/0 verdict — **the week accumulates from zero**.
- [x] **kap 4.3 «quick unlocks»** — **DONE 11/3** (merge `7b3836d`, gate `build detektAll` green, 1,134 tests): `CircuitBreaker(successThreshold, retryAfter)` (deprecated `resetTimeout` alias for compatibility) · `Schedule.delays(Array/List)` — the `vararg Duration` is impossible because of the value class — · the value→delay operator `Schedule.delayBy(f)` · `Ref<A>` in kap-core · the `kap-detekt-rules` module with the `ManualWaitLoop` rule (import-aware, unpublished). Item origin (audit + B2a candidates): `CircuitBreaker(successThreshold=2, retryAfter)` to retire TgBreaker (Python F79e requires 2 successes in half-open; kap closes with 1 and does not report retryAfter) · `Schedule.delays(0,1,3)` (a finite table of delays — the spaced/exponential/linear/fibonacci formulas are not a list) · **a value→delay operator with replacement semantics** (the 429 names the window: `ra+0.5 capped 60` OVERRIDES the table's delay; `and`/`or`/`zipWith` merge by max/min and cannot express it) · `Ref` (brain asks for it: Traces and the metrics' `@Volatile`/AtomicLong) · **the kap-detekt-rules module** with the «manual wait loop» rule (while-true / while+delay / do-while+sleep in src/main outside kap) — the ecosystem-wide gate; brain's F1 grep has blind spots (only the literal with one space, it sees neither non-literal polling nor do-while, hardcoded modules).
- Exit gate: both repos clean, CI green, the parity week running on its own.

### U1a — The brain in read mode (in parallel with U0/U2; does NOT touch the live bot)
- [x] **Repo RAG** — **DONE 11/3**: `index-repos [--dry-run] [--repo NAME]` over `$HOME/Projects/{kap,bot,zcode-bot-kotlin,ZCode,agent-brain}` (commit `f505c41`); heap `-Xmx768m` after a real OOM (17,450 docs / 154.7 MB); doc ids = Postgres serial by `uri`. Running as a one-shot LaunchAgent. (An indexer of `~/Projects` (kap, bot, zcode-bot-kotlin, ZCode, agent-brain — docs and code) as a new agent-brain source (outside the bus), queryable with `rag_query` from ZCode and Telegram. Ingestion respects the ADRs (single pgvector, dedup in SQL).)
- [x] `make plugin` + install brain-mcp in Damian's ZCode — **DONE 11/3** (`de8db22` doc + manual plugin registration, MCP stdio verified) → `rag_query`/`memory_search` from the chat. Gate: **MET** — `rag_query` over MCP answers with real citations from our own repos.
- Gate: `rag_query` answering with citations over our own repos, from ZCode.

### U1b — Turn on the bus and memory (reframed 2026-11-02: the cutover already happened and Python no longer exists)
**The original plan said «set `"event_bus": true` in `~/.zcode/telegram-remote.json`». That was a Python bot knob, and it is retired. The Kotlin daemon does NOT publish `BusEvent`: `modules/contract-types/.../BusEvent.kt` is only the read contract and `~/.zcode/agent-brain/bus-events.jsonl` does not exist. U1b is not turning a knob on; it is building the producer.**
- [x] **Decide the producer** — **DONE 11/3**: ported to the Kotlin daemon behind the `event_bus` knob (commit `329699a`; taps: user.prompt/turn.started/tool.call/tool.result/turn.completed/turn.failed, the real `reply` from TaskSnapshot, BusSink O_APPEND). Verified live: real events ingested with zero malformed ones.
- [x] Port `memory_inject` to the Kotlin bot — **DONE 11/3** (`5fe5675` `/brain` `/memory`; `6f0ea49` memory_inject ≤1500 cp, top-3, 1.5 s, knobs ON).
- [ ] Verification: a real conversation is remembered (memory) and queryable (RAG) the next day. *In flight 11/3: RAG verified over MCP; the real phone turn (memory) and the next-day gate are missing.*
- Gate: memory cited in a real turn + `/brain` answering in Telegram.

### U2 — B2: the transport — **CUTOVER DONE 10/30 (commit `56a7229`)**: the daemon owns the send
- [x] `shadow|dry-run|live` knob in `daemon.json` (read once at boot; typo → SHADOW loudly; +24 tests, suite 197). DRY-RUN deployed 15:41 with a real selftest + shared contracts verified (tg-last-ph=19447 = Python's newest).
- [x] **FLIP 15:54**: mode=live, Sender wired to the shared files (tg-cooldown read+write, flock-compatible outbox, tg-last-ph), Python silenced (`mac_spectator=false` + zconn quiet — last announce 15:32), single-reader respected (Python kept polling getUpdates; the daemon NEVER polls in B2). **Kotlin's first real send: `zconn/up message_id=19454` at 16:00:40. Zero duplicates.**
- [x] Rollback documented as a one-line command in the README (knob back to shadow + Python send on).
- [ ] E2: metrics (Micrometer/Prometheus) in the daemon (moves to the post-B3b queue).
- Post-cutover audit: the comparator stays daily; with Python silenced its spectator/zconn evidence fades (moot by the aggressive cutover's design).

### U2-parallel — B3a: the fork's channel (the «single path») — **DONE 10/30, commit `9b814dcb` (origin/damian)**
- [x] `ZCode` fork: complete «daemon local» channel — socket `~/.zcode/daemon-local.sock` (JSONL, versioned v1 protocol, owner: the host process), **mandatory auth** (64-hex token in `~/.zcode/daemon-local.token` 0600, rotated on every boot, hello-first handshake, timing-safe comparison, two-host guard). Messages: `hello/inbound/subscribe/stream_event/query/stop/stopAll/compact/ping/bye`. The «daemon» provider integrated natively (replies come back over the socket; gate = the Bots dialog toggle + kill-switch `ZCODE_DAEMON_LOCAL_CHANNEL=0`). **It covers the WHOLE MVP**: the raw V4 stream (`agent_thought_chunk`, `tool_call` + updates with `toolName` and `streamingRawInputLength` for the line/char counters, `parentToolUseId` for subagents, `at` epoch in the envelope), queries (`listTasks`/`taskList`/`activeTaskIds`/`quota`/`taskMeta`/`botStatus`), control (`stop`/`stopAll`). Green pipeline: typecheck + oxlint + architecture check + 10/10 hermetic tests (real socket and token) + bots suite 491/491. **Spec: `docs/fork/DAEMON-CHANNEL.md` + normative shapes in `packages/shared/src/daemon-channel.ts`** — B3b's contract. Deploy pending (one ⌘Q from Damian when the global flip is due; rollback = any earlier build has no channel).

### U2.5 — B3b + abrupt retirement: the Kotlin bot at 100% — **✅ COMPLETE 10/29 17:59 (rc=0, 6/6 phases)**
The final path (four runs, every failure a measured pothole and fixed): B2 flip LIVE (15:54) → B3a channel in the fork (`9b814dcb`) → B3b complete bot (`5948d34`) → typed Nushell automaton under launchd (`139eb20`) → **cutover 17:53-17:59**: app dead with no resurrection (bootout of the launchd job), fork built+swapped, channel verified (hello_ok v1, caps=12), **Python retired (0 LaunchAgents)**, the daemon poller beating, Telegram narrated everything. Potholes on the way (all documented in ~/Projects/nu/001): pgrep blind to the app · zombies that lie in nu's ps · launchd resurrects the app ~8s after SIGKILL (bootout is the real death) · nohup dies with the app's tree (the launchd one-shot is the correct cradle) · the human reopens the app during the build (retry with re-quit).
- [x] All U2.5 items. **Hot-fix loop ACTIVE since 17:59.** Queue: (1) free text → the ACTIVE conversation + the **push-or-enqueue** flow (taskRunning → [⚡ push][⏳ queue]; push = stopGeneration + resend; queue = pending until task_complete) + /tasks with history + setMyCommands//help + selection buttons + /stop //nuclear soft-with-button vs `go` + the model·thinking line in /status + Python's byte-for-byte emojis [agent running; hardened-bind workaround, additive `bindTaskId` queued for the fork's next deploy] · (2) **THE LIVE VIEW — the owner's most beautiful and fundamental visual feature**: read the CoT live as it fills its own little boxes · while a file is being edited, the thinking box adding UP the lines it modifies LIVE and at the end the TOTAL removed and added (+N/−M) · **loading with seconds for everything happening** — everything quantifiable visible and being measured (chars thought, lines written, diffs) · general thinking: «thinking… 🧠» + seconds of how long it has been thinking · CoT collapsing into a button at ~3k («cot 1.x») · a permanent «thinking» line with verbs (reading/writing/executing shell command) — port the heart of reasoning.py+live_view.py («in Python it wasn't identical but it was close to this») · **(2b) shared DESIGN SYSTEM** (the owner's spec): a pretty interface with color coding — 🟢 tasks · 🔵 subagents with ▶️ running/⏹️ stopped by subagent · **FUNDAMENTAL FILE RULE: every file shown carries line numbers NO MATTER WHAT (diff or normal), and diffs mark an added line in green (or 🟢 if there is no color) and a removed line in 🔴** — the spectator's renderer already complies byte-exact (ToolBox + ported difflib, 21 golden vectors); the rule extends that convention to EVERY file display (/cot, subagent views, live view previews) · a single render module (emojis+HTML entities, extracted from Python's formatting.py/reporting.py) used by commands AND the live view · (3) the interface map (docs/fork-interface-map.md, `2b24d6f`) guides everything that comes.

**MVP defined by the owner (10/30, locked):**
- Commands: `/tasks` (ex `/task`) · `/all_tasks` · `/status` · `/stop` · `/nuclear` · `/quota` · `/help` · `/subagents`. Everything else is dropped (hot-fix later if it hurts).
- **Mandatory**: live CoT streaming · a «thinking» spinner · **cumulative progress** (writing → lines growing; thinking → chars growing) · a **timer** of how long each thing has run · a **visible heartbeat** · **never radio silence** (know what ZCode is doing at every moment — periodic in-place editing of the status message, like Python's blinking line) · **error surface: ALL the fork's typed connection/streaming errors visible in the bot** (complete inventory 10/30 with verified file:line: 13 errors, the fork's `ModelRetryReason` taxonomy; **critical finding: zconn's countdown was synthesized** — a 1308 usage-limit with a real 10.5 h wait was announced as «next retry 2s»; fix in B3b: the `model.network.retry_scheduled` needle with a real countdown = timestamp+delayMs + rendering `session_info_update{apiRetry}` in LiveTurn — the desktop pill's exact state already travels over the channel and fell into the `else` + the `reason:"cancelled"` filter that fired false pills on ⌘-stop. Cheaper paths first, all pure Kotlin; highspeed and the structured resetAt out of scope).
- Source of the live events: the channel's `bot-channel-continuous` stream (agent_thought_chunk, tool_input_delta, task_complete) — the single path; live-io sidecars only as a documented fallback.

- [x] The daemon socket's client (B3a's ADR protocol, retry/reconnect as a Kap policy).
- [x] Our own poller: getUpdates (TgOffset already exists) + a minimal dispatcher.
- [x] The live router: free text → socket → the REAL conversation; the continuous stream → the existing renderer (spectator/zconn) → Telegram.
- [x] The MVP's 8 commands (the channel's service queries; /stop and /nuclear via control ops; /subagents by parent info).
- [x] **Live view**: the in-place status message (spinner + timer + progress counters + heartbeat) edited at a Telegram-safe cadence.
- [x] Full daemon deploy + fork deploy (when Damian can restart the app).
- [x] **Abrupt Python retirement**: unload the LaunchAgents (com.zcode.telegram-cmds, watchers, bot-healer) and total silence.
- [x] End-to-end verification: a real conversation from the phone over the single path.
- [x] **Hot-fix loop**: the daemon survives by its Process/RestartPolicy + KeepAlive; every failure is fixed and committed hot. The loop continued on 10/31 and 11/2: answerable questions/permissions, `/steer`, `/plan`, `/context_window`, `/last`, `/compact`, `/queue`, `/subagents`, `/verbosity`, `/file`, `/diff`, attachments, the transcript feed, the stream heartbeat and the CoT counter fix.
- **Trigger: FULLY AUTOMATIC (the owner's decision 10/30: «I want it done on its own»)** — when B3b and the automaton finish, main reviews the results and launches `nohup scripts/deploy-fork-and-retire.nu --real & disown` (with a ~30 s grace so the last message arrives). The automaton narrates the 6 phases over Telegram (quit → build+swap → reopen → verify → retire → final), ZCode reopens on its own, and the session resumes on the fly for verification. Without a single word from the owner in between.
- What degrades at first (accepted by the owner, recovered hot/later): the CLI's permission hooks (▶️✅❓🔐), warm worker/rotation, per-turn `model:`, thought levels, incoming attachments, /brain //memory (waiting for U1b). The parity audit loses its Python evidence when Python is retired (moot by design); the safety net becomes the golden vectors, the tests and real traffic.

### U3 — Live mode (B3a + B3b)
- [x] `ZCode` fork: the «daemon local» channel (unix socket): `handleInboundMessage` + the `bot-channel-continuous` stream + service queries (listTasks/quota/meta).
- [ ] `zcode-bot-kotlin`: live|headless routing, `/mode live|headless`. *Probably OBSOLETE: the headless model was explicitly discarded in the doctrine — confirm and delete it from the plan and the code.*
- [ ] E3: OTel spans (one span per turn, a child per tool call).
- Gate: a turn from the phone travels through the real conversation (not headless).

### U4 — kap 5.0 (K1): dogfooding decides
- [x] `Supervisor` (OneForOne/AllForOne) with laws — **DONE 11/3** (kap 5.0, `401b05c`; 17 tests).
- [x] `agent-brain` phase A on top: Brain as `Resource.zip`, the three loops as `Process`, shutdown with `Kap.awaiting` — **DONE 11/3** (merge `458d7b6`, deployed; `/bus/status` reports `restarts`/`heartbeatAgeMs`).
- [x] `KapStream` (backpressure as values) + `Schedule` semiring + benchmarks — **DONE 11/3** (kap 5.0; JMH: flatMap 1.02x raw Flow; map/filter 3.0x/2.4x of raw but 1.39x/1.08x of one suspend call per element — documented in the benchmark and CHANGELOG).
- [ ] `agent-brain` C and D (sealed ADTs + kap-arrow; state-as-values). *C in flight 11/3 (`agent/phase-c`).*
- Gate: the full SPEC-5.0 + used on a real surface (the dogfooding contract). *(SPEC complete 11/3; the dogfooding — KapStream in the brain's event tailer — waits for Phase G.)*

### U5 — Render (B4)
- [ ] Render core (~4.9k LOC: boxes, blinking line, agent live) on KapStream. *The plan's biggest risk.*
- [ ] E4: Grafana dashboards (agent-brain compose) + T7 chaos drills.

### U6 — Close-out (B5)
- [ ] Thin hybrid hooks (socket to the daemon); a single LaunchAgent artifact.
- [x] **Python retirement** (done 10/30 in U2.5; the `bot` repo stays as reference/rollback).
- [ ] Runbook ported (E5).

### U7 — Our own evals (thing 4 as a tool, not just a gate)
- [ ] agent-brain's EvalRunner becomes a product: real questions + expected answers + pass/fail + **cost and latency** per run, model comparison (expensive/cheap/local), CI gates.
- [ ] Our own use cases: choosing the bot's model per turn, renderer regression, RAG quality (a correct answer with a citation).
- Gate: one eval run comparing ≥2 models with pass/fail + cost, reproducible with one command.

### U8 — Public MCP (thing 1 open to the world)
- [ ] brain-mcp goes from local plugin to an MCP server consumable from any client (token auth, minimal documented surface).
- [ ] Evaluate a second MCP: the Telegram/agent system (send, turns, state) as an MCP server with its permissions. **New candidate (10/30): Nushell as an MCP server** (`nu --mcp-transport http`, native since 0.116) — a fast path to expose system operations as MCP tools without building a server from zero; evaluate against brain-mcp/ktor.
- Gate: an external client (another machine / another MCP client) using the brain with auth.

### U9 — Security / red team (cross-cutting, systematized here)
- [ ] Red teaming of the new surfaces: prompt injection over the bot (incoming messages), B3a socket auth, the MCP surface (U8), tokens in configs.
- [ ] Documented drills with findings and fixes; ideally automated (Promptfoo red-team style) as part of U7.
- Gate: a complete drill run with findings closed or mitigated.

### U10 — agent-brain's P7 panel (post-MVP, already planned)
- [ ] The ui.surfaces panel (diff-ask style): memory + RAG + bus status, reading the `serve` HTTP API.
- Gate: memory and RAG browsable from the panel.

### U11 — kap marketing (the ecosystem's public piece)
- [ ] Publish `blog-draft.md` (docs/blog), minor issues (#14 matchers, #16 KDoc, #19 errors, #20 Dokka, #18 Playground).
- Gate: post published + DX issues closed.

## 5. How this is executed from this chat

- One task at a time; **one subagent per repo** that executes completely (tests + detekt + CI) and **commits+pushes on its own** (decided 10/30); main coordinates and reviews the delicate points (cutover, migrations, auth).
- Every advance updates this file (changelog below). **New docs the plan generates are in English** (decided 10/30); this plan is in English.
- The parity week and the recorder run on their own: the Python bot is not touched except for config deploys (only at U1b).

## 6. The underlying goal: learning AI engineering by building

The stated purpose (10/30/2026): **becoming an AI engineer by building our own system** — the Telegram bot, the RAG over chats and repos, the MCPs. Each phase of the plan teaches one piece of the 2026 roadmap:

- U1 → hybrid RAG + memory + MCP (3 of the 5 things, live, over our own data)
- U2/U3 → a production harness (transport, live mode, resilience with kap)
- U4 → type-safe orchestration (our own differential piece: kap)
- U2-U6 → evals and observability as real gates (E2-E5, comparator, golden replay)
- Cross-cutting → security of the new surfaces (B3a socket auth, MCP red teaming)

## 7. Changelog

- 2026-11-02 v1.16: **cycle close**. Real state added at the top (§ after the header) because the checkboxes had gone stale. The 13 ecosystem `.sh` files of our own migrated to typed `.nu` with the `0.116.2-nightly.28` nightly (deploy-daemon/recorder/comparator/retire-python, build-install/verify-deploy/bootstrap-diff-ask/sync-upstream, bootstrap/deploy/bot-revive, brain's bootstrap and kap's verify-claims); `--when-quit`'s detach went from Python to a launchd job (`launchctl submit`). U2.5 and U3's first item got ticked. U1b was rewritten: Python retired ⇒ `event_bus` is no longer a knob, the Kotlin daemon does not publish `BusEvent` and the producer must be ported. `zcode-diff-ask` 0.9.20 closed with unit + check + e2e. `agent-brain` green with Postgres/Redpanda up: test + bbLocal + detektAll + eval 20/20. Worktrees `zcode-bot-feed`/`zcode-bot-superset` removed.
- 2026-10-30 v1.15: **Nushell everywhere** — a new stone rule (§3): every new script is `.nu` (goodbye bash/Python for scripts), typed nightly pinned by shebang, Kotlin style, nu-check gate, knowledge base `~/Projects/nu/`. The canonical automaton is already `deploy-fork-and-retire.nu` (`062e531`: dryrun parity 23/23, fail-path rc 3 verified, zero embedded bash, the agent's DX report in `~/Projects/nu/001-first-deploy-script-learnings.md`). Pending: B3b (the trigger's last piece).
- 2026-10-30 v1.14: typed-error inventory COMPLETE (13 errors, verified file:line, classification ALREADY-FLOWS/LOG-UNUSED/CHANNEL-YES/UI-ONLY). **Critical bug found**: zconn's synthesized countdown (a real 10.5 h announced as «2s»). Fixes sent to B3b: rendering `session_info_update{apiRetry}` (the desktop pill already travels over the channel), the `retry_scheduled` needle with a real countdown, the cancelled filter (false pills on ⌘-stop). Zero fork changes needed. Hardened automaton: pgrep/pkill do NOT see the app's process (rewritten argv) — detection by exact ps comm (`907e1e4`). **New Nushell adoption line** (owner's decision): the canonical automaton migrates to `deploy-fork-and-retire.nu` — Nushell nightly 0.116.2 with the `type` keyword (parser-time type errors, the owner's proof of concept: «I do not tolerate untyped languages»), fully idiomatic (try/catch/finally, record matching, typed flags, zero embedded bash), .sh as a frozen fallback; the knowledge base `~/Projects/nu/` is born (numbered learnings + symlink to the script) and Nushell-as-MCP-server enters as a U8 candidate.
- 2026-10-30 v1.13: the final trigger is automated too (owner's order: «I want it done on its own»): when B3b + the automaton complete, main launches deploy-fork-and-retire detached — Telegram narrates, ZCode reopens, Python retires, without any gesture from the owner.
- 2026-10-30 v1.12: **B2 CUTOVER COMPLETE** (`56a7229`): shadow|dry-run|live knob, dry-run verified 15:41, **flip 15:54, first real send 16:00:40 (message_id=19454), Python silenced with no duplicates, rollback documented**. The agent died on finishing — everything committed/pushed/deployed; main verified the flip live. B3b's build launched (the last piece before the abrupt retirement).
- 2026-10-30 v1.11: **B3a COMPLETE** (`9b814dcb` in the fork): the daemon-local channel with auth, v1 protocol, raw V4 stream (covers the MVP's counters), queries and control — the fork's whole pipeline green (491/491 bots suite + 10/10 channel). Spec in `docs/fork/DAEMON-CHANNEL.md`. Missing: the B2 flip (agent running: knob + dry-run + flip), then B3b on the spec, deploy and the abrupt retirement.
- 2026-10-30 v1.10: **the Kotlin bot's MVP defined by the owner**: 8 commands (/tasks /all_tasks /status /stop /nuclear /quota /help /subagents) + hard UX requirements (live CoT streaming, spinner, cumulative lines/chars progress, timers, visible heartbeat, NEVER radio silence). Streaming becomes first-class in the B3a channel (a message to the fork's agent with the needed V4 events). With this, the exit happens immediately on retiring Python; the rest is dropped/hot-fixed.
- 2026-10-30 v1.9: **owner directive: abrupt Python retirement and hot fixing.** When B2+B3a finish, U2.5 runs automatically (complete B3b live wiring → deploy → unload the Python LaunchAgents → end-to-end verification → hot-fix loop). B5 (retirement) moves ahead to U2.5; B4 (full render) becomes the recovery of what degraded. No more gates or questions in that chain.
- 2026-10-30 v1.8: user's decision — **aggressive B2 cutover THIS WEEK** (minimal dry-run + a flip with rollback armed; the parity week and the recorder become post-cutover audit, they no longer block) and **B3a starts in parallel now** (the fork's unix-socket channel: the «single path» like ZCode's original bot). §3's gate rule is amended for this cutover by the system owner's explicit decision.
- 2026-10-30 v1.7: **U0 CLOSED completely** — divergence #2 resolved (`e455af8`): zconn = a memory difference (the shadow now ADOPTS Python's live track at birth; the port was faithful), spectator = a comparator bug (the coverage boundary split twins by 0.468 s; coverage never separates twins). Parity window restarted clean (INSUFFICIENT 0/0). U0's exit gate met: clean repos, CI green, the week accumulating on its own. Next: U1a.
- 2026-10-30 v1.6: **U0 COMPLETE** — bot: 4 commits (`e2d514a` docs · `7f00b28` B2a + 79 tests · `a04b36b` LaunchAgent · `60980ed` SpectatorTurn as an algebraic schedule), CI green, daemon live with the transport's first real getMe, comparator scheduled; brain: phase B (`642f2ee`). The comparator caught its **divergence #2** on day 1 (zconn: an 11 s down not announced by Python; spectator: a group with steps=4 without a pair) → investigation open; the parity week does not accumulate until the fix + restart. kap 4.3 gains a new candidate (a value→delay override for the 429).
- 2026-10-30 v1.5: U0 advances — agent-brain phase B CLOSED (`642f2ee`, B1+B2+B3, all gates green including eval 20/20 with the local model). Pending: the bot's B2a agent (transport + tests + audit fixes + comparator LaunchAgent).
- 2026-10-30 v1.4: U0 impurity audit complete — 3 MUST-FIX in the bot (Outbox/SpectatorWatch/TgBreaker), 1 mild in brain (Traces→Ref), kap clean; SendPolicy confirmed canonical; brain's F1 gate has blind spots and kap has no detekt rules (the module must be created). Derived fixes in flight with the B2a agent; kap 4.3 quick unlocks added to U0.
- 2026-10-30 v1.3: fundamentals raised to stone rules (user's order): §3 gains «Denotational always» (impure only with justification at the edge) and «Permanent real feedback» (blackbox e2e with real Telegram); U0 gains the while(true)/manual-concurrency sweep. U0 starts with subagents (transport+commit, agent-brain phase B, comparator LaunchAgent, the sweep).
- 2026-10-30 v1.2: second round of decisions — subagents execute and commit (main reviews the delicate parts) · after B5 EVERYTHING enters as phases: U7 (our own evals), U8 (public MCP), U9 (red team), U10 (P7 panel), U11 (kap marketing) are born · new plan docs in English.
- 2026-10-30 v1.1: user's decisions — the plan lives in this repo (docs/) · a daily comparator as a LaunchAgent · **U1 splits**: U1a (repo RAG + brain-mcp, without touching the bot) starts in parallel with U0; U1b (event_bus + memory_inject) comes right after the U2 cutover, which is accelerated for that reason.
- 2026-10-30 v1: the plan is born. Context gathered with 5 subagents (one per repo); the video's transcript read in full; Telegram investigated (two mechanisms; ADR-022 in force); the state of the art validated with searches (MCP/RAG/memory/evals/harness, the 2026 consensus); the learning goal (§6) and the repo RAG in U1 added.

# Command parity plan — the "bot completo" batch (2026-11-02)

The per-command, per-unit work plan agreed with the owner after the full
reconnaissance of the retired Python bot (`~/Projects/bot`, byte-exact
behavioral spec), the live Kotlin daemon, and the fork's channel surface.
Companion of `plan-brujula.md` (the U0–U11 roadmap — this batch is the
post-U2.5 hot-fix queue, NOT a re-order of the compass) and
`parity-and-engineering.md` (the verdict matrix this plan executes).

## State of the world at planning time

- The live bot registers 15 commands (+ `/file` `/diff` intercepted), the
  transcript feed, the question round-trip and the multi-bot lanes. All
  deployed (fork caps=16, daemon master 294141f + 0e46e77).
- **The button round-trip already exists**: `Questions.kt` parses
  `permission_request` (🔐 one ✅/❌ row per option),
  `elicitation_request` (multiple-choice buttons + ✍️ free-text arm) and
  plan approval (ExitPlanMode → ✅/❌); the fork's `respond` frame
  (6993d7e8) answers them. The owner's "everything zcode sends must land
  as actionable buttons" is therefore a VERIFY + close-residuals job, not
  a build-from-zero.
- **Verbosity already matches zcode**: the bot's `summary|normal|verbose`
  is the fork's `messageStreamVerbosity` enum; the bot's default is
  `verbose` (the owner's standing order; zcode defaults to `normal`).
- The channel does NOT yet expose: `setMode` (needed by /mode),
  `createTask` (needed by /run), any gift/promo surface, or the additive
  `bindTaskId` inbound field (designed in fork-interface-map, queued).

## Progress (2026-11-02, updated as units land)

- **B1 ✅** — `QuotaDirect.kt` live (direct z.ai API → Start-Plan buckets
  fallback → honest without key) + the owner's number law v4 (`tokenRaw`:
  raw tokens → K → M, sistema americano; commit 10a80b8).
- **B2 ✅** — /retry merged (c79f393): atomic last-prompt store, re-fire
  through the SAME router (busy → F17 push-or-enqueue), armed order never
  spent by /retry; armed-order one-shot fix (49e88db).
- **B3 ❌ DROPPED** (owner decision): /handshake + /ping leave the batch
  and the phone checklist.
- **B4 ✅** — /usage + /ctx merged (751feb8; cf1bc84 + db5c1ca): the
  fork's `taskUsage` VERDICT = REAL data (agent SQLite `model_usage`
  aggregate); /ctx renders the REAL contextWindow meter per conversation.
  Honest gaps: no today-filter (session-lifetime cumulative only), no
  compacted flag — never faked.
- **B5 ✅** — /run merged (402ec79; dfb556e): fresh-conversation
  guarantee achieved CHANNEL-SIDE (awaited `/new` → botStatus confirms
  the draft → fire with the create intent); no createTask cap needed —
  the A4 fork package stays additive-only.
- **B6's live refutation + fix** (81e6c59, the owner's own /deploy at
  22:02): the announce landed, the build+install ran, and then the
  script's `launchctl bootout` tore down the daemon's WHOLE process tree —
  the nohup'd script included — so the bootstrap never ran and the bot
  stayed silent until a manual bootstrap (22:02:37→22:06:03). A nohup
  child is NOT detached from launchd's job. The script now runs as its
  OWN one-shot job (`com.zcode.bot-deploy`): a sibling that survives its
  target's bootout, with HOME/JAVA_HOME/PATH and both stdio streams
  pinned in a pure, tested plist. A failed bootstrap throws with
  launchctl's words — the phone hears it and the daemon stays up.
- **A4 ✅ MERGED** (`6cd2d579` on the fork's `damian`, agent branch
  `feat/a4-child-sessions` d281b1ae + 26ce110d): the child-session
  convention (`sess_subagent_<agentId>`) is REAL — the v4 failure was a
  missing OWNER runtime record, not a wrong id. New native v4
  `sendSubagentMessage` (child steered/queued, terminal child resumed)
  + `stopGeneration` now resolves the owner and cancels background work;
  capability `subagents` announced additively (the daemon needs NO
  change). Plus the additive `setMode` query
  (`build|edit|plan|yolo|guarded`, unknown → `bad_request`, cap
  `control:setMode`) — /mode's door. **Pending the owner's ⌘Q fork
  deploy.**
- **A5 ✅ verdict PARCIAL** (`639b3ea` merge; doc
  `docs/a5-gifts-promos.md`): the reset pill and the claimable-plan
  banner DO exist in the fork, but both need the ZCode JWT + provider
  OAuth (encrypted in credentials.json) — NOT reachable from the bot's
  channel, and `/quota`'s direct API key cannot substitute. The
  claimable offer carries no expiry field (the "expires in 2 days" copy
  is server-side campaign text). Proposal: an additive
  `codingPlanGifts` capability (no credentials, no mutations) + two
  🎁/🔴 line groups with honest silence when the data is absent.
- **The healer + /deploy's two live failures** (b42382b, 540a66a): the
  beacon watched the Mac, not the bot — `scripts/bot-healer.nu`
  (LaunchAgent, hb-channel freshness > 3 min → kickstart/bootstrap, one
  action per 5 min, hands off during `state/deploying`, one send-only
  Telegram line) closes it, live-tested by booting out the real daemon.
  And the owner's second `/deploy` of the day bounced twice with
  `error 5`: the deploy job was still loaded, bootout had not released
  it, and the bootstrap flew milliseconds later. `DeploySpawn.spawn` now
  retries once after 2 s (lib/deploy.nu's measured fix) and writes its
  receipt line only AFTER a successful bootstrap.
- **The ORDER pulse gate + the sn: detail card** (66d6a7e, the owner's
  live proof 20:34): two orders into the ⏹️ agent's dead session bounced
  nothing — now the ✍️ arm and the fire both ask the tri-state pulse
  (closed lane = death certificate; open lane never vouches — the sa:
  pin freezes the parent's fold; only the store's fresh row speaks life;
  stale row = silence, not proof). Tapping a row's name opens the FULL
  uncapped name + fresh action buttons; a dead agent's card is a pure
  memory (the name survives, the buttons don't).
- **Landed alongside**: ⏹️ stop-subagent on live lanes (2c9a73d), the
  Working-bar faithful mirror + collapsed CoT (📖/🙈 in-place toggle),
  ⏹️ buttons only on fresh rows (honest-indicator law).

## Execution doctrine

Two tracks in parallel, ONE deploy batch at the end (the 4th Telegram
flood penalty was caused by 3 daemon restarts in 3h — never again).
Track A (buttons: fork + bot), Track B (bot-side commands). Python is the
byte-exact spec for behavior and emojis; the "Kotlin must be SUPERIOR"
doctrine wins the improvements (FP-first: pure core + property tests, KAP
for orchestration). Max 2 concurrent subagents; every unit = 1 incremental
commit+push (`--no-verify`); deploys batched.

## Track A — buttons

- **A1. Live drill** (owner's phone): fire a permission request, a
  multi-option AskUserQuestion and an ExitPlanMode; verify each arrives as
  buttons and the tap seals the message. Diagnose from `daemon.jsonl`
  (`route:` lines) if anything does not arrive. Deliverable: verdict per
  kind + fixes.
- **A2. Multi-question elicitation** (real gap: only question 1 of N is
  rendered): per-question navigation (q1 → answer → q2, «pregunta 2 de N»
  counter); `elicitationContent` accumulates `answers` for ALL answered
  questions (today only `answer_0` flies). Python ref:
  `handlers_tasks.py:90-111`. Tests: 2-question flow, partial answers,
  double tap.
- **A3. UI style guide + total sweep** (owner's choice): `ui-style-guide.md`
  built FROM the existing vocabulary (ToolBox.familyOf verbs, F23 acks —
  untouchable), then every `Keyboards.kt` builder and command render swept
  through it in one unification commit. Tests pin the guide (destructive
  never first row, caps respected).
- **A4. Fork package** (one batch → one owner ⌘Q deploy): additive
  `bindTaskId` on `inbound` (free text targets the conversation without
  the bind-by-command workaround; simplifies `Routing.kt`) + additive
  `setMode` query (plan 🗺 / yolo 🔓; Python ref `handlers_system.py:180`)
  + anything A1/A2 uncover.
- **A5. SAVED — gifts & promos** (when tokens return; does not block the
  batch): find where the gift boxes live (token pill "reset 5 hour quota
  now, expires in X" / weekly; config sidebar "free 500.000K token expires
  in 2 days, Select!") — the provider endpoints/data (IUsageStatsService /
  z.ai) — then surface them in an enriched /quota + a 🔴 alert when a
  claimable gift exists.

## Track B — bot-side commands

| # | Unit | Python spec | Kotlin shape |
|---|---|---|---|
| B1 | /quota real data | `handlers_info.py:47` + `reporting.py:211-283` (`https://api.z.ai/api/monitor/usage/quota/limit`, Bearer from `~/.zcode/hooks/bot-provider.json`, 5h+weekly buckets, ⚠️≥75%) | new `QuotaDirect.kt` (pure parser, injected HTTP); `BotCommands.kt:1121` tries direct → falls back to the channel's `quota` query (Start-Plan buckets already render) → honest without key. No leveldb scraping — better than Python |
| B2 | /retry | `handlers_tasks.py:155` + `_save_last_prompt` (`workers.py:937`) | last prompt per sid in `state/last-prompt.json` on every confirmed inbound; /retry = armed order to the bound sid; honest refusals (no context / busy) |
| B3 | /handshake + /ping | `handlers_system.py:238-310` + `:103` | pure `HealthReport`: channel ping RTT (exists), hb-poller/hb-channel ages, outbox depth + cooldown active, running subagents (TasksHistory), live-view state, manual resubscribe button. /ping = 🏓 pong + uptime + channel |
| B4 | /usage + /ctx | `handlers_info.py:51-55` + `reporting.usage_report:283` + `context_report:358` | FIRST verify `ChannelQuery.TaskUsage` returns real data (the `not_configured` was the QUOTA provider, not usage); /usage = TaskUsage of the bound task + today's aggregate (rollout parser via the spectator if needed); /ctx = contextWindow per open conversation as a LIST (the fork's REAL meter — better than Python's estimate) + 🧹 compacted flag |
| B5 | /run `<prompt>` | `handlers_tasks.py:343` | design against `Routing.kt` (naked inbound with no open conversation already creates one); if "new conversation + prompt" cannot be guaranteed channel-side, honest refusal + the gap joins A4 (createTask) |
| B6 | /deploy | `handlers_system.py:38-77` (two-step `dep:go/dep:no`, 600s ceiling, log tail, loud failure) | intercept in `ChannelWiring.kt` (the /file /diff pattern); go = DETACHED spawn of `scripts/deploy-daemon.sh` (anti-suicide door: the script announces BEFORE, the new daemon posts the tail at boot); owner-only, loud failure |

## Final deploy batch (the only one)

1. Fork: the OWNER runs `~/.local/bin/nu-nightly scripts/deploy-fork-and-retire.nu --real`
   from a normal terminal (never ZCode's integrated one) — carries A4.
2. Daemon: the coordinator runs `scripts/deploy-daemon.sh` (standing grant)
   — carries Track B + A2 + A3.
3. Phone checklist: /quota (real buckets) · /retry · /usage ·
   /ctx · /run · /mode · a 2-question elicitation · plan approval ·
   permission ✅/❌ · the unified aesthetics.

## After the batch — the compass order stands (v1.16)

U1a (repo RAG indexer + brain-mcp, parallel, never touches the bot) →
U1b (BusEvent producer + memory_inject port → enables /brain /memory) →
kap 4.3 (TgBreaker → kap CircuitBreaker) → E2 (metrics) → E3 (OTel) →
U4+. Stale docs (README build section, fork-interface-map TOP-5) get
repaired along the way.

# Command fixes — the owner's 4-point order (2026-11-04 evening)

Four orders. All BOT-SIDE, no fork changes, no deploy (the coordinator deploys).

## 1. THE REPLAY BUG (root cause of both «/nuclear twice» and «/deploy many times»)

`Poller.runCycle` saves the offset AFTER dispatch (`saveCursor(next)` post-loop,
Poller.kt). `/nuclear go` and the bot/both deploys KILL THIS DAEMON mid-dispatch
(the ladder's self-kickstart; the deploy script's bootout). The process dies
before `saveCursor` runs → after the restart Telegram RE-DELIVERS the same
update → the nuclear ladder / deploy runs AGAIN. The in-process single-flight
gate (`nuclearGate`, the deploy machine's consumed arm) dies with the process —
it cannot protect across the restart. Measured behavior: nuclear fired twice;
deploy announces chained «muchas veces».

THE FIX — PRE-COMMIT for self-killing updates: in the dispatch loop, BEFORE
`dispatch(cfg, message)`, when the update is one that can kill this process,
commit the cursor through it FIRST (`saveCursor(message.updateId + 1)`):

- text commands: `nuclear`, `nuke`, `stop`, `deploy` (any rest — bare /deploy
  replaying the card twice is harmless, but the family pre-commits whole;
  simple law beats clever law)
- callback taps: `nuc:`, `stop:`, `dep:` prefixes, plus the existing
  `isDestructiveTap` verdict (a tap that spells the words)

Everything else keeps the at-least-once post-commit law unchanged. The
`intr:/odp:/sst:/gfy` interrupts do NOT kill the daemon — they stay
post-commit. Note the distinction: `DESTRUCTIVE_TAP_PREFIXES` (the
anti-suicide door's gate) is a DIFFERENT concern — do not merge the two
vocabularies; add a separate `SUICIDAL` predicate/comment for the offset law.

Test (PollerTest style): a dispatch lambda that THROWS (simulating the process
dying mid-dispatch) — assert the offset file ALREADY contains update_id+1 for a
`/nuclear go` update and a `dep:bot` update, and does NOT for an ordinary
command that throws.

## 2. /deploy ONE-TAP (no re-confirmation, the button row dies)

Current: tap target → arm + confirm card → `dep:go` fires. The owner's law:
«command deploy, elegir que deployar y esa eleccion ya empieza el deploy… una
única vez… no se puede volver a apretar esa botonera».

- `DeployCommand` simplifies: no arm window, no confirm half — the machine
  keeps ONLY the owner gate (`authorize(fromId, ownerUserId)`) and the
  one-shot consumption. WINDOW_MS / Expired / NotArmed / the arm die. (The
  busy-guard against a RUNNING deploy stays where the deploy-phases work put
  it — the spawn edge refuses by name.)
- `DeployFlow`: a target tap/text (`dep:bot|fork|both`, `/deploy bot|fork|both`)
  → owner gate → THE DOOR immediately (announce → receipt check → spawn, the
  fork-first BOTH split untouched).
- THE BUTTON ROW DIES: the flow remembers the targets card's born message id
  (the `say` receipt when the card went out) and, when a target fires, EDITS
  that message to strip the inline keyboard and mark the chosen row
  (e.g. «🤖 Bot Kotlin — ▶️ en vuelo»). The wiring must inject an edit closure
  (the transport's editMessageText path — find the existing `EditRequest`
  machinery; if the send leg lacks an edit, add the thinnest one). The edit is
  best-effort: if it fails, the flow says the deploy is in flight and the
  spawn edge's busy-guard is the backstop.
- IN-FLIGHT refusal: after a successful launch, further target taps/texts say
  «ya hay un deploy en vuelo» until process death (honest, best-effort — the
  script-side guard is the durable one). Taps while IN FLIGHT never re-arm,
  never re-fire.
- Copy: the targets card names each pipeline in one line each (the confirm
  cards' content collapses into it — the tap IS the confirmation now); the
  confirm cards, EXPIRED, NOT_ARMED and ACTION_GO/ACTION_NO retire. Keep
  THE_ANNOUNCE family, SPAWNED family, ANNOUNCE_FAILED, SPAWN_FAILED,
  NOT_OWNER, CANCELLED (cancel dies too — no step to cancel; remove).
- Typed `/deploy go` and `dep:go` retire: unknown word → the targets card.
- Tests: DeployFlowTest + DeployCommandTest rewritten for the one-tap law
  (owner gate, fire-on-tap, keyboard-strip edit called with the card's id,
  in-flight refusal, fork-first BOTH).

## 3. /mode: plan OUT, default Full access

- `ModeMenu.MODES` keeps the five for vocabulary, but the KEYBOARD renders
  four — plan never appears in /mode (normalize keeps accepting `plan` so old
  spellings and /plan_mode's write still work).
- DEFAULT = Full access («por defecto siempre Full access»): a conversation
  whose snapshot has NO `meta.mode` renders the card with «Full access» as the
  mode in force (🎯 on the yolo row, the label said as default — never «—»).
- ENFORCEMENT: on `task_run_started` for a task the wiring watches, read the
  task's snapshot `meta.mode` (the same query the /mode card uses) — absent →
  fire `ChannelQuery.SetMode(taskId, "yolo")` ONCE per task (a per-task
  in-memory guard set; re-checked against the fork's truth after restarts, so
  an owner-chosen `build` is never stomped: it IS in meta.mode). Honest
  caveat (log line + the card's copy): the very first turn of a brand-new
  conversation runs the app's own default; yolo lands from the next turn.
- The mo: tap flow unchanged (minus plan never being offered there).

## 4. /plan_mode — the separate toggle

New command, plan leaves /mode:

- Card: «🧭 Plan mode de «título»» + the state line ON/OFF (current =
  snapshot `meta.mode == "plan"` — READ, never remembered).
- Keyboard: TWO buttons — `✅ ON` / `⬜ OFF` (`pm:on` / `pm:off`), the CURRENT
  one carrying 🎯 (the same marking law as /mode's rows).
- ON → `SetMode(taskId, "plan")`; OFF → `SetMode(taskId, "yolo")` (the
  default law — back to Full access; another mode is one /mode tap away).
  Applied copy mirrors /mode's («aplica al próximo turno»).
- Typed: `/plan_mode` (card), `/plan_mode on|off` (direct toggle), unknown
  word → the card. Register in the command table (+ /help) and the
  DESTRUCTIVE vocabulary stays untouched (a mode switch is not destructive).
- Tests: ModeCommandTest/PlanCommandTest patterns — card renders with the
  current state marked, taps fire the right SetMode, unknown refused.

## House laws

- Work in THIS worktree, branch `agent/cmdfixes`. Commit + push `--no-verify`
  per unit IN ORDER (1 first — it is the safety fix). Gate per unit:
  `./gradlew build` green (tests + detekt 14/60/2/11, inline @Suppress with
  one-line justifications, house style).
- Card strings in Spanish, the bot's current voice. All content through
  `PyText.esc`. Kap/poller laws untouched — no `while(true)` in main sources.
- The poller stays stdlib-pure (no channel imports beyond the existing
  package-local helpers it can already see).
- The fork is READ-ONLY here. No deploy. Final report: per unit — hash, what
  changed, gate result; deviations with reasons; total diffstat.

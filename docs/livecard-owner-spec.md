# Live card v2 — the owner's 6-point order (2026-11-04)

The owner rejected the current card («no puedo entender a golpe de vista qué está
pasando»). Six requirements, all BOT-SIDE — the fork already ships every field
(`task_run_started.startedAt`, `tool_call_update.status:"in_progress"`, streaming
partial `input`). Verified in the fork: `packages/shared/src/zcode-task-types-core.ts:606`
(status union), `:740` (`task_run_started` + `startedAt`), and
`packages/services/src/bots/daemonChannel.ts:1129` (`at: now()` is the DELIVERY
clock — that is why the bot must anchor on the payload's `startedAt`).

## 1. The header time is zcode's, mirrored

Today `startedAtMs` anchors on the first frame's delivery `at`. Fix:

- `StreamEvents` gains `startedAt(event)` (reads `startedAt` from
  `task_run_started`, epoch ms).
- `RenderEvent.Started` carries it; `reduceFrame`'s fresh seed and the `Started`
  branch anchor `startedAtMs` on it when `> 0` (fallback `atMs`, then `nowMs`).
- MIN-GUARD: `startedAtMs = min(existing ?: new, new)` on every `Started` — a
  re-emitted `task_run_started` (stream recovery) must never restart the clock.
  The time counts from when the TASK started, not the turn.
- The header still ticks wall-clock while live and freezes per the frozen-clock
  law (unchanged).

## 2. «procesando…» — the pure-computing state (100% valid)

The Thinking phase splits by whether thought deltas are actually flowing:

- New state field `lastThoughtAtMs: Long?` — updated ONLY by main-agent Thought
  events (subagent lanes don't count). One more `TurnLive.copy` in `reduceThought`.
- Render (Thinking phase):
  - `fresh = lastThoughtAtMs != null && clockNow - lastThoughtAtMs < COT_QUIET_MS`
    (new const `COT_QUIET_MS = 5_000L`):
    - fresh → `🧠 generando CoT · N caracteres · Xs` (X = `clockNow - phase.sinceMs`)
    - not fresh → `🧠 procesando…` + `stalenessWord` suffix only when silence
      ≥ STALE_SOFT_MS (honest degradation unchanged).
  - After a tool closes (phase returns to Thinking) `lastThoughtAtMs` is old →
    «procesando…» immediately. First deltas flip it to «generando CoT». This is
    the owner's state 2 vs state 3 — no CoT text shown in either (the blocks
    live in the feed, unchanged).

## 3. The current action, with its own elapsed seconds

- Fold `in_progress`: `classifyTool`'s `TOOL_UPDATE` ladder gains
  `"in_progress"` → new `RenderEvent.ToolRunning(taskId, seq, atMs, nowMs, toolId)`.
  `reduceToolRunning`: update that lane's `lastUpdateAtMs`; if phase drifted to
  Thinking, return to `Writing(tool, toolId, …)` KEEPING the lane's
  `openedAtMs` anchor (the action's elapsed = `clockNow - openedAtMs`, already
  rendered by `verbLine`). A lane that was never opened (no pending/final
  arrived) opens with `openedAtMs = event.atMs ?: nowMs`.
- `verbLine` rework (the NOW line):
  - Bash/Shell (`SHELL_TOOLS` sense): line = `⚡ Bash · 7s`, then the COMPLETE
    command as `<pre><code class="language-bash">CMD</code></pre>`. Commands
    ≤ 1800 cp ride the card complete. Longer: the card shows the first 1800 cp
    + `✂️ … el comando completo va en el feed`, and the feed speaks the command
    ONCE (at open, `speak`), complete, split across messages only if a single
    one would exceed the Telegram 4096 limit. NEVER elided mid-command.
  - Edit/Write: `✏️ Edit · 7s — <path> +12 −2` — path through the new
    `elidePath`, counts from the live diff (already folding).
  - Other tools: verb + detail as today, but any path-shaped detail goes
    through `elidePath`.

## 4. Progress pings every 5 s — separate messages outside the card

New publisher cadence alongside the 3 s tick:
`Kap { pingTick() }.repeat(Schedule.spaced(5.seconds))` (same idiom as
`asProcess`, started in Main.kt next to it). Pacing state (ping counter, last
action identity, last counts) lives in the PUBLISHER — the fold stays pure.

While the watched turn is live (`phase` is Thinking/Writing, channel alive, no
typedError/apiRetry, not frozen):

- CoT streaming (fresh per §2): every 5 s speak
  `N - generando CoT X` — N = per-action counter (1, 2, 3…), X =
  `fmtKDecimal(thoughtChars)`. Skip the ping if no new chars since the last one
  (silence is data).
- Edit streaming (Writing with an Edit/Write lane carrying live +A/−R): every
  5 s speak `N - editando archivo <basename> +A −R`. Skip if the counts did not
  change.
- Bash / answer streaming: NO pings (the card already carries them; output
  boxes arrive at close).
- The counter resets when the action identity changes (tool id or phase kind),
  on Done, and on task switch. Counter and pacing are publisher state.

On completion:

- A sealed CoT block (COT_BLOCK_CP) already speaks via the feed — make sure its
  message reads `📖 CoT <n> generado Xk` (fmtKDecimal) and keeps its collapsed
  box button (the «muestro botón» of the owner's example). Check
  TranscriptFeed/speakFeed for today's exact shape and align.
- No new completion message for edits/bash — the card's stamp carries the
  verdict.

FLOOD SAFETY (the standing law): pings ride `speak` (firehose lane). If the
cooldown gate would delay the send, DROP the ping — never queue, never burst.
Max one ping per 5 s per turn.

## 5. Bash rendering law — code block, complete, always

- Commands: `<pre><code class="language-bash">…</code></pre>` (Telegram's bash
  highlighting = the closest to «fondo negro, letras verdes»; a Telegram message
  cannot force colors — that is the honest ceiling).
- Output boxes (`ToolOutputPages`): shell-tool pages render inside
  `<pre><code class="language-bash">…</code></pre>`; non-shell pages keep plain
  `<pre>`. Numbered-line law unchanged. Keep `PyText.esc` on all content.
- Stamp line gains the runtime: `⚡ Bash · 12s · 34 líneas` (runtime =
  `lastUpdateAtMs - openedAtMs`, real measured, frozen-clock-safe because both
  anchors are real events).

## 6. The filename law — basename + extension NEVER elided

New pure helper (contracts or channel module, with tests):

```
fun elidePath(path: String, maxCp: Int): String
```

- Fits → unchanged. Too long → elide DIRECTORIES from the left
  (`…/<dir>/…/<basename>` style), the final basename + extension always
  complete byte-for-byte. A path whose basename alone exceeds the budget is
  not the helper's problem (caller handles), but the helper never cuts inside
  the basename.
- Applied at: `verbLine`/`laneLine`/`stampLine` details, `ToolOutputPages.headerOf`,
  and the edit pings (basename only there).

## Number formatting

New `fmtKDecimal(n: Long): String` (next to `fmtK`):
`< 1000` → raw; `< 1_000_000` → K rung with DECIMAL COMMA and trailing zeros
trimmed (`1250 → 1,25k`, `2750 → 2,75k`, `3000 → 3k`); `≥ 1_000_000` → M rung
AT the boundary (`1_000_000 → 1M`, never `1000k` — the number law).
Used by: pings, the CoT row's «en camino», sealed-block «generado Xk». The
card's phase lines keep raw char counts (the owner never complained there).

## Tests (the gate is `./gradlew build`, detekt 14/60/2/11)

- Reducer: `startedAt` anchoring + min-guard; `in_progress` fold (phase
  restoration, lane touch, never-open lane); `lastThoughtAtMs` only main-agent.
- Render: «procesando…» vs «generando CoT» (both sides of COT_QUIET_MS, frozen
  clock included); bash command block complete + the >1800 cp feed path; edit
  line with elidePath (long dir chain, basename intact, extension intact);
  stamp with runtime.
- `fmtKDecimal` table with REAL magnitudes (999, 1000, 1250, 2750, 3000, 999999,
  1_000_000).
- `elidePath` table.
- Publisher: ping emits on the 5 s cadence, skips when counts unchanged, skips
  under cooldown pressure, counter resets on action change (MockEngine pattern,
  LivePublisherTest style).
- Every changed string asserted byte-for-byte in the existing test style
  (`LiveTurnTest`'s local `ev()` helper).

## House laws

- Work in THIS worktree, branch `agent/livecard`. Commit + push `--no-verify`
  per logical unit (1: anchoring+in_progress fold, 2: render rework, 3: pings,
  4: pager/filename/format, 5: test sweep if separate). Never accumulate.
- The fold and render stay PURE (no clock, no I/O); effects live in the
  publisher. Kap schedules only — no `while(true)` (CI bans it).
- Detekt suppressions inline with a one-line justification each (house style).
- Card strings in Spanish, matching the current voice («pensando», «listos»).
- NO fork changes, NO deploy (the coordinator deploys after review).
- The card is delete+re-birth on a 3 s cadence under a shared cooldown: new
  render content changes `lastText` and re-birth frequency — keep the card lean;
  the feed carries the volume.

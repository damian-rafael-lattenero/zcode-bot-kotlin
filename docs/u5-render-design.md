# U5 — Render core (B4): design before the ~4.9k lines

> Status: design (2026-11-03). The plan calls U5 the roadmap's biggest
> risk (`docs/plan-brujula.md` — «render core (~4.9k LOC: cajitas, línea
> parpadeante, agent live) sobre KapStream»). This document exists so the
> risk is spent on design, not on a big-bang. English per the plan
> convention; the owner's visual laws are quoted verbatim where they bind.

## What exists today (the floor, not the target)

- `LiveTurn.kt` already carries the typed heart: `TurnPhase` (Thinking /
  Writing / Done / ToolLive), `TypedErrorPill`, `InteractionPending`,
  `ToolVerb` (the spectator's verb map), the CoT fold («cot 4K» at 3k
  chars) and the staleness watchdog («(30s sin eventos)», «stream
  cortado?»). The honest-indicators doctrine is already law here.
- The imperfection: the fold is imperative (vars mutated by frame
  callbacks in the publisher/wiring), the subagent lanes and history
  views hang off the same mutable state, and Python's full render
  surface (4.9k LOC: tool boxes with +N/−M, page navigation, agent live)
  is NOT reached yet.

## Design: one spine, one fold, pure renderers

1. **One event spine (KapStream).** The channel's stream is the raw
   source: `KapStream.from(frames)` classified by a pure `mapKap` into a
   sealed `RenderEvent` ADT (`Frame*`, `ToolOpen/ToolClose`, `Seal`,
   `Interaction`, `Tick`, `Park`). KapStream's dogfooding contract
   (SPEC-5.0) is finally consumed by a real surface: the live view IS
   the stream.

2. **State is a pure fold.** `RenderState = events.scan(RenderState.empty, ::reduce)`.
   The reducer is pure and golden-tested (the spectator's 21 golden
   vectors law — every render law the owner set: file line numbers
   ALWAYS, diffs +N/−M with 🟢/🔴, bash output behind one numbered
   button, emojis from Python's vocabulary, never invented). No `var`
   survives outside the fold; the staleness watchdog becomes a `Tick`
   event the fold reduces like any other (the card's age is state, not
   a side channel).

3. **Backpressure is named per stage — and it encodes honesty.**
   CoT char chunks → `conflate()` (only the latest matters; the fold's
   counter says so); tool input lines → exact (a line counter that
   drops is a lie); the staleness ticker → `dropOldest(1)`. KapStream's
   tested drop law (what may drop, what never) becomes the render's
   honesty law, not a comment.

4. **Renderers are pure functions of state.** `TelegramCard.render(state)`
   and `TelegramCard.keyboard(state)` produce the message + inline
   keyboard; golden tests pin the bytes. The bg: button (owner-approved
   3/11) is a `Park` event: the card freezes with «▶️ sigue en
   background», the fold keeps running, the publisher stops editing,
   and the seal speaks ONCE (🔔 notification with the conclusion).

5. **Agent-live is the same spine.** Subagent lanes are child
   streams folded by the same reducer (the fork's `parentToolUseId`
   already tags them); the lanes' own cards are `RenderState`s scoped
   by agent id. No second pipeline.

## Phasing (each phase lands byte-identical visuals)

| Phase | Content | Proof |
|---|---|---|
| U5a | Event spine + pure fold REPLACING the imperative fold behind the SAME renders | golden vectors: old fold out, new fold in, zero visual diff; metrics: `bot_turns_*` unchanged |
| U5b | Tool boxes with +N/−M and page navigation (the bash-pager law everywhere) | golden vectors for open/closed boxes; the pager's numbered pages |
| U5c | Agent-live: subagent lanes on the same spine + the bg: button (`Park`) | lane goldens; the seal notification fires exactly once per parked turn |
| U5d | E4: Grafana dashboards (agent-brain compose) reading `/metrics` + chaos drills T7 | dashboards derive from the fold's real events; drills cut the stream and assert honest degradation |

## Non-goals

- No visual redesign inside U5 — the owner's aesthetics are LOCKED
  (the system-design spec of 30/10). U5 changes the ENGINE, not the look.
- No fork changes: everything here is daemon/channel-side.

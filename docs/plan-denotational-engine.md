# Plan — The Denotational Engine

> Created: 2026-11-26 · Status: **v2.0 — approved by the owner on 2026-11-26 with 24 recorded answers.**
> Execution starts at U1, one unit per session; the book is written inside the units, never as a separate marathon.
> Supersedes v1.2 (same day; git history keeps it). The book v5 is written by building the engine: every piece is also a card.

## 1. Context

The owner's goal, in his words: the brain learned Nushell to the point of almost never writing a broken
`.nu`; he wants the same for Haskell, Kotlin and anything functional and denotational. The brain must be
present whenever an LLM writes code — as **hook** (a gate that stops what breaks a law), **guide** (the
right note injected at the moment of writing), **judge** (the *course judge*, watching the turn while it
runs) and **lighthouse** (the curated Canon ranks first, every time) — and every miss becomes a rule.

**One sentence:** when an agent writes Haskell, Kotlin or Nushell, the brain is there at every moment, so
the code is correct and denotational on the first try — *learned* = **5 % or less first-try oracle
failure**, measured per language — and every miss becomes a rule.

Done since v1.2: P0 (baseline `brain-baseline.nu`, the `denials.tsv` ledger), P1 (the Canon: 25 notes +
79 QuickCheck laws; the Theory: 20 notes; both indexed), and the English-corpus campaign (22 notes
translated with a mechanical receipt, the store door + the English-input boundary + the live
`canon-recall` suite, all on branch `agent/rag-en` — merged and deployed in U1).

Typed input and output apply to four things (owner decision 26/11): the code the agent writes, the
agent's replies to the owner, the engine's own pieces (Request, verdict, correction, cards), and the book.

## 2. Vocabulary — form first

| Piece | Denotes (one sentence) | Lives in |
|---|---|---|
| `Canon` · `Theory` | The idea in Haskell; the categorical shape above it | `haskell-kb`, `theory-kb` (exist) |
| `Mapping` | For one concept and one language: the equivalent, an example that compiles, the difference, the trap — or "gap" | `kotlin-kb/notes/canon-map.md`, `nushell-kb/notes/canon-map.md` (new) |
| `Rung` | One concept learned end to end (map row → gate → drill → eval case → card) | the ladder loop, §6 |
| `Gate` | A deterministic predicate from an action to a verdict: `Allow · Guide · Ask · Deny` | `~/Scripts/scripts/hooks/` |
| `Oracle` | The compiler or test runner of a language — the only ground truth for "it works" | `PostToolUse` hooks (new) |
| `Request` | The typed witness of what was asked: `goal · constraints · nonGoals · acceptance` | fork runtime |
| `Verdict` | `aligned · minor_drift · drift`, with cited evidence | fork runtime |
| `Seal` | Delivered versus Request, plus the reply's shape | fork runtime |
| `Reply` | The agent's answer to the owner: `result · evidence · next` | law text + seal |
| `Correction` → `Proposal` | What the agent did, what the owner did instead, which concept it breaks → a candidate rule the owner approves | bus kind + table in the brain |
| `Eval` | One number over time, in the ledger, with baseline and target | `agent-brain/evals/suites/` |
| `Card` · `PaperSheet` | One book unit; what the owner writes by hand: `signature · law · drawing · own_words` | `agent-brain/docs/book/` |
| `LawText` | The denotational law every session carries in its system prompt | `agent-brain/docs/denotational-law.md` (new) |

Engine laws (kept from v1.2): a Gate that denies is deterministic; an LLM may only Ask, at most once per
turn; nothing enters a KB or a hook without the owner's approval; a quotation without verification does
not enter.

## 3. Diagnosis — what the measurements corrected in v1.2

| v1.2 said | Measured reality (26/11) |
|---|---|
| Hooks never fire in subagents — "a fork/config question to resolve in P3" | The child runtime is built **without hook wiring** (`subagent.ts` omits the deps); every entry returns an empty result. It is an omission, not a design: a small fork fix. 25,341 of 80,278 steps (31 %) ran ungated. |
| The Canon "answers Monad queries" | Only for the phrasings tried: `canon-recall` (90 cases, 45 concepts × EN/ES) put the note **first for 34/45** English and 23/45 Spanish with the English-input boundary. U1 re-measures on the clean, deployed store. |
| Kotlin lacks a lint | detekt runs at build/CI, and `kap-detekt-rules:5.5.0` is wired in three consumers — but its 3 rules (ManualWaitLoop, CancellationArmBeforeCatchAll, VolatileReadModifyWrite) are concurrency rules. **No mechanical denotational-style rule exists for Kotlin.** |
| No PostToolUse hook exists in the fleet | Correct, and the fork supports PostToolUse but it **cannot block** — it only adds context after the tool ran. Right home for oracles; wrong home for gates. |
| "haskell-flows-mcp" | Lives at `~/Projects/haskell-rules-and-mcp`: 12 tools over one embedded ghcide (`ghc_check`, `ghc_property`, `ghc_gate`, …). Reused in U9, not rebuilt. |
| Theory: 25 of 60 language rows empty; kotlin-kb 6 notes; prompt-laws Nushell-only | 18 of 60 empty (Kotlin 8, TypeScript 6, Nushell 4). kotlin-kb: 5 notes + README, no official mirror. `prompt-laws.nu` is mostly language-neutral — but it carries no Kotlin, Canon or Theory text, and `rag-guard`'s `is-law` still excludes `kotlin-kb/notes`, `haskell-kb` and `theory-kb`. |

The Nushell result is a recipe, not magic — seven ingredients (mirror · curated notes · denying hook ·
lint · compiler oracle · shared library · test census); only Nushell has all seven. Baseline (P0):
oracle failure nu 20.8 % → 4.8 % (the learning signal), kt 16–26 % flat, hs dormant since W41; law
denials flat at 30–37 per 1,000 writes — the gate teaching at write time is part of the recipe; what
must fall is the first-try failure.

## 4. The four roles at five moments (the picture the book repeats)

| Role | When | Mechanism | Receipt |
|---|---|---|---|
| Lighthouse | on every query | Canon (curated) ranks above manual fragments; nothing non-English in the store | a concept question, EN and ES, returns the Canon note at rank 1 (`canon-recall`) |
| Guide | at design time; when a file is born | `LawText` in the system prompt + `design-guard` querying the Canon live | a real turn where the injected hit changed the design |
| Hook | at the write, and right after | a deterministic gate, then the oracle (compiler/tests) | a `denials.tsv` line; an oracle-failure ledger line — also from subagent sessions (U4) |
| Judge | along the turn | the Request, measured triggers, one verdict with cited evidence | a real turn where the judge spoke and the turn was saved |
| Memory | after the turn | correction → proposal → owner approval → promotion | a correction that became a rule |

## 5. The fleet — one row per piece

| Piece | Today | Target | Change | Unit | Receipt |
|---|---|---|---|---|---|
| RAG store | English-only by the door (unmerged); Canon first 34/45 EN · 23/45 ES | Canon rank 1 both languages (≥ 43/45) | merge + deploy; curated-source priority in `RagStore.kt` if the number demands it | U1 | `canon-recall` ledger row; the Spanish mónada question returns `monad.md` first |
| Memories | 13 Spanish + 42 mixed rows | English-only | `translate-legacy` + review; inventory must show zero | U2 | `rag-language-inventory --short` clean |
| Evals | recall-core 33 · real-questions 8 (non-deterministic) · `canon-recall` live 90 (branch) | canon-recall in the nightly; `first-pass` / `canon` / `course` suites | suites + nightly step | U1, U22 | ledger rows; `/learn` shows the numbers |
| Hooks | 14, deny only for `.nu`; nothing compiles after a write; silent in subagents | Kotlin + Haskell gates; PostToolUse oracles; fire everywhere | `is-law` opens to `*-kb/notes`; new gates; oracle hooks; fork wiring | U4, U9, U11+ | `denials.tsv` rows incl. subagent sessions; an oracle catch |
| Prompt layer | `prompt-laws.nu` static, no Kotlin/Canon; `design-guard` static ladder | the law in the fork's system prompt; live Canon retrieval | `systemPromptAppendFile`; `design-guard` queries the brain | U4, U9 | the law visible in a subagent's context |
| MCP | 4 read-only tools | unchanged | — | — | — |
| Bot | `/learn` = memories + evals + costs; steer/stop publish nothing | `/learn` shows the three numbers; corrections published | `correction` bus kind on steer/stop/cancel | U8, U22 | a correction row from a real steer |
| Skills | `fp-denotational-design`, Spanish body | English law text, the `LawText` | body replaced (authorized) | U4 | the skill loads in a fresh session |
| KBs | 5 repos; 15 of 25 concepts without an executable law; no canon-map | maps for Kotlin + Nushell; 15 new QuickCheck laws; `kotlin-kb/official` | filled rung by rung | U9–U21 | `cabal test` green per new law; map rows verified |

## 6. The concept ladder — one concept per rung

1. Its row in both maps (Kotlin + kap; Nushell + nushell-fp), with an example that compiles, the
   difference and the trap — or "gap".
2. A gate rule when the law is mechanical: denies from day 1 when the pattern is unambiguous (owner
   decision 26/11); what needs the tree goes to the oracle. Eight-case selftest + census test.
3. Its drill in `canonical-drills` (new repo: Kotlin, Nushell, Haskell; the type and the test exist, the
   body is the exercise) and its QuickCheck law if missing (15 of 25 lack one).
4. Its eval case per language.
5. Its card B.

**Exit criterion:** the rung is learned when its eval case passes first-try, N of N (N = 3), in fresh
sessions. **Order:** writing follows the measured violation (U10 judges a sample of real edits against
the Canon and names the five most violated); reading follows the book's prerequisite order.

## 7. The units — U1…U25 (one per session)

| # | Unit | Leaves done | Live receipt | Cards |
|---|---|---|---|---|
| U1 | Lighthouse | merge `agent/rag-en` (deploy authorized 26/11); measure; curated-first in `RagStore.kt` if needed | `canon-recall` row; "qué es una mónada y para qué sirve" → `monad.md` first | C5, C8, E1 |
| U2 | The English check | memories to English; `rag-english-check` + `translation-check` promoted to `~/Scripts` with tests; nightly runs them; `~/Scripts/tests/` out of the index; ADR "The corpus is English"; AGENTS.md edits (`[verified <version>]`, the KB list) | the check green on the live store, red on a planted Spanish note | D1, C7 |
| U3 | The book frame | English route sheet (`00-route-sheet.md`): 90 cards, prerequisite order; `book-check` with tests; first 8 cards | `book-check` green; A1 readable | A0.1–A0.4, A1–A3, C6 |
| U4 | The fork carries the law | system-prompt append section reading a file (generic `systemPromptAppendFile`); replaces the skill's Spanish body, retires `prompt-laws.nu`; hooks wired into subagents (all of them, owner decision), with a live survey of what fires | (after the owner's build) a `denials.tsv` deny from a subagent session; the law in a subagent's context | C1, D2, D3, D4, D9 |
| U5 | The Request | before the first step: drafted by model + schema → confirm/correct card → waits for the tap, 2-minute expiry → injected and stored; conversational prompts get no card | a real turn (desktop and phone) where the agent waited | D7 |
| U6 | Triggers + verdict | the measured triggers off the fork's own event flow; one model call; minor drift = a note to the agent; big or repeated = one question to the owner; fails open, counted | a real turn where the judge spoke to the agent | D5 |
| U7 | The seal | delivered vs Request + the `result · evidence · next` shape; one bounce, then the owner is told | a real turn the seal bounced | D10 |
| U8 | Corrections | `correction` bus kind; the bot publishes on steer/stop/cancel; `corrections` table + extractor beside `MemoryExtractor`; Corpus Doctor gains `law_gap` (proposes, never writes) | a correction that became a rule | C2, C3, C4, D8 |
| U9 | Oracles + guide | PostToolUse for `.kt` (compile + detekt with the kap rules) and `.hs` (`ghc_check` reused), advisory, time-capped, with its ledger; `rag-guard` injects every `*-kb/notes`; `design-guard` queries the Canon live and covers `.hs` | a compile failure caught right after a write | D6 |
| U10 | Kotlin baseline | `kotlin-kb/official`; a sample of real edits judged against the Canon → the 5 most violated; the `var`/`!!` survey by package | the list of 5, with numbers | E3 |
| U11–U15 | Rungs 1–5 | the most violated, one per unit | per rung: its eval case passes first-try; a Kotlin deny in the ledger | B ×5 |
| U16–U19 | Rungs 6–25 | five per unit | idem | B ×20 |
| U20–U21 | Part N | one card per nushell-fp module (15) | — | N ×15 |
| U22 | The numbers | `first-pass`, `canon`, `course` as evals with suites; `/learn` shows them | the three rows in the ledger and `/learn` | E2 |
| U23–U24 | Part H | the 20 short theory cards, Kotlin and Nushell rows verified | — | H ×20 |
| U25 | The method | laws with receipts, traps of the month, exam with answer key and question→card map; answers close | — | F1, F2 |

Owner decisions needed, at most one per unit: U4 — approve the child-session rules of the hooks that
speak too much; U10 — how a pure core is declared (from the survey) and the canon-adherence target.

## 8. The book v5

English, inside the brain's index, read as markdown on the Mac (owner decisions 26/11). v4 stays as the
historical record — it is Spanish, so the door already keeps it out of the index.

**`book-check`** (the didactic spec as a gate, molded on `kb-check.nu`): the eight stops · ≤ 150 lines ·
no paragraph over 100 words · no term used before its home card · every cited path exists · English ·
no clinical language. Two sizes per card: the full path (10–20 minutes) and the minimum path (~5:
the aha, the signature, the checkpoint).

**The eight stops:** The aha · What it denotes · In your languages (the difference and the trap) · Where
it lives in your system · The paper sheet · The drill · Retrieval questions (answers live in part G) ·
The close + checkpoint.

**Concept cards (B, H) are short:** what it denotes, the "in your languages" table with Kotlin and
Nushell, where it lives in the owner's repos, the drill, the receipt that the brain learned it. The
mathematics is not re-taught — the reading path is simplified, never the math.

**Paper:** four slots (`signature · law · drawing · own_words`), three retrieval questions, and a paper
index page carrying the +1-day and +7-day re-asks (paper only; no Telegram cards).

**Parts:** A 7 · B 25 · C 8 · D 10 · E 3 · F 2 · G answers · N 15 (one per nushell-fp module) ·
H 20 — 90 cards; the route sheet maps every card to its unit.

**Citations enter only verbatim-verified.** Verified 26/11: Minear et al., *"Is practice good enough?
Retrieval benefits students with ADHD but does not compensate for poor encoding in unmedicated
students"* (Frontiers in Psychology 14, 2023, doi 10.3389/fpsyg.2023.1186566); the worked-example
effect (NSW Department of Education, *Cognitive load theory: Research that teachers really need to
understand*, 2017: "novice learners who are given worked examples to study perform better on subsequent
tests"); MemCoder, *"Your Code Agent Can Grow Alongside You with Structured Memory"* (arXiv:2603.13258,
2026 — SOTA on SWE-bench Verified, "+9.4% improvement" over DeepSeek-V3.2 per the abstract; the older
76.4/73.0/68.4 triple was not verifiable and is dropped). The remaining v1.2 quotes are verified in U1
or removed.

## 9. The judge — design constraints

- Lives in the fork runtime (owner decision 26/11), modeled on the `/goal` completion verifier
  (`target-contract` / `target-verifier`): a second model request over a typed pack.
- Reuses the fork's existing surfaces: the question card (desktop and phone) for the one question, the
  Stop-hook continuation for the seal (capped, message injected).
- Runs in the main session; in subagents act the system-prompt law, the gates and the oracles.
- Never traps a turn: model failure or timeout continues the turn, and the failure is counted in
  `course`.
- Fork work goes live only after the owner's build; U5–U7 may be executed as one session.

## 10. The numbers

| Number | Today | Target | When |
|---|---|---|---|
| Canon first (`canon-recall`, 90) | EN 34/45 · ES 23/45 (boundary measured on the pre-cleanup store) | ≥ 43/45 both | U1 |
| Tool steps under gates | 69 % (main sessions only) | 100 % | U4, after the owner's build |
| First-try failure — Nushell | 4.8 % | ≤ 5 % sustained | weekly |
| First-try failure — Kotlin | 16–26 % (re-baselined by the U9 oracle) | ≤ 5 % | end of week 6 |
| First-try failure — Haskell | unmeasured | baseline U11; ≤ 5 % | two weeks later |
| Law denials / 1,000 writes — Kotlin | 0 (no hook) | Nushell's order (30–37) | end of week 6 |
| Canon adherence (20 edits/language/week) | unmeasured | baseline + target in U10, with the owner | U10 |
| Cancels / 100 turns | 12.4 | falling | U22 |
| Wasted-step share of cancelled turns | 74.3 % | falling | U22 |

Cost: the judge ≈ $5–7/month, the canon eval $1–2, everything else $0 (v1.2 estimates, unchanged).

## 11. Risks

- **Upstream merge cost** — fork changes small, generic, marked `[fork]`, with tests (measured 23/11:
  a v4 merge took 29 conflict resolutions; do not grow that surface).
- **All hooks in subagents** — turn-level hooks will fire per subagent; U4 ships a live survey of what
  each hook did and the child-session rules are approved with the owner.
- **Kotlin gate false positives** — gates deny only unambiguous patterns; every rule carries its
  eight-case selftest; overriding a deny becomes a correction (U8).
- **Approval fatigue** — one tap per turn (the Request, with expiry), at most one judge question, seal
  tells the owner only when delivery was incomplete.
- **Scope** — one active unit; the book is written inside the units.
- **The other front** — the repo-migration plan is a separate front: this plan does not rewrite git
  history and the two touch none of the same files.

## 12. Repos, reuse, verification, order

**Repos touched:** `agent-brain`, `zcode-bot-kotlin`, `ZCode` (branch `damian`, worktree), `~/Scripts`,
`kotlin-kb`, `nushell-kb`, `haskell-kb`, `theory-kb`, `fp-kb`, the new `canonical-drills`, `AGENTS.md`
(edits authorized, shown in diffs), the skill `fp-denotational-design`. Read-only: `kap`,
`haskell-rules-and-mcp`.

**Key files:** fork `core/src/runtime/methods/subagent.ts` (wire the hook runner),
`core/src/context/builder.ts` (the append section), `methods/turn.ts` + `turn-stop.ts` (Request, seal),
`packages/contracts/src/tools/target.ts` (the verifier shape, reused). Brain: `rag/RagStore.kt`,
`memory/LegacyTranslation.kt`, `bus/BusEvent.kt`, `memory/MemoryExtractor.kt`, `eval/CorpusDoctor.kt`,
`eval/Suite.kt`, `scripts/rag-nightly.nu`, `docs/adr/`. Bot: `channel/BotCommands.kt`,
`channel/BrainGateway.kt`, `daemon/bus/BusProducer.kt`. Hooks: `rag-guard.nu` (`is-law`, L16–23),
`design-guard.nu`, `english-law.nu`, the new Kotlin gate and oracles.

**Reuse, do not rewrite:** `kb-check.nu` (the mold for `book-check`), `English.kt` + `translate-legacy`,
`denials.tsv` with `tests/scripts/denial-ledger.nu`, `lib/brain-baseline.nu`, the 3 kap-detekt rules,
`ghc_check` / `ghc_property` / `ghc_gate`, the Canon `_TEMPLATE.md`.

**Verification:** every unit — tests green in each repo touched, its receipt row, `fleet brain-eval`
after every corpus change. Fork — its test suite before every push; live receipts after the owner's
build. Book — `book-check` green and the card→unit matrix complete (90/90). The plan is done when every
row of §10 has its measured baseline and its first ledger row.

**Order:** U1 → U2 → U3 → U4 → U5 → U6 → U7 → U8 → U9 → U10 → rungs → N → U22 → H → U25, four or more
sessions per week (owner decision 26/11). **First session = U1:** merge `agent/rag-en` into
agent-brain master (`--no-ff`), deploy (authorized), re-index if the merge changes the corpus, run
`canon-recall` — if the Canon does not rank first, implement curated-source priority, re-measure, commit
the before/after — close with its cards and the single next step. Open leftover: whether
`ECOSISTEMA.md` lists the KBs (decided with the owner when U2 touches docs).

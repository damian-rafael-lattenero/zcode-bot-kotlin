# Plan — The Denotational Engine

> Created: 2026-11-26 · Status: v1.1 (in execution since the owner's order of 2026-11-26: P0 done, P1 running)
> Sits next to [plan-brujula-v2.md](plan-brujula-v2.md): that plan built the five pillars; this one makes the
> whole fleet teach and enforce one style of programming — the owner's.

## 1. Context

The owner's stated goal (2026-11-26): his RAG and brain learned Nushell to the point of almost never
writing a broken `.nu`; he wants the same for Kotlin, TypeScript and any other language he uses. Haskell
is **the source of the concepts, not a target**: the brain learns each concept from the manuals (the
`Maybe` monad with `Just`/`Nothing`) and then says, per language, "here is the equivalent, and here is
the detail that differs".

The measured reason this needs a plan rather than another KB: the Nushell result came from a recipe
with seven ingredients, and only Nushell has them. In parallel, the replay of the cancelled turns
showed that drift is semantic — no structural rule sees it — so correcting the agent while it works
needs a judge, not more lint.

The plan has one vehicle: a study book (v5) written as short fichas, where every piece of the engine
is also something the owner learns by building.

## 2. Vocabulary (the pieces — form first)

| Piece | Denotes (one sentence) | Lives in |
|---|---|---|
| `Concept` | One idea of the Haskell canon: what it denotes, its type, its laws, its sources | `haskell-kb/notes/concepts/` |
| `Theory` | One categorical concept above the Canon: its shape, where it comes down to Haskell, what other languages make of it, where a cited source finds it in architecture | `theory-kb/notes/category-theory/` |
| `Mapping` | For one concept and one language: the equivalent construct, an example, the gap, the trap | `*-kb/notes/canon-map.md` |
| `Law` | A rule the owner stated | `fp-kb/notes`, the KBs, `AGENTS.md` |
| `Gate` | A pure predicate from an action to a verdict: `Allow · Guide(text) · Ask(card) · Deny(reason)` | `~/Scripts/scripts/hooks/*.nu` |
| `Oracle` | The compiler or test runner of a language — the only ground truth for "it works" | PostToolUse hooks (new) |
| `Drill` | One exercise with an automated check | `canonical-drills` repo |
| `Ficha` | One book unit: concept + mappings + paper sheet + drill + retrieval questions + checkpoint | `agent-brain/docs/book/` |
| `Correction` | What the agent did, what the owner did instead, and the concept or law it breaks | brain `corrections` table (new) |
| `Proposal` | A correction turned into a candidate hook, law note, skill or drill — owner approves | Corpus Doctor |
| `Eval` | One number over time, in the ledger | `agent-brain/evals/suites/*.json` |

Laws of the engine:
- A `Gate` that denies is deterministic. An LLM may only `Ask`, never `Deny`, and at most once per turn.
- Every `Mapping` is verified by an `Oracle`; a concept with no equivalent says "gap", never a fake one.
- Nothing is written into a KB or a hook without the owner's approval (the Corpus Doctor rule).

## 3. Diagnosis (measured 2026-11-26)

### 3.1 The recipe, ingredient by ingredient

| Ingredient | Nushell | Kotlin | TypeScript | Haskell |
|---|---|---|---|---|
| Official docs mirror | `nushell-kb/official` (1,473 pages) | none | none | none |
| Curated notes | 33 files | 6 files, 391 lines | 1 note | none |
| Write-time hook that denies | 3 (`nushell-typing`, `anti-pattern-lint`, `nushell-law`) | none (advisory only) | none | none |
| Lint | `anti-pattern-lint.nu` | detekt at build/CI only | none | none |
| Compiler oracle after a write | none | none | none | none |
| Shared library | `nushell-fp` (15 modules) | `kap` | none | none |
| Test census | `~/Scripts/tests/census.nu` | none | none | none |

Proof points: hooks are registered in `~/.zcode/cli/config.json` (`hooks.events`), canonical copies in
`~/Scripts/scripts/hooks/`; the live corpus list is `~/.zcode/agent-brain/corpus.txt`; `kap-detekt-rules:5.5.0`
is wired in `zcode-bot-kotlin`, `agent-brain` and `whatsdigest-kt` build files with three rules.

### 3.2 The Nushell learning curve (law denials; first pass revised 26/11)

| Week | `.nu` writes | denials | per 1,000 |
|---|---|---|---|
| Nov 2–8 | 520 | 18 | 34.5 |
| Nov 9–15 | 1,139 | 40 | 35.0 |
| Nov 16–22 | 1,008 | 30 | 30.1 |
| Nov 23–26 | 516 | 19 | 36.8 |

The first reconstruction (11/22/31/1) undercounted: it missed Bash-tool denials and the newer
`PreToolUse:… hook error: [command hidden]` wrapper. The real rate is FLAT at ~30–37 per 1,000
writes — the hooks catch real mistakes continuously; there was no collapse. `denials.tsv` is
authoritative from 26/11 (the live-fire drill logged its first line the same day). 60 stored denials
of the retired `nu -c` wrapper rule are counted apart; weeks before W44 show zero because no denying
hook existed yet. 25,341 of the window's 80,278 tool steps came from subagent sessions and carry
**zero** hook denials — the mechanical layer does not fire in subagent sessions at all (a
fork/config-level question to resolve in P3).

### 3.3 Concrete defects found

1. `rag-guard.nu` (`is-law`, l.16–24) excludes `kotlin-kb/notes`: Kotlin notes are indexed but never injected.
2. `prompt-laws.nu` is Nushell-only: no "consult kotlin-kb and kap first" law rides a Kotlin prompt.
3. No PostToolUse hook exists, so nothing ever compiles a file the agent just wrote.
4. The corpus indexes no `.kt`, `.ts` or `.hs` source and no TypeScript documentation.
5. No hook wrote a denial record — FIXED 26/11: `denials.tsv` is live (P0).

Baseline oracle-failure ratios from the first run (W45 → W48): nu 20.8% → 4.8% (a real learning
signal), kt ≈ 16–26% (flat — no mechanical layer), ts 13.1% → 0% (small numbers), hs dormant since W41.

### 3.4 Correcting the agent while it works

From the replay of 619 turns (77 cancelled, 12.4%): successful and cancelled turns are structural
twins (4.00 vs 4.03 re-edits per file, 51.7 vs 59.6 steps); 23 long turns still running hold 74.3% of
the wasted steps; the best deterministic signals reach lift 2.46 with 14% recall. They are triggers
for a judge, never verdicts. ZCode's `/goal` is a session-local persistence loop with a post-turn
completion verifier — it never inspects an action before it happens.

## 4. Target architecture — five layers

1. **Knowledge** — the Theory (the categorical shapes, sourced from books and papers) above the Canon
   (Haskell concepts, sourced from the manuals) + the Maps (one per language) + the official docs
   mirror per language.
2. **Application** — gates at write time + the oracle after the write.
3. **Rumbo** — the typed witness of the request + a semantic judge fired by the measured triggers.
4. **Learning** — corrections captured as data, proposed as rules, promoted through the owner's ladder.
5. **Measurement** — three new numbers in the ledger, shown by `/learn`.

## 5. Phases

Each phase is one or more sessions; each session is one logical unit: commit, push, and the ficha it
teaches. Hooks follow the census law (every `.nu` has its test, `~/Scripts/tests/`).

### P0 — Baseline and scaffolding (1 session) — DONE 26/11

- [x] The owner's cognitive profile in permanent memory + one line in `AGENTS.md`.
- [x] Denial ledger: the three denying hooks append `ts, session, hook, rule, language, target` to
      `~/.zcode/hooks/state/denials.tsv` — merged `0fe94d2`, the live-fire drill logged its first line.
- [x] Baseline `scripts/brain-baseline.nu` + `lib/brain-baseline.nu` (pure core), tests, gate green;
      first real run exit 0 in 65 s. Oracle definition measured: a failing run is `status=completed`
      with the failure inside `state.output` (no exit codes are stored; `status=error` is the harness).
- [x] Book folder and the hoja de ruta (`agent-brain` `cb632ca`).
- [x] Found and fixed on the way: the typing hook's type-alias warn crashed on two prose parens
      inside the interpolated message — exactly on the files the law targets (`6f58ad8`).

### P1 — The Canon: Haskell as the source (3 sessions)

- [x] DONE 26/11 — repo `haskell-kb`, same shape as `nushell-kb`: `official/` (mirror) + `notes/concepts/<id>.md`.
      Sources, all free to mirror: *Learn You a Haskell* (CC BY-NC-SA 3.0), *Real World Haskell*
      (CC BY-SA), the *Typeclassopedia* (HaskellWiki, permissive), *What I Wish I Knew When Learning
      Haskell* (source on GitHub). Paid books are cited, never copied.
- [x] DONE 26/11 — one note per concept, English, with frontmatter: `id`, the one-sentence denotation, the Haskell
      type and laws, the problem it solves, the source chapter. First 12 (daily use in his repos):
      purity and referential transparency · immutable data · first-class functions · currying and
      partial application · composition · sum and product types · `Maybe` and `Either` · pattern
      matching and exhaustiveness · recursion and folds · type classes · `Functor`/`Applicative`/`Monad` ·
      `Semigroup`/`Monoid`. Tier 2: `Foldable`/`Traversable`, laziness, `newtype`, higher-kinded types,
      parametricity, `IO` and effects at the edge, total functions, property laws. Tier 3 (applied):
      free monad / tagless final (`Kap`), streaming and backpressure (`KapStream`).
- [x] DONE 26/11 — the Canon made executable: `laws/` holds 79 QuickCheck laws in ten modules and `cabal test`
      is the oracle (79 of 79 on master; a law turned upside down goes red with a counterexample).
- [x] DONE 26/11 — `kb-check` (`~/Scripts`), the mechanical check of the maintenance law: the template's shape,
      every cited path on disk, every quotation on a block that names a mirror, the INDEX as contract. The
      Canon reports 25 notes, 0 findings.
- [ ] `~/.zcode/agent-brain/corpus.txt` indexes it, one source at a time with a recall run after each;
      `agent-brain index`. `~/Projects/ECOSISTEMA.md` lists no KB today — decide whether it should.
- [ ] The Theory (owner's order, 2026-11-26: "launch one more subagent for category theory… the
      categories that then come down to Haskell and then pass to other languages… always the latest on
      the web, in papers, in books"). New repo `theory-kb`: `official/` (text sources whose license
      allows a mirror) + `notes/category-theory/<id>.md`, 20 concepts in learning order, fichas H01–H20:
      category · functor · natural transformation · limit and colimit · adjunction · monoidal functor ·
      monoid object · Kleisli category · F-algebra · free and forgetful · exponential and CCC · Yoneda ·
      Kan extension · contravariant functor · profunctor · comonad · monoidal category · sheaf ·
      optics · category theory and AI (a dated survey). Every note: the shape, the Haskell landing,
      what Kotlin, TypeScript and Nushell make of it (seeds for P2), and where a cited source finds
      it in architecture. A connection nobody published is an open thread, never a claim.

### P2 — The Maps: one per language (2 sessions)

- [ ] `kotlin-kb/notes/canon-map.md` — concept → Kotlin + `kap`, reusing the mapping table of the
      `fp-denotational-design` skill and `kap/LAWS.md`. Add `kotlin-kb/official` (kotlinlang.org
      publishes LLM-friendly mirrors of its docs).
- [ ] `nushell-kb/notes/canon-map.md` — concept → `nushell-fp`, folding the Elixir/Ramda table already
      in `fp-kb/notes/dynamic-fp-discipline.md`.
- [ ] New repo `typescript-kb`: `official/` (the Handbook, Apache-2.0) + `notes/canon-map.md`.
- [ ] Every map starts from the Theory: the language table of each `theory-kb` note says what a library
      documents; the probe in this phase turns it into a verified row or a gap.
- [ ] Every row carries a snippet that compiles in the drills repo. The owner's first example is the
      model: `Maybe` → `T?` with `?.let {}` covers `bind`, but `T?` has no `map`/`pure`, so it does not
      compose like the monad — that nuance is the teaching.
- [ ] Open decision, taken here with a probe: which TypeScript FP library, if any.

### P3 — Application: gates and oracles (2–3 sessions)

- [ ] Fix `rag-guard.nu`'s `is-law` to include every `*-kb/notes`; make `prompt-laws.nu` language-aware.
- [ ] `design-guard.nu`: from the static ladder to canon retrieval — on a file being born or a design
      prompt, `rag_query` the concept notes and the language's map, top 4, and inject the hits.
- [ ] Denying gates for Kotlin's mechanical laws, each with the 8-case selftest: manual wait loop
      (`while(true)`, polling — the `ManualWaitLoop` rule at write time), `!!` and `var` inside a
      pure-core module, `runCatching` around suspending code (cancellation laundering).
- [ ] Compiler oracles — the first PostToolUse hooks in the fleet, advisory only, skipped when the
      tool is absent or slower than a cap: `.nu` → parse check; `.kt` → detekt with the wired
      `kap-rules.yml`; `.ts` → `tsc --noEmit --incremental` when a tsconfig exists; `.hs` → `ghc -fno-code`
      (the owner's `haskell-flows-mcp` already wraps ghcide — reuse, do not rebuild).

### P4 — Rumbo: the judge (3 sessions)

- [ ] `El Pedido`: a typed witness `{ goal, constraints, nonGoals, acceptance }` written at turn start
      to `~/.zcode/hooks/state/pedidos/<session>.json`.
- [ ] Triggers, from the replay: two tool errors in the first 10 steps · a subagent in the first 10 ·
      no edit in the first 8 · a request of 60 characters or fewer past step 50 · the first edit.
- [ ] The judge: one stateless call over the pack (Pedido + the action ledger: files, tests, errors)
      returning `alineado · desvío menor · desvío` with cited evidence; it may only ask, once per turn.
- [ ] Seal: delivered versus Pedido, reusing the fork's completion-verifier prompt shape
      (`ZCode/apps/zcode-cli/packages/contracts/src/tools/target.ts`).
- [ ] It lives in the fork's hook layer, so desktop and phone share one mechanism.

### P5 — Learning: corrections become rules (3 sessions)

- [ ] A `correction` kind in the brain's bus taxonomy (`BusEvent.kt`), published by the bot on steer,
      stop-and-refire, cancel, and a hook denial the owner overrides, with the turn snapshot.
- [ ] A `corrections` table and its extractor, beside `MemoryExtractor.kt`: problem, root cause, fix,
      concept id; status `pending → approved → promoted`.
- [ ] Corpus Doctor gains the cause `law_gap` and proposes a hook rule, a canon note or a drill —
      never writes it. Approval is a Telegram card.

### P6 — The numbers (2 sessions; the baseline belongs to P0)

- [ ] `first-pass` — per language: law denials per 1,000 writes and oracle failure ratio, weekly.
- [ ] `canon` — a weekly sample of 20 edits judged against the Canon: adherence and the most violated
      concepts, which also says which ficha to read next.
- [ ] `rumbo` — cancels per 100 turns and the wasted-step share (baselines 12.4% and 74.3%).
- [ ] New `EvalKind`s and JSON suites in `agent-brain/evals/suites/`; `/learn` shows the three numbers.

### P7 — The book v5 (starts at P0; every phase writes its fichas)

**From the v4 audit (measured):** v4 has 149 dictionary entries in one part, zero exercises with
answers, 21 exam questions with no answer key, zero recap boxes, and eleven ideas used before they are
defined (LoRA, MRR, RRF, HNSW, harness, bus, ledger and gate among them). Its study plan assigns all
149 entries to a single day and is not ordered by prerequisite. The v5 rules that follow: one home per
idea (the fixture-blindness and LoRA stories are each retold four or more times in v4); no paragraph
over 100 words; order by prerequisite, never by date; the exam gets an answer key and a
question-to-ficha map.

**Shape.** `agent-brain/docs/book/`, Spanish, code and terms in English (the same deliberate exception
as v4). `00-hoja-de-ruta.md` is one page with a checkbox per ficha, ordered by prerequisite. v4 stays as
the historical record; the Desktop copy (still the old v2) is replaced by the hoja de ruta.

**A ficha** is at most 150 lines and sized for the owner's measured reading time: 10–20 minutes on new
material, chained so a hyperfocus session can continue. Fixed order:

1. **El ajá** (five lines or fewer) — the Haskell idea in one sentence and its equivalent in his
   languages, or a real bug from his repos that the idea explains. The reward comes first.
2. **Qué denota · en Haskell · por qué existe** — the one-sentence denotation, the type and its laws.
3. **En tus lenguajes** — the table: Kotlin, Nushell, TypeScript, with the gap and the trap.
4. **📍 En tu sistema** — the real file where it lives (`kap/LAWS.md`, a `nushell-fp` module, a bot file).
5. **✍️ Hoja de papel** — copy the signature; complete the law (`fmap id == ___`); draw the diagram.
6. **Drill** — the test to make pass in the drills repo.
7. **Preguntas de recuperación** — three, answered from memory; answers live in the answers part.
8. **Cierre** — a three-line recap, then the checkpoint: if you can explain X in your own words, go
   to ficha N.

**Parts.** A El mapa (the five pillars, the history in two pages) · B Los conceptos (the Canon, one
ficha each) · C Tu sistema por dentro (RAG, memoria, evals, MCP, harness — v4's architecture parts
rewritten as fichas) · D El harness como capa de leyes (new: hooks, skills, the ladder, gates, the judge,
promotion) · E Los números (recall, MRR, gates, and the three new ones) · F El método y las trampas ·
G Respuestas · H La teoría (the 20 categorical concepts above the Canon, fichas H01–H20, written after
B so that each one can point at its Canon note).

**Drills repo** `~/Projects/canonical-drills`: one module per language, types-first (the test and the
signature exist, the body is the exercise), one command per language, a CI gate.

**Why this shape.** Retrieval practice helps learners with ADHD but does not compensate for weak
encoding (Minear et al., *Frontiers in Psychology*, 2023) — so the meaningful connection comes before
the questions. New material is taught one idea at a time with a worked example (cognitive load theory;
NSW Department of Education, *Cognitive Load Theory in Practice*, 2017). Structured memory beats raw
history (MemCoder: 76.4 vs 73.0 vs 68.4 on SWE-bench Verified) and retrieval dilutes past a small
top-k — hence one note per concept and top 4 in the guard.

## 6. Measurement and gates

| Number | Today | Target |
|---|---|---|
| Law denials per 1,000 writes — Nushell | ≈ 30–37, flat | hold; kt/ts to Nushell's order within 6 weeks of P3 |
| Law denials per 1,000 writes — Kotlin, TypeScript | unknown | Nushell's order within 6 weeks of P3 |
| Oracle failure ratio, per language | unknown (baseline at P0) | falling week over week |
| Canon adherence of written code | not measured | baseline, then rising |
| Cancels per 100 turns | 12.4 | falling after P4 |
| Wasted-step share of cancelled turns | 74.3% | falling after P4 |
| RAG recall suites (`recall-core` 33/33) | green | stay green |

A phase is done when its tests are green and there is one live receipt: a real turn where the hook
fired, the judge spoke, or the eval row landed in the ledger.

## 7. Cost

- Runtime: the judge about $5–7 a month, the canon eval $1–2, everything else $0.
- Build: about 15 sessions across P0–P7.

## 8. Risks

- **Approval fatigue** — catch rates collapse under prompt volume: at most one phone interruption per
  turn, and an LLM never denies.
- **Fake equivalences** — laziness and higher-kinded types have no counterpart in Kotlin; the map says so.
- **Oracle latency** — advisory only, capped, skipped when slow; a gate must never stall the stream.
- **Scope** — one active unit at a time; the book is written inside each phase, never as a separate marathon.
- **Spanish fichas in the RAG** — `~/.zcode/agent-brain/corpus.txt` indexes no agent-brain docs today (the
  belief that v4 was indexed was wrong); Spanish fichas may add retrieval noise, as the 20/11 blog removal
  showed. Probe against `recall-core` before adding the book folder.
- **Stale notes** — the earlier audit recorded `kap-detekt-rules` as wired nowhere; they are wired in all
  three consumers. Verify before citing.
- **Fabricated sources** — the Theory cites papers and the web, where an identifier remembered by a model
  is often wrong: every arXiv id is checked against its abstract page, every cited path against the
  disk, and the father re-runs both checks before a merge.

## 9. Order

P0 → P1 → P2 → P3 → P4 → P5, with P6's baseline inside P0 and P7 running through all of them. Parts A,
C, D and F of the book document what already exists and can be written right after P0, so the v5 is
readable early. Open decisions deferred to their phase: the TypeScript FP library (P2), and whether the
`Pedido` is parsed deterministically or by a small model call (P4).

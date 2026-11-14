# Plan Brujula v2 — What Is Left to Learn

> Created: 2026-11-14 · Status: v2.0
> Supersedes the open units of [plan-brujula.md](plan-brujula.md) (v1, Spanish, stays as historical record).
> The compass, updated: the 2026 hiring skills for AI engineers are exactly the five things v1 built, **deepened** — evals + observability as products, RAG evolution (rerank, GraphRAG), agent memory as a discipline (Mem0/Zep/Letta, graph variants, LoCoMo-class benchmarks), and MCP Apps. Verified via serper searches on 2026-11-14.

## State real at 2026-11-14 (verified against repos, not against wishes)

One systematic gap per repo:

| Repo | What v1 built | The gap v2 attacks |
|---|---|---|
| `agent-brain` | Hybrid retrieval (pgvector + FTS + RRF, `Rrf.kt`, `Salience.kt`), dedup, eval suites, brain-mcp (4 tools), bus | Retrieval quality never measured over time (no recall@k/MRR/nDCG trend); decay/reinforcement loop half-built (access never reinforces, nothing ever forgotten); no memory-to-memory links/graph; `plan.md` F-gates never added (~111 un-gated suspects) |
| `ZCode` fork | Daemon-local channel, MCP Apps host **first-class** (`packages/shared/src/mcp-apps/`, `packages/ui/src/plugin-ui/`, host e2e fixture), capture/replay | The MCP Apps host has zero tenant apps; no CI at all; 2 parked deterministic test debts (`docs/fork/upstream-sync-expedition-v3.18.2.md`) |
| `zcode-bot-kotlin` | Full harness + live view, 1,297 tests, media ladder, brain gateway + memory injection | Zero quality evals of itself (comparator is a museum piece, runbook §6.6); sticker/animation still silently dropped (`Poller.kt` `normalizeAttachment`); red-team §6 ACTIONs pending |
| `kap` | 5.3.0 on Central, KMP targets, laws + PBT (kotest-property), JMH, docs site | Law suites only run on `jvmTest` despite multiplatform main sources; no optics; 5 DX issues open (v1-U11) |
| `whatsdigest-kt` | Live digest bot with LLM routing | Out of v2 scope except as an eval consumer if it earns a slot |

## The units

### agent-brain (highest learning density)

- [ ] **B1 — Retrieval eval harness** (v1-U7 core): golden query set over the real corpus (query → expected doc/memory ids); runner computes **recall@k, MRR, nDCG** into `eval_runs` + a trend matrix; CI gate. Reuses `EvalRunner` / `EvalLedger` / `evals/suites/*`. *Teaches: RAG evals — skill #1.*
- [ ] **B2 — Rerank stage**: cross-encoder/listwise rerank after RRF (local Ollama or LLM-listwise via the existing providers); A/B against B1's numbers — **keep only if measurably better**. *Teaches: modern RAG.*
- [ ] **B3 — Memory lifecycle**: access-reinforces (search hits bump salience), decay → archive state machine (active/cooling/archived) with an explicit forgetting law, wired into `RankKey`. *Teaches: agent memory design (Mem0/Letta territory).*
- [ ] **B4 — Memory graph**: memory-to-memory edges (entity / co-occurrence / temporal), related-memories traversal exposed in `memory_search`. *Teaches: GraphRAG.*
- [ ] **B5 — Gates + close-out**: add `plan.md` F1/F2 CI gates; finish Phase C (branch `agent/phase-c`) and D (kap-arrow).

### ZCode fork

- [ ] **F1 — Brain MCP App panel (FLAGSHIP; v1-U10 reborn on MCP Apps)**: side-pane panel on the existing host contract rendering brain memories / RAG answers / bus status live — the 4 brain MCP tools already exist. *Teaches: MCP Apps SDK — 2026's hottest integration skill; portfolio centerpiece.*
- [ ] **F2 — Parked test debts**: root-cause the `desktopRuntimeEnv` CUA ×3 and `product-projection` ×3 failures; fix or documented-kill.
- [ ] **F3 — Fork CI**: GitHub Action running `vitest run -c vitest.fork.config.ts` (292 tests) + typecheck + `architecture:check` — the repo has no CI at all.

### zcode-bot-kotlin

- [ ] **T1 — Shadow-judge evals**: daily LLM-as-judge over `~/.zcode/bot-recordings/` traces, reusing the comparator's launchd slot (`com.zcode.bot-comparator`); score → metrics + weekly digest. *Teaches: LLM-as-judge, quality observability.*
- [x] **T2 — Sticker/animation fix** (warm-up): extend `normalizeAttachment` — sticker → image or video by its real mime (`stickerKind`), animation → video (the Bot API's own words); the shape's own name keeps the truth. The last silent drop dies. *(Done 2026-11-14, full gate green; deploy pending owner's word.)*
- [ ] **T3 — /learn command**: weekly "what did the ecosystem learn" digest from the brain (`memory_recent` + `rag_query` + bus stats) to Telegram — closes the learning loop kept by hand today.
- [ ] **T4 — Red-team §6 ACTIONs**: rotate the healthchecks.io heartbeat URL + `~/.zcode/agent-brain/mcp.token`, 0600 perms, thread `fromId` into destructive taps.

### kap

- [ ] **K1 — Laws on every target**: move the 9 `*LawsTest` suites from jvmTest-only to the multiplatform matrix (js/native/wasm). *Teaches: KMP testing.*
- [ ] **K2 — Optics module**: `Lens`/`Prism`/`Optional`, laws first (extend-and-bump doctrine), KSP generation where it fits — fills the one conspicuously-missing FP slot.
- [ ] **K3 — U11 close**: the 5 DX issues (#14/#16/#18/#19/#20).

## Unified threads (the plan for all together)

- **E1 — Evals as product (the spine)**: B1 + T1 + one reproducible **≥2-model comparison run with cost/latency** (v1's stated U7 gate). Every improvement in B2/B3/T1 must prove itself against these numbers. **This is v2's exit criterion.**
- **E2 — The system reads its own memory**: B3 + B4 + T3 together make the ecosystem able to answer *"what did we learn this week"* — memory that reinforces, decays, links, and reports.
- **E3 — Portfolio loop**: one writeup per closed theme (kap blog exists; a "building an agent memory system" series feeds the CV / `interview-defense.md` battle card).
- **U5 render core (~4.9k LOC)**: PARKED by default — consciously deferred (low learning-per-hour vs evals/memory/MCP-App; the current render already satisfies the owner's visual laws). Carried only on the owner's word.

## Sequencing

1. T2 warm-up → **B1** (the instrument everything else measures with)
2. B2, B3 (proven against B1) → T1, T3
3. **F1 flagship** → F2, F3 as fillers
4. K1–K3 interleaved (self-contained)

## Rules (binding, inherited from v1 §3 and the standing laws)

- One unit per session, one active goal; **commit+push per logical unit**; KB note per unit.
- Subagents work in their own worktrees (`agent/<slug>` branches); only the parent writes master.
- FP-KB consulted before non-trivial design; denotational design law; kap or extend-kap.
- Deploys and other disruptive actions are always the owner's call.

## Verification

- **B1**: eval matrix reproducible with one command; before/after numbers for every B2/B3 change.
- **F1**: panel live in the fork side-pane reading real brain data; `mcp-apps-host-e2e.mjs` fixture passes.
- **T1/T3**: launchd job writes real reports; `/learn` answers from real brain data, not mocks.
- All repos: gates green — bot `./gradlew test detekt`; brain suite + eval gate; fork fork-suite; kap `build detektAll`.
- **E1 gate (exit)**: one command reproduces a ≥2-model eval run with pass/fail + cost + latency.

## Changelog

- 2026-11-14 v2.0: plan born — 2 explore agents over the 4 repos + serper pulse on 2026 AI-engineering skills; v1 open units folded in (U7→B1/T1, U10→F1, U11→K3, U5 parked).

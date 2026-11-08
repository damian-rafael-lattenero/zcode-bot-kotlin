# Concurrency/FP audit — findings and verdicts (2026-11-08)

Companion to [`concurrency-audit.md`](concurrency-audit.md) (the mechanical U0
inventory). U1 = two read-only review agents over every U0 hit; U2 = the fix
batches (all merged to master); U3 = the CI sweep extension + this document.

## Score

| U0 category | hits | REAL | false-positive / lawful | verdict |
|---|---|---|---|---|
| forbidden | 7 | 0 | 7 | the `while(true)` was a bounded collision probe; `runBlocking` hits are entry-point bridges; the raw `Thread` is the JDK HttpServer's executor shape |
| handrolled | 49 | 3 | 46 | MenuMirrorRefresher RMW race (fixed), BusSink monitor-across-syscalls (fixed), TurnSpanTracker taps-under-lock (invariant pinned) |
| impure | 101 | 2 | 99 | two hidden wall-clock reads in channel renders (fixed); ALL file IO lives at lawful edges with atomic tmp+rename |
| errors | 181 | 14 | 167 | 14 suspend catch-alls swallowed or laundered cancellation (all fixed); ~30 existing sites already carry the correct rethrow idiom |

## The laws this audit pinned (the review checklist)

1. **`@Volatile` is for independent cells only.** A `state = f(state)` cell
   shared by two coroutines is a lost update waiting to happen — it is kap
   `Ref` (`update`/`modify`), never `@Volatile`. (MenuMirrorRefresher was the
   one violation; the other ten `@Volatile` hits are single-writer handles,
   cache cells, or coroutine-confined state.)
2. **Cancellation is never swallowed or laundered.** Every suspend catch-all
   rethrows `CancellationException` FIRST (`catch (e: CancellationException) {
   throw e }`), or an honest-degrade edge reads a shutdown as "Telegram
   failed" and answers the owner with lies. (TgApi wrapped CE into IOException
   through the redactor seam — the worst class: a transformation, not a
   swallow.)
3. **Decide under the monitor, act after it.** No IO, no sends, no suspend
   calls inside a `synchronized`/lock section — snapshot the decision as a
   value, fly the effects outside. (Questions.kt is the house exemplar:
   `Registration`/`AnswerStep` ADTs, persist/send after the lock.)
4. **No monitor across syscalls.** A writer holding a lock across disk
   write+flush pins a thread and stalls every contender; single-writer or a
   suspending `Mutex` instead. (BusSink.)
5. **Pure cores take an injected clock.** A render is a function of its
   inputs; `LocalDate.now()` inside a renderer is a defect (OpencodeQuota's
   `nowMs: Long = System.currentTimeMillis()` default param is the house
   pattern).
6. **Errors that cross a seam carry a policy, not a stack trace.** Typed
   refusals (`CircuitBreakerOpen`, `ChannelDown`) read as policy arms
   downstream; generic `IllegalStateException` only at true bug-guards.

## Landed fixes (all merged to master, gates green)

| commit | what |
|---|---|
| `e3ee33f` | TgApi call/downloadFile/upload rethrow CE instead of laundering it into IOException (+test) |
| `b64f1b7` | the other 11 CE sites: QuotaFetch, QuotaDirect, OpencodeQuota, ChannelWiring ×3, DeployFlow, Main.firehoseProbe, BotMenu, NuclearLadder.stopAllStep, PromptTap (+2 tests) |
| `ee9f5b1` / `3398a0b` | BotCommands + QuotaDirect renders take an injected `nowMs` (+2 deterministic tests; goldens byte-identical) |
| `6425afe` | LiveOrphanStore logs the orphan-seal persistence failure at debug |
| `5d0a2fd` | MenuMirrorRefresher state → kap `Ref` with atomic `update`/`modify` (+ concurrency property test: never lose a registration, never resurrect a seal) |
| `760be29` | BusSink append under a `Mutex` — no monitor across syscalls (+ 2×100-line intact test) |
| `710b834` | tap invariants + pump-owned guard sets pinned in KDoc |
| `cc6e114` | Routing's three `!!` replaced by typed null-flow (smart-cast at the `when` arms) |

## Documented-justified exceptions (do not "fix" these)

- `daemon/Main.kt` / `recorder/Main.kt` `runBlocking` — entry-point bridges
  into the kap Process graph (the composition root is the edge).
- `MenuMirrorRefresher.create` — a ONE-SHOT `runBlocking` at birth, because
  kap `Ref.of` answers a Kap and the wiring is plain boot code. Creation
  only, never a call path.
- `MetricsEndpoint` raw daemon `Thread` — the JDK HttpServer executor shape,
  loopback-only.
- `Questions.nextShortId` `while(true)` — bounded collision probe (≤ cap+1
  iterations, no suspend/IO inside).
- `DeploySpawn` busy `IllegalStateException` — user-facing outcome spoken by
  the flow; a typed `DeployVerdict.Busy` is the future cleanup (owner's call).

## Still open (candidates, not scheduled)

- Publish `kap-detekt-rules` (the `ManualWaitLoop` rule exists, JVM-only,
  unpublished) — the definitive mechanical gate for rules 1–3.
- `DeploySpawn` typed verdicts (C5-1) — M effort, anti-dogma defers.
- CI cannot grep "CE before catch-all": review-time rule until the detekt
  rule lands.

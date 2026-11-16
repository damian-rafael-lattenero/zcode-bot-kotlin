# Typed SQL (SQLDelight) — the daemon's read-only SQLite reads

Date: 2026-11-16 · Status: ACCEPTED

## Context

The daemon read the app's `v2/tasks-index.sqlite` and the CLI's
`cli/db/db.sqlite` with 15 hand-written SELECTs across five files
(`TasksHistoryIo`, `InterventionsNotifs`, `SubagentNotifs`, `SpectatorIo`,
`WiringFragments`). Every query mapped columns by index, the SQL strings lived
in `private companion object` blocks, and the schema was documented only in
`docs/fork-interface-map.md`. The same migration already runs in agent-brain
(ADR-007) and whatsdigest-kt (ADR-001).

## Decision

1. **SQLDelight 2.4.1 over the stock SQLite dialect** (`sqlite-3-38-dialect`).
   The generated queries parse and type-check at build time; the dialect is
   upstream (nothing pgvector-ish is involved here).
2. **Typing-only schema.** `Schema.sq` declares the four tables this repo
   reads. Both files belong to the ZCode app/CLI: the daemon never creates,
   migrates or writes them (`open_mode=1`). No `.sqm`, no schema generation.
3. **The house read pattern is untouched.** Every read still opens one
   short-lived xerial connection, read-only, inside `runCatching` — a missing
   table/column still degrades to the previous "empty answer" through the
   SQLException catch. The generated call rides that SAME connection through
   `BorrowedConnectionDriver` (`db/Typed.kt`), which never owns or closes it.
4. **Public surface frozen.** `TasksHistoryIo`, the watchers and the wiring
   helpers keep their signatures; only their private SQL mapping changed.
5. **Behavioral gate = the existing suites.** The 40 tests that seed real
   SQLite files and drive these classes (TasksHistoryIoTest, SubagentsTodayTest,
   InterventionsWatchTest, SubagentNotifsTest, SpectatorIoTest,
   FinalAnswerResolverTest) pin the queries against real schema fixtures,
   including the older/partial-schema degradation paths.

## Notes and traps

- The tasks-index query has no `LIMIT`: the cap applies AFTER the duplicate-id
  dedupe, so a legacy duplicate never eats a slot. The generated rows are
  materialized and the walk stops at the cap (same rows, same order).
- The spectator's label and the workspace lookup tolerate a missing column by
  the same `runCatching` that tolerated a missing table before.
- Column constants (`TID_COL`, `SQLITE_READONLY`, …) and the SQL string
  constants are gone; the SQL text lives single-source in `Tasks.sq`/`Cli.sq`.
- If the app renames or drops a column, the generated query fails at prepare
  time and the read degrades exactly like the raw one did — but the mismatch is
  now visible at build time when the `.sq` typing schema is updated.

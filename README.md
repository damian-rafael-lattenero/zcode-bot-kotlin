# zcode-bot-kotlin

The Kotlin rebirth of [zcode-bot](https://github.com/damian-rafael-lattenero/zcode-bot):
the Python bot keeps running untouched while this repo grows the pieces that
replace it, one subsystem at a time, through the **file contracts** the
Python processes already share (atomic `tmp+fsync+rename` writes, `flock`
with a deadline, mtimes as shared clocks). The roadmap is
[`docs/kap-first-migration.md`](https://github.com/damian-rafael-lattenero/zcode-bot/blob/master/docs/kap-first-migration.md)
in the bot repo; this repo is its **B0**.

**Principles (binding):** FP-first development — pure by default, KAP as
the effect layer and standard library (anything KAP lacks is added to KAP
with laws and a version bump), Arrow for error channels, laws before
features, mutability only as a visible escape hatch. See
`docs/parity-and-engineering.md` §7.

## Modules

| Module | What it is |
|---|---|
| `modules/contract-types` | Read-side contracts of every persisted format (`schemas.py` is the spec): the bus event (mirror of agent-brain's `BusEvent`) plus bot-session, workers registry, queue, active turn, outbox, zconn, audit, CoT boxes and the nuclear report — **bilingual forever** (readers accept the legacy Spanish keys; writers emit English). |
| `modules/recorder` | The golden replay recorder: a one-shot pass that snapshots `~/.zcode/hooks/state/` (content + mtime + sha256) into a daily JSONL trace. Later phases replay these traces to prove byte-level parity before every cutover. |
| `modules/daemon` | The B1 shadow daemon: the watchers reborn on KAP. B1a zconn (the episode machine pure, the CLI-log tailer, byte-exact notice texts) and B1b the spectator (marker-driven per-turn `Process`es with the hook's exact spawn gates, the push gates read live, and the RENDERER's first proof: `_tool_box` + a faithful `difflib.SequenceMatcher` port pinned by 21 golden vectors generated from the real Python) — all under `Process` (cadence is `repeat(spaced)`, bugs restart under the convergence law, the heartbeat beats at its own pace) with the E1 logging stack (JSONL + MDC, async neverBlock). The **B2 send knob** (`~/.zcode/bot-kotlin/daemon.json`, read at boot): `shadow` (decisions land in `~/.zcode/bot-kotlin/shadow/`, the Python bot keeps sending) → `dry-run` (compose the byte-exact sendMessage request and log it, token redacted, WITHOUT posting) → `live` (the daemon owns the send through the transport, sharing Python's `tg-cooldown`/`outbox.json`/`tg-last-ph`; Python keeps polling getUpdates — the daemon NEVER polls). Rollback is the knob back to `shadow` + Python's send on. |
| `modules/comparator` | B1c — the parity gate: diffs the shadow decisions against what the Python bot actually did (kb.log for zconn's sends, prompt-worker.log for the spectator's turns), writes a daily report with a PARITY/DIVERGENT/INSUFFICIENT verdict, and `--week` aggregates: seven consecutive PARITY days earn the cutover knob. Its first real run caught a genuine divergence (the hook's F15 spawn gate was missing) within hours. Post-cutover (owner's decision 30/10) it runs as the daily audit: a DIVERGENT after the flip is evidence, not a blocker. |

Later: `transport` (B2, Ktor + the same tg-offset/cooldown/outbox
contracts), and the render core (B4, on KapStream — the SPEC-5.0
dogfooding contract).

## The trace contract

`~/.zcode/bot-recordings/replay-YYYY-MM-DD.jsonl` (UTC days; **never inside
a repo** — traces contain real prompts):

- `{"t":"pass",...,"total":N,"changed":M,"gone":K}` — one per pass, always:
  a silent state dir is distinguishable from a dead recorder.
- `{"t":"file","name":...,"size":...,"mtimeMs":...,"sha256":...,"content":...}` —
  per new/changed file; content inlined up to 512 KB (text, base64 for
  binary), hash-only above.
- `{"t":"gone","name":...}` — per disappeared file.

The mtime is recorded because it IS data: the Python bot uses file mtimes
as shared clocks (the 429 cooldown's expiry epoch is a mtime).

## Build and deploy

```bash
# The Mac's ~/.gradle/gradle.properties pins the Gradle daemon JVM to 25
# (kap's compile toolchain) — and detekt 1.23 cannot run on a daemon >= 24.
# The command-line override beats gradle.properties; CI runs plain (Corretto 21).
./gradlew -Dorg.gradle.java.home="$(/usr/libexec/java_home -v 21)" test detekt
scripts/deploy-recorder.sh       # B0: ~/.zcode/bot-recorder + LaunchAgent
scripts/deploy-daemon.sh         # B1a: ~/.zcode/bot-daemon + LaunchAgent (shadow)
```

The recorder is a fresh JVM per pass (LaunchAgent `StartInterval`, 60 s) —
the same self-healing dumb-ring pattern the bot's outer healer uses. The
daemon is one long-lived JVM under a KeepAlive LaunchAgent
(`com.zcode.bot-daemon`): inner supervision is KAP `Process`, launchd is
the dumb outer ring. Both are additive only — the daemon reads the CLI
logs the Python bot also reads, and every write stays under
`~/.zcode/bot-kotlin/` (ADR-023) — except in `live` mode, where the send
shares Python's own `tg-cooldown`/`outbox.json`/`tg-last-ph` by design
(the strangler's handover files).

### The B2 cutover knob and its rollback

`~/.zcode/bot-kotlin/daemon.json` (`{"send": "shadow|dry-run|live"}`),
read once at boot — a mode change is `launchctl kickstart -k
gui/$(id -u)/com.zcode.bot-daemon`. The flip order is the owner's rule:
**daemon `live` FIRST, silence Python's send AFTER** (a duplicate
notification annoys; a silent phone is an incident) — never the reverse
and never a long gap. Python is silenced by config only:
`mac_spectator: false` in `~/.zcode/telegram-remote.json` (the hook's
spawn gate; the owner-side daemon ignores the knob from `dry-run`/`live`
on), and for zconn there is no config key in Python — its state file
`~/.zcode/hooks/state/zconn.json` with `since` in the future deafens
`zconn.listen` (`iter_events` skips everything at `ts < since - 1`, and
`since` never advances without events); back up the file before
overwriting it.

One-line rollback (knob back + Python's send on):

```bash
printf '{"send": "shadow"}' > ~/.zcode/bot-kotlin/daemon.json && launchctl kickstart -k gui/$(id -u)/com.zcode.bot-daemon && python3 -c "import json,os; p=os.path.expanduser('~/.zcode/telegram-remote.json'); c=json.load(open(p)); c['mac_spectator']=True; json.dump(c,open(p,'w'),indent=2); os.chmod(p,0o600)" && cp ~/.zcode/hooks/state/zconn.json.pre-cutover ~/.zcode/hooks/state/zconn.json
```

## Status

| Phase | State |
|---|---|
| K0 — kap 4.2 `Process` (restart policies, heartbeat, laws) | done ([kap](https://github.com/damian-rafael-lattenero/kap) 4.2.0) |
| B0 — this repo: contracts + recorder | recording since 2026-10-29 (gates B1's cutover on >= 2 weeks of traces) |
| B1 — zconn + spectator watchers (shadow, then cutover) | B1a + B1b + B1c deployed 2026-10-29: both watchers in shadow (golden-vector renderer) and the comparator; the week gate was waived by the system owner 30/10 (aggressive cutover) — the comparator continues as the daily post-cutover audit |
| B2 — transport (Ktor, same state files) | B2a transport complete (TgApi/Sender/Outbox/Cooldown/LastPh/TgBreaker, 79 tests); B2b the send knob `shadow|dry-run|live` (`~/.zcode/bot-kotlin/daemon.json`, read at boot) + the dry-run composer + the owner-side mac_spectator override; polling stays in Python (ADR-001) — the daemon never calls getUpdates |
| B3 — workers under `Process` | |
| B4 — render core on KapStream | |
| B5 — thin hooks, Python retirement | |

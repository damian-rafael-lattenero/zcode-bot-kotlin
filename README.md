# zcode-bot-kotlin

The Kotlin rebirth of [zcode-bot](https://github.com/damian-rafael-lattenero/zcode-bot),
and since 2026-10-30 **the live bot**. The Python bot was retired in the
U2.5 cutover; this repo owns the Telegram transport, the daemon-local
channel, the live view and the getUpdates poller. The Python repo stays as
the parity reference and the rollback door. The migration grew through the
**file contracts** the Python processes shared (atomic `tmp+fsync+rename`
writes, `flock` with a deadline, mtimes as shared clocks). The roadmap is
[`docs/kap-first-migration.md`](https://github.com/damian-rafael-lattenero/zcode-bot/blob/master/docs/kap-first-migration.md)
in the bot repo; this repo is its **B0**. The current unified plan is
[`docs/plan-brujula.md`](docs/plan-brujula.md).

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
| `modules/daemon` | The live daemon: the watchers reborn on KAP. B1a zconn (the episode machine pure, the CLI-log tailer, byte-exact notice texts) and B1b the spectator (marker-driven per-turn `Process`es with the hook's exact spawn gates, the push gates read live, and the RENDERER's first proof: `_tool_box` + a faithful `difflib.SequenceMatcher` port pinned by 21 golden vectors generated from the real Python) — all under `Process` (cadence is `repeat(spaced)`, bugs restart under the convergence law, the heartbeat beats at its own pace) with the E1 logging stack (JSONL + MDC, async neverBlock). The **send knob** (`~/.zcode/bot-kotlin/daemon.json`, read at boot): `shadow` (decisions land in `~/.zcode/bot-kotlin/shadow/`) → `dry-run` (compose the byte-exact sendMessage request and log it, token redacted, WITHOUT posting) → `live` (the daemon owns the send through the transport, sharing Python's `tg-cooldown`/`outbox.json`/`tg-last-ph`). Since the retirement the daemon also owns getUpdates (`poll: true`). Rollback is the knob back to `shadow` + Python's send on. |
| `modules/comparator` | B1c — the parity gate: diffs the shadow decisions against what the Python bot actually did (kb.log for zconn's sends, prompt-worker.log for the spectator's turns), writes a daily report with a PARITY/DIVERGENT/INSUFFICIENT verdict, and `--week` aggregates: seven consecutive PARITY days earn the cutover knob. Its first real run caught a genuine divergence (the hook's F15 spawn gate was missing) within hours. Post-cutover (owner's decision 30/10) it runs as the daily audit: a DIVERGENT after the flip is evidence, not a blocker. |
| `modules/channel` | B3b — the daemon-local channel client (the fork's ADR-022/B3a unix socket, spec in `~/Projects/ZCode/docs/fork/DAEMON-CHANNEL.md`): the protocol as PURE values pinned against the spec's own fixtures (hello/auth with the 0600 token, inbound to the REAL conversation, `bot-channel-continuous` stream, the §3.4 service queries), reconnection as Kap policy (`retry(exponential and whileInput{ChannelDown})` — a missing socket is the DEGRADED state, a periodic loud warning, never a crash: the channel exists only while the fork's host is deployed), the LIVE VIEW (the never-radio-silence contract: one status message edited in place — spinner, thinking chars, writing LINES from `tool_call_update` streaming, timers, the fork's own apiRetry pill with the honest countdown, visible heartbeat; sealed at `task_complete` with the spectator's renderer + the snapshot's answer), the eight commands of the owner's MVP (`/tasks` `/all_tasks` `/status` `/stop` `/nuclear` `/quota` `/help` `/subagents`, free text routed to the real conversation), and the getUpdates POLLER (ON since the retirement, `poll: true`; the single-reader rule is now the daemon's). Also the error inventory's fixes: the zconn honest retry countdown (`model.network.retry_scheduled`, the 1308 lesson) and the ⌘-stop (cancelled) that is not an outage. |

Later: the render core (B4, on KapStream — the SPEC-5.0
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
scripts/deploy-recorder.nu       # B0: ~/.zcode/bot-recorder + LaunchAgent
scripts/deploy-daemon.nu         # the daemon: ~/.zcode/bot-daemon + LaunchAgent (zconn/spectator + B3b channel/live view; poller on since the retirement)
```

The whole script surface is typed Nushell (`scripts/*.nu`), pinned by
shebang to `~/.local/bin/nu-nightly` (0.116.2-nightly.28). `nu-check
--debug` is the parse gate; the migration notes live in
`~/Projects/nu/002-migracion-total-2026-11-02.md`.

The recorder is a fresh JVM per pass (LaunchAgent `StartInterval`, 60 s) —
the same self-healing dumb-ring pattern the bot's outer healer uses. The
daemon is one long-lived JVM under a KeepAlive LaunchAgent
(`com.zcode.bot-daemon`): inner supervision is KAP `Process`, launchd is
the dumb outer ring. The daemon reads the CLI logs the fork writes, and
every write stays under `~/.zcode/bot-kotlin/` (ADR-023) — except in
`live` mode, where the send shares the Python contract files
`tg-cooldown`/`outbox.json`/`tg-last-ph` by design
(the strangler's handover files).

### The B2 cutover knob, the B3b poll knob, and the Python retirement

`~/.zcode/bot-kotlin/daemon.json` (`{"send": "shadow|dry-run|live", "poll": false}`),
read once at boot — a change is `launchctl kickstart -k
gui/$(id -u)/com.zcode.bot-daemon`. The send flip order is the owner's rule:
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

The `poll` knob (B3b) is **shipped OFF**: the getUpdates poller exists
and is wired but does not run, because Python still owns the phone's
reads (ADR-001's single reader — two pollers on one token fight
Telegram's 409). It turns on ONLY at the retirement.

The `metrics` knob (E2, also **shipped OFF**) lights up a
Micrometer/Prometheus registry and a `GET /metrics` listener on
**127.0.0.1 only** (`metrics_port`, default 9102) — every metric
derives from a real event that already exists, and OFF is byte-for-byte
the pre-E2 daemon. The full inventory: [docs/observability.md](docs/observability.md).

The `otel` knob (E3, also **shipped OFF**) adds OpenTelemetry TRACES —
one `bot.turn` span per turn, one `bot.tool` child per tool call —
batch-exported as OTLP/HTTP to `otel_endpoint`. It EXPORTS and binds
nothing; a dead collector is one log line, never a broken turn. The
span tree and the local-Jaeger recipe: [docs/observability.md](docs/observability.md).

**Rollback after the Python retirement (read this twice).** With Python
retired, `send: "shadow"`/`"dry-run"` mean **NOBODY speaks**: there is no
Python send to fall back to, and the phone goes silent (only the healer's
siren, which is send-only, would still reach it). The honest recovery
order is:

1. `live` again (the daemon is the only sender): `printf '{"send": "live"}'
   > ~/.zcode/bot-kotlin/daemon.json && launchctl kickstart -k
   gui/$(id -u)/com.zcode.bot-daemon`
2. If the daemon itself is the problem, restore the retired Python (the
   `bot` repo + the inverse of `scripts/retire-python.nu`) — an explicit,
   documented decision, never a silent knob flip.
3. The pre-cutover zconn memory
   (`~/.zcode/hooks/state/zconn.json.pre-cutover`) only matters for the
   Python path; it is NOT a rollback by itself.

### The abrupt Python retirement (B3b's last step — `scripts/retire-python.nu`)

Prepared, **never run by the agent**: it executes only when Damian has
restarted the app with the fork's daemon-local channel deployed (⌘Q →
`scripts/build-install.nu --yes` in `~/Projects/ZCode` → `scripts/verify-deploy.nu`),
because retiring Python's poller before the channel exists leaves the
phone with no way to start turns. The script verifies every step:

1. **pre-check** — the unix socket answers a `hello` (refuses to retire
   onto a dead phone);
2. **unload** Python's three LaunchAgents (`com.zcode.telegram-cmds`,
   `com.zcode.telegram-watch`, `com.zcode.bot-healer`) — FIRST, so the
   single getUpdates reader is free the moment the daemon takes it;
3. **flip** `poll: true` in daemon.json + `launchctl kickstart -k` (the
   file is the deploy, the restart is the apply);
4. **verify** — the daemon's boot line says poll on, the channel is UP
   (not degraded), `/status` answers from the phone, sends stay LIVE.

Rollback: relaunch the Python agents (`launchctl bootstrap gui/$(id -u)
~/Library/LaunchAgents/com.zcode.telegram-cmds.plist`, same for watch and
healer) and flip `poll` back to false + kickstart. After the retirement
the daemon survives by its `Process` restart policies + launchd's
KeepAlive; every failure is fixed and committed hot (the owner's
hot-fix loop, U2.5).

## Status

| Phase | State |
|---|---|
| K0 — kap 4.2 `Process` (restart policies, heartbeat, laws) | done ([kap](https://github.com/damian-rafael-lattenero/kap) 4.2.0) |
| B0 — this repo: contracts + recorder | recording since 2026-10-29 (gates B1's cutover on >= 2 weeks of traces) |
| B1 — zconn + spectator watchers (shadow, then cutover) | B1a + B1b + B1c deployed 2026-10-29: both watchers in shadow (golden-vector renderer) and the comparator; the week gate was waived by the system owner 30/10 (aggressive cutover) — the comparator continues as the daily post-cutover audit |
| B2 — transport (Ktor, same state files) | B2a transport complete (TgApi/Sender/Outbox/Cooldown/LastPh/TgBreaker, 79 tests); B2b the send knob `shadow\|dry-run\|live` + the dry-run composer; **LIVE since 30/10 15:54**, and since the retirement the daemon also owns getUpdates (`poll: true`) |
| B3 — the channel + workers under `Process` | B3a landed in the fork (commit `9b814dcb`: the daemon-local unix socket — spec `docs/fork/DAEMON-CHANNEL.md`); B3b this repo: `modules/channel` (client + live view + the eight + the poller) **deployed and live**; the Python retirement ran 30/10 17:59 (`scripts/retire-python.nu`) |
| B4 — render core on KapStream | pending (the next big surface after U1) |
| B5 — thin hooks, full Python retirement | Python is retired (30/10); thin hooks + the single LaunchAgent artifact + the E5 runbook remain |

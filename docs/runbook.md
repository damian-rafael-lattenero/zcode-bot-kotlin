# Runbook — operating the Kotlin bot

The operational manual of **this** system, ported from the retired Python
bot's `docs/runbook.md` (2026-11-03, U6/E5). It is not a copy: every
recipe below was verified against the code in this repo and, where it is
read-only, executed against the live machine. Python-era recipes that no
longer exist are listed in §9 with what replaced them — or with
"nothing".

**Verified against:** `master` `fe434b1`, 2026-11-03 22:50–23:10
(America/Argentina/Buenos_Aires). The evidence appendix (§10) carries the
commands and their real, abbreviated output.

The other operational documents stay authoritative in their own lanes:
[`observability.md`](observability.md) (the daemon's metrics/traces and
the dashboard half), [`beacon-runbook.md`](beacon-runbook.md) (the
dead-man's switch), [`incident-30-10.md`](incident-30-10.md) (the token
war that retired the Python hooks).

## 0. How to read this file

Commands carry one of two markers:

- **`[read-only]`** — safe to run any time; it observes. Every one of
  these was executed for this runbook.
- **`[ACTION]`** — changes state (restarts, deploys, moves files). The
  **owner** runs these, or the daemon does. An agent session must not,
  except where a recipe explicitly says otherwise.

Never printed anywhere in this file, and never read by an agent: the
contents of `~/.zcode/hooks/bot-provider.json`, the bot token inside
`~/.zcode/telegram-remote.json`, and the URL inside
`~/.zcode/bot-kotlin/heartbeat.url`. Paths only.

## 1. The living system

### 1.1 Who owns what

Four owners, and the boundaries matter more than the components:

| Owner | What it runs | Why the boundary exists |
|---|---|---|
| **launchd** (`gui/$(id -u)`) | every `com.zcode.*` job below; the daemon's `KeepAlive`; the deploy, healer, beacon, recorder, comparator, brain | survives app restarts, shells dying, and the agent session ending |
| **the ZCode fork (the app)** | the `application.dev.zcode.*` job, `zcode-cli` children, the `daemon-local` unix socket + token in `~/.zcode/` | the channel exists only while the fork's host is deployed; the bot must never kill it |
| **colima + docker** | `agent-brain-pg`, `agent-brain-redpanda`, `agent-brain-prometheus`, `agent-brain-grafana` | Postgres/Redpanda must outlive the app; started from Ghostty, never from a ZCode session (a ZCode restart takes the VM down — §8.5) |
| **the agent session** | nothing permanent. Reads, builds, commits. | a child of the session dies with the session; that is the whole reason everything above is a launchd job |

The full job census, with states, lives in §5. Inside `com.zcode.bot-daemon`
one JVM runs every bot watcher:

| Process (KAP name) | Cadence | Heartbeat file | What it does |
|---|---|---|---|
| `zconn` | every 5 s | `state/hb-zconn-shadow` | reads the fork's CLI logs, folds the model-wire episodes, speaks ⚠️/✅ once per episode (`ZconnShadow`, `ConnTrack`) |
| `spectator-orchestrator` | every 2 s | `state/hb-spectator` | scans `~/.zcode/hooks/state/turn-*.marker` and narrates a turn nobody else streams. **Since 2026-10-30 nothing writes markers** — see §6.4 |
| `channel` | reconnect with exponential retry (1 s → 30 s) | `state/hb-channel` | the `connect → hello → subscribe` client on `~/.zcode/daemon-local.sock` (`ChannelClient`) |
| `channel-ping` | every 30 s | none | ping/pong keepalive; a missing pong kills the socket so the reconnect path runs |
| `live-view` | every 3 s edit cadence | `state/hb-live-view` | the never-radio-silence card: one message edited in place (`LivePublisher`) |
| `poller` | 1 s spacing + 3 s long-poll | `state/hb-poller` | getUpdates, the single reader since the Python retirement (`Poller`) |
| `subagent-notifs` | every daemon tick (5 s) | `state/hb-subagent-notifs` | the 🔔 owner notifications from the store's transitions |
| `interventions-watch` + its own `channel` client | every daemon tick (5 s) | `state/hb-interventions` | the any-task law (14/11): follows every recently-active task on a SECOND connection and hands its permission/elicitation questions to the same board — the phone answers questions from tasks the owner is not reading (`InterventionsNotifs`, `InterventionsWatch`) |
| `outbox-drain` | every 15 s | none | paces the lane(s)' parked messages out of `outbox*.json`, 1 msg/s (both files only when the firehose split exists) |

Sources: `modules/daemon/.../Main.kt` (the wiring), `modules/channel/.../ChannelClient.kt`,
`Poller.kt`, `LivePublisher.kt`; the heartbeat constants at the bottom of `Main.kt`.

### 1.2 Heartbeats and what they prove — read this before trusting one

All `hb-*` files are **mtimes touched by KAP's heartbeat**, which beats
every 30 s *while the process is alive — including during the body's
execution and during restart delays* (`kap-resilience/.../Process.kt`).

That is the honest reading:

- a **fresh** `hb-*` proves the process **loop** is alive;
- it does **not** prove the work is succeeding. A channel that can never
  connect still beats: the reconnect lives inside the body as a retry.

So `hb-channel` fresh + `channel DEGRADED: no socket` in the log is the
normal, correct picture of "the app is closed, sends keep flowing". The
healer (§2.4) exists to catch the other case: the loop itself died.

### 1.3 What survives what

| Event | daemon | sends | phone can start turns | brain | dashboards |
|---|---|---|---|---|---|
| the agent session ends | survives (launchd) | yes | yes | survives (launchd) | yes |
| ZCode app quits / restarts | survives | **yes** (send path never needs the app) | no — no socket, `/status` and `/plan` answer 📡 | survives | yes |
| `launchctl kickstart -k` the daemon | restarts | yes (queue holds) | yes, after reconnect | unaffected | scrape gap only |
| `make down` in the brain repo | survives | yes | yes | **no** — memory/RAG fail loudly (`bot_brain_failures_total`) | no |
| colima stopped (§8.5) | survives | yes | yes | `/ready` DEGRADED or the job exits 75 and launchd retries | no |
| the Mac reboots | launchd starts everything | yes, once it is up | yes | launchd starts it; colima must be started by hand | by hand (`make up`) |

## 2. Start, stop, deploy

### 2.1 Where the daemon lives

| Path | What |
|---|---|
| `~/.zcode/bot-daemon/` | the installed distribution (`bin/daemon`, `lib/`), installed by the deploy |
| `~/.zcode/bot-kotlin/daemon.json` | the knobs, read **once** at boot (§8.7) |
| `~/.zcode/bot-kotlin/state/` | `hb-*`, `zconn.json`, `healer.json`, `live-view-active.json`, `inbound-queue.json`, `subagent-notifs.json`, `verbosity.txt`, `deploy-job.plist` |
| `~/.zcode/bot-kotlin/logs/` | `daemon.jsonl` (the signal), `launchd.{out,err}.log` (the crash noise), `healer.log`, `deploy-daemon.log` |
| `~/Library/LaunchAgents/com.zcode.bot-daemon.plist` | the rendered launchd definition (`KeepAlive` true) |

### 2.2 Start / stop / restart the daemon

```bash
# [read-only] is it loaded, and what is its state?
launchctl print gui/$(id -u)/com.zcode.bot-daemon | head -8

# [ACTION] restart (the apply of any daemon.json knob change)
launchctl kickstart -k gui/$(id -u)/com.zcode.bot-daemon

# [ACTION] stop it and keep it down until the next deploy/boot
launchctl bootout gui/$(id -u)/com.zcode.bot-daemon
# [ACTION] bring it back from its rendered plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.zcode.bot-daemon.plist
```

`launchctl list | grep bot-daemon` prints `PID  last-exit  label`. That
second column is the **last** exit status, not a health verdict — an
`agent-brain` row can read `143` (SIGTERM from an old reload) while the
job runs fine today. Trust `launchctl print`'s `state = running` plus a
fresh `hb-*`, never the number alone.

### 2.3 Deploy the daemon

The deploy script is the **owner's** door and it is also `/deploy`'s
target from the phone. Both run the same file:

```bash
# [ACTION] owner only — build, install, re-arm the LaunchAgents
~/.local/bin/nu-nightly ~/Projects/zcode-bot-kotlin/scripts/deploy-daemon.nu
```

What the script does, in order (`scripts/deploy-daemon.nu`):

1. **writes `state/deploying`** — the healer's first door: a deploy is
   *supposed* to leave the daemon down. The marker ages out on its own
   after 15 min (`DEPLOY_GRACE_S` in `bot-healer.nu`).
2. builds with `./gradlew -q :modules:daemon:installDist detekt test`
   (the build is one JVM, toolchain 25) and
   installs into `~/.zcode/bot-daemon`.
3. **re-bootstraps `com.zcode.bot-healer` first**, so that if anything
   below fails, the job that resurrects the daemon is already armed.
4. re-bootstraps `com.zcode.bot-daemon` through `lib/deploy.nu`'s
   `bootstrap-agent`: render `__HOME__`, `bootout`, `launchctl enable`,
   `bootstrap`; **on failure it sleeps 2 s and retries once** — bootout's
   teardown races the bootstrap (measured: `error 5` twice on 2/11).
5. verifies the daemon actually armed: `state/hb-zconn-shadow` must be
   **newer than this deploy's start** and `state/zconn.json` must exist,
   15 s deadline. The mtime check exists because the files survive
   restarts, so mere existence once false-passed a daemon that never
   booted (measured 2026-11-02).
6. removes `state/deploying` — the healer owns the daemon again.

**`/deploy` from the phone is the same script, spawned differently.** The
daemon writes `~/.zcode/bot-kotlin/state/deploy-job.plist` and bootstraps
it as the **one-shot job `com.zcode.bot-deploy`** (`DeploySpawn.kt`). It
must be a *sibling* job, not a child: on 2026-11-02 22:02 a `nohup`'d
script was torn down by its own `launchctl bootout` (launchd kills the
job's whole process tree), the bootstrap never ran, and the bot stayed
silent until a manual bootstrap.

The anti-suicide door holds: the announce must LAND (a real message id)
before the spawn fires. A deploy the phone never heard about is never
launched.

```bash
# [read-only] is the deploy job loaded, and what did it run?
launchctl print gui/$(id -u)/com.zcode.bot-deploy | head -10

# [read-only] the deploy's own words (build + verification)
tail -20 ~/.zcode/bot-kotlin/logs/deploy-daemon.log
```

**Known failure — `bootstrap com.zcode.bot-deploy failed: Bootstrap
failed: 5: Input/output error`.** Measured 2026-11-03 at 19:37 (twice)
and 22:45. At that moment the label was still loaded from the 14:14 run
(loaded-and-stopped is its normal resting state) and **both** bootstrap
attempts — the 2 s retry included — answered `error 5`. `DeploySpawn`
discards the pre-bootstrap `bootout`'s exit code, so the bootout not
having taken effect is the likeliest cause, not a proven one. What is
certain: the daemon stays up, the phone hears the failure, and the
deploy never runs. The recovery is in §8.1.

### 2.4 The healer — the daemon's own dead-man's switch

`scripts/bot-healer.nu` under `com.zcode.bot-healer` (every 60 s). It
reads `state/hb-channel` and restarts the daemon when the beat is dead.

Three doors keep it honest (`bot-healer.nu`'s own constants):

| Door | Constant | What it prevents |
|---|---|---|
| freshness, not liveness | `STALE_AFTER_S = 180` | `launchctl print` says "loaded" for a hung process; 3 silent minutes mean the channel loop is not running |
| hands off during a deploy | `DEPLOY_GRACE_S = 900` against `state/deploying` | fighting the deploy that is *supposed* to have the daemon down |
| one action per 5 minutes | `MIN_INTERVAL_S = 300` against `state/healer.json` | a heal that does not take becoming a restart loop (the flood lesson) |

It chooses `kickstart` when the job is loaded and `bootstrap` when it is
not, writes `state/healer.json` + `logs/healer.log`, and sends **one**
Telegram line through a send-only `sendMessage` (it never touches
getUpdates, so it cannot fight the daemon's monopoly).

```bash
# [read-only] its whole view, as JSON — no action taken
~/.local/bin/nu-nightly ~/Projects/zcode-bot-kotlin/scripts/bot-healer.nu --status

# [read-only] the decision, printed, still no action
~/.local/bin/nu-nightly ~/Projects/zcode-bot-kotlin/scripts/bot-healer.nu --dry-run

# [ACTION] bypass the three doors (the live test) — restarts a real daemon
~/.local/bin/nu-nightly ~/Projects/zcode-bot-kotlin/scripts/bot-healer.nu --force
```

What it will **not** do: heal a daemon whose loop is alive but whose
channel cannot connect (§1.2 — the beat keeps beating), heal the app, or
heal the brain. Those have their own doors (§3, §4).

### 2.5 Deploy the fork — the owner, ⌘Q, never the session

The channel, the live view and every phone→turn path depend on the
**fork's** host. Its deploy is the owner's decision and it is destructive
by design (it quits the app, swaps `/Applications/ZCode.app`, reopens it):

```bash
# [ACTION] OWNER ONLY, from a terminal outside ZCode.
# The canonical recipe is the fork's own docs; the bot-side wrapper is:
~/.local/bin/nu-nightly ~/Projects/zcode-bot-kotlin/scripts/deploy-fork-and-retire.nu --dryrun   # phase 0 really runs, 1-6 narrated
~/.local/bin/nu-nightly ~/Projects/zcode-bot-kotlin/scripts/deploy-fork-and-retire.nu --real     # the real run
```

Its phases are sanity → quit → build/install (`~/Projects/ZCode/scripts/build-install.nu --yes`)
→ reopen → verify (`verify-deploy.nu` + a hello/hello_ok smoke) → retire
Python's leftovers → final announce. `--real` quits the app, so the
session that launched it must not be inside the app's process tree: the
repository's own documented door is the launchd job
`com.zcode.deploy-fork` (`RunAtLoad` is **false** on purpose — on 30/10 a
Mac reboot silently re-ran the cutover):

```bash
# [ACTION] OWNER ONLY — the detached door that survives the app's death
launchctl kickstart gui/$(id -u)/com.zcode.deploy-fork
tail -f /tmp/deploy-fork.log
```

**What NOT to do:**

- never `kill`/`killall`/`launchctl bootout` the ZCode app or its
  `zcode-cli` children **from an agent session** — the session is a child
  of the app, so it dies with it, and the bot loses the channel;
- never run the fork deploy from inside ZCode (the ⌘Q takes the script
  with it);
- never flip the fork's own rules to "deploy on boot" again (that is the
  30/10 lesson encoded in the plist comment);
- the sanctioned app restart is `/nuclear` **from the phone**, executed by
  the daemon (`modules/daemon/.../nuclear/NuclearLadder.kt`), not by a
  shell in the session.

## 3. Diagnosing a silent bot — the ladder

Run the steps in order and stop at the first one that answers "no". Each
step names what it *proves*, not just what it prints. Every step below is
read-only.

### Step 0 — is Telegram reachable at all?

The beacon already answers it every 60 s: it calls `getMe` directly and
records the verdict.

```bash
# [read-only] the newest beacon line, with the live Telegram probe
tail -1 ~/.zcode/bot-kotlin/logs/beacon.log
# 2026-11-03T22:59:47-03:00 ok epoch=... daemon_age_s=4 poll_fail_recent=false telegram_probe=ok
```

`telegram_probe=ok` + a silent bot means the problem is on the Mac (keep
going). `telegram_probe=fail` means Telegram or the network is the
problem: the bot's own apology will arrive when it recovers
(`poller RECOVERED` in `daemon.jsonl`), and there is nothing to fix
locally.

### Step 1 — is the daemon alive?

```bash
# [read-only] loaded? running? since when?
launchctl print gui/$(id -u)/com.zcode.bot-daemon | head -8

# [read-only] all six heartbeats, with ages in seconds
now=$(date +%s); for f in ~/.zcode/bot-kotlin/state/hb-*; do
  echo "$(basename "$f") age=$((now - $(stat -f %m "$f")))s"
done
```

Every `hb-*` must be under ~30–40 s (the beat is 30 s). A single stale
file is a single dead loop, not a dead daemon:

| Stale file | The loop that died | What the owner loses |
|---|---|---|
| `hb-poller` | getUpdates (`poller`) | nothing arrives from the phone at all |
| `hb-channel` | the channel client | `/status` and `/plan` answer 📡; no turn can start |
| `hb-live-view` | the in-place card | the turn runs, but the phone shows no live progress |
| `hb-zconn-shadow` | the model-wire watcher | no ⚠️/✅ notices (the wire may be down unnoticed) |
| `hb-spectator` | the marker scanner | nothing today — nothing writes markers (§6.4) |
| `hb-subagent-notifs` | the 🔔 notifications | no key-moment pings |
| `hb-interventions` | the any-task watcher (or its second channel client) | questions from tasks the owner is NOT reading stop reaching the phone (the selected task is unaffected) |

If they are **all** stale, the JVM is not beating: check the crash noise
and let launchd do its job (it will, `KeepAlive` is true).

```bash
# [read-only] the JVM's own stderr (a boot failure lands here)
# Today it is dominated by JDK native-access WARNINGs — those are noise; a
# stack trace or "Error occurred during initialization of VM" is not.
tail -40 ~/.zcode/bot-kotlin/logs/launchd.err.log

# [read-only] the daemon's last words
tail -3 ~/.zcode/bot-kotlin/logs/daemon.jsonl
```

### Step 2 — is the poller reading the phone?

```bash
# [read-only] the reader's failures/conflicts, newest last
jq -r 'select(.message|test("getUpdates|poller RECOVERED"))
       | ."@timestamp" + " " + .message' ~/.zcode/bot-kotlin/logs/daemon.jsonl | tail -3

# [read-only] the cursor Telegram was confirmed at, and when
cat ~/.zcode/hooks/state/tg-offset
stat -f '%Sm %N' ~/.zcode/hooks/state/tg-offset
```

A `getUpdates failed: ... Conflict: terminated by other getUpdates
request` (HTTP 409) means **a second reader holds the token**. That was
the 30/10 incident (a resurrected Python poller); the fix then was
deleting the CLI hooks (§5.2) and the legacy app bot. The job census in
§5 is how you check for a relapse.

A frozen `tg-offset` while the owner keeps writing means nothing is being
confirmed: either the poller is dead (Step 1) or every cycle fails
(Step 2's log line).

### Step 3 — did the owner's message arrive?

```bash
# [read-only] every accepted update, with the routing decision
jq -r 'select(.message|startswith("route:"))
       | ."@timestamp" + " " + .message' ~/.zcode/bot-kotlin/logs/daemon.jsonl | tail -5
```

The shapes that matter:

- `route: update=<id> ctxWs=<workspace> active=<sid> alive=… running=… open=…`
  — the update arrived and the router looked at the live state;
- `route: text FIRED at <sid> (<reason>)` — the text entered a real
  conversation (this is the healthy line);
- `route: <reason> — text parked (F17)` / `route: … HELD — asking
  empujar/encolar` — the Mac was busy; the text is in the F17 queue
  (`/queue` lists it with 🚀/🗑 taps);
- `route: atomic bind to <sid> REFUSED|TIMED OUT|REJECTED` — the fork
  answered "no"; the reason is the fork's own word;
- `route: text NOT fired — the channel is down` — Step 4.

No `route:` line at all after the owner's message means the update never
reached this process: go back to Step 2.

### Step 4 — is the channel to the app alive?

The channel exists only while the fork's host is deployed. Two lines
resolve it — **the newest one wins**:

```bash
# [read-only] the last UP / bye / DEGRADED
jq -r 'select(.message|test("^channel (UP|bye|DEGRADED)"))
       | ."@timestamp" + " " + .message' ~/.zcode/bot-kotlin/logs/daemon.jsonl | tail -3

# [read-only] the socket file and its owner (the ZCode host)
ls -l ~/.zcode/daemon-local.sock ~/.zcode/daemon-local.token
lsof -nP -U | grep daemon-local
```

- `channel UP: hello_ok v… server pid=… app=… caps=…` — connected. The
  `app=` field is the fork's version: that is how you date a stale fork
  deploy (§8.4).
- `channel DEGRADED: no socket — the fork's host is not deployed —
  reconnect attempt N in …` — the app is closed, still booting, or the
  fork build is not deployed. **Sends keep flowing**, so the bot does not
  go silent; commands that need the app answer honestly (`📡`).
- `channel bye: …` — the session ended; the client reconnects on its own
  exponential policy (1 s → 30 s), and the reconnect is logged as
  `channel DEGRADED` until it succeeds.
- `channel error id=… code=…` — the protocol said no to one request; it
  does not kill the session.

The token file is 0600 and written by the fork's host; never print it.

### Step 5 — did the send leave?

```bash
# [read-only] the last sends that landed (the transport's receipt)
jq -r 'select(.message|startswith("send LIVE"))
       | ."@timestamp" + " " + .message' ~/.zcode/bot-kotlin/logs/daemon.jsonl | tail -3

# [read-only] the queue that touches Telegram, and the 429 window
jq 'length' ~/.zcode/hooks/state/outbox.json
now=$(date +%s); m=$(stat -f %m ~/.zcode/hooks/state/tg-cooldown)
echo "cooldown_mtime=$m now=$now active=$([ "$m" -gt "$now" ] && echo yes || echo no)"
cat ~/.zcode/hooks/state/tg-last-ph      # the newest message id the Mac sent
```

- `send LIVE …: message_id=<id>` — it landed.
- `outbox.json` growing means sends are **parked**, not lost: the
  drain runs every 15 s, 1 msg/s, and stops at a 429 window. A parked
  message is a delay.
- `tg-cooldown`'s **mtime is the expiry epoch** (that is the Python
  contract the transport still shares): `active=yes` means Telegram asked
  the main lane to wait. A firehose split keeps its own
  `tg-cooldown-firehose`; with no split — today's config — there is one
  file and one window.
- `sendMessage failed (attempt N/3)` in the log with a `429` is the
  flood window; with `403` it is a bot the owner never `/start`ed.

### Step 6 — ask the bot itself

The chat is a diagnostic surface, and it never lies about a degrade:

| Command | What a degrade looks like |
|---|---|
| `/status` | `📡 The channel to the app is down — the fork's host is not running?` |
| `/plan_model_effort` | `📡 No pude leer la selección — ¿el fork está deployado con los frames de /plan?` |
| `/tasks` | `📡 The channel is down (…unavailable) — the fork's host is not running?` |
| `/queue` | the parked texts, with 🚀/🗑 per row |
| `/ctx`, `/usage` | `⚠️ No pude leer…` when the host answers a shape the bot cannot parse |
| `/help` | lists the menu this binary registered (21 commands at `fe434b1`) |

The menu is registered with Telegram **once, at boot**
(`telegram menu registered: … — N commands`). A command the owner cannot
see in Telegram's «/» list after a deploy is a boot-time registration
failure, not a missing command.

### Step 7 — cross-check the two watchers

```bash
# [read-only] the healer's view: is the beat fresh, is a deploy in flight, is the job loaded?
~/.local/bin/nu-nightly ~/Projects/zcode-bot-kotlin/scripts/bot-healer.nu --status

# [read-only] has it ever acted?
cat ~/.zcode/bot-kotlin/state/healer.json
cat ~/.zcode/bot-kotlin/logs/healer.log
```

`healer.json` carries `ts, action, hb_age_s, ok, detail` — the last heal
and whether it took. A `detail` with text means the heal itself failed
(usually launchd's refusal, quoted verbatim) and needs hands (§8.1).

## 4. Diagnosing the brain (`agent-brain`)

The brain is a separate launchd job (`com.zcode.agent-brain`) serving
`http://127.0.0.1:8765` from `~/Projects/agent-brain`, with Postgres (and
Redpanda, Prometheus, Grafana) in colima. The bot talks to it for RAG
(`/brain`), memory injection (`memory_inject`) and the bus.

### 4.1 The endpoints, and what each answer means

```bash
# [read-only] liveness + the whole config it is serving (no secret values: password is only "set: true")
curl -sS http://127.0.0.1:8765/health

# [read-only] readiness: migrations applied, embedding dimension, a live embed probe
curl -sS http://127.0.0.1:8765/ready

# [read-only] the bus tailer's whole state
curl -sS http://127.0.0.1:8765/bus/status

# [read-only] corpus and memory inventory
curl -sS http://127.0.0.1:8765/rag/stats
curl -sS http://127.0.0.1:8765/memory/status
```

Live on 2026-11-03 23:05 (abridged; `applied` and the bus counters move
— `applied` read 3 at 22:55 and 4 fifteen minutes later, a migrate
landed in between):

```json
/health  {"status":"ok","version":"0.1.0","config":{"http":"127.0.0.1:8765",
          "pg_url":"jdbc:postgresql://127.0.0.1:5433/agent_brain","pg_password_set":true,
          "embed_provider":"ollama","embed_dim":768,"bus_transport":"file"}}
/ready   {"status":"ok","checks":{"db":{"ok":true,"applied":4,"pending":[],"embedDim":768},
          "embed":{"provider":"ollama","model":"nomic-embed-text","dim":768,"ok":true}}}
/rag/stats    {"documents":17460,"chunks":60441}
/memory/status{"pendingTurns":0,"processedTurns":33,"memories":49,"quarantined":2,"breaker":"Closed"}
```

`/ready` answers **503 with `"ok": false`** when a check fails — the body
is the answer, not the status code alone. `db.applied` / `db.pending` is
the migration truth; `embed.embedDim` vs `embed.expectedEmbedDim` is the
mismatch that makes every insert fail (a new embedding model needs
`make nuke`, and that drops the data — owner's call).

### 4.2 The exposition: 20 families

```bash
# [read-only] how many metric families, and which
curl -sS http://127.0.0.1:8765/metrics | grep -c '^# TYPE'
curl -sS http://127.0.0.1:8765/metrics | grep '^# TYPE'
```

**20 families on 2026-11-03** (the E5 brief said 21; the exposition is
the authority — count it, never assume). `brain_bus_*` (13) covers the
tailer, `brain_rag_*` (2) the corpus, `brain_memory_*` (5) the memories
and the breaker. The full table with the honest-indicator law:
`~/Projects/agent-brain/docs/observability.md`.

An **absent** family is a fact, not a scrape error: it means that surface
never ran in this process's life (e.g. `brain_memory_*` needs the memory
worker, `bot_turns_*` needs a witnessed turn).

The daemon's own exposition is the other half:

```bash
# [read-only] the bot's exposition as it renders today
curl -sS http://127.0.0.1:9102/metrics | grep -c '^# TYPE'
curl -sS http://127.0.0.1:9102/metrics | grep '^# TYPE'
```

Live that is **17 `# TYPE` blocks** covering 14 of the **17 families**
inventoried in `docs/observability.md` (a Micrometer summary renders as
its own `summary` plus a `_max` gauge, so blocks and families are not the
same number). The three absent-on-purpose families seen live are
`bot_brain_failures_total`, `bot_memory_inject_failures_total` and
`bot_zconn_transitions_total`: each exists only once its event happens.
Counting is how you tell "the surface never ran" from "the scrape broke"
— the count is the evidence, not the presence of one line.

### 4.3 The three numbers that diagnose the bus

From `/bus/status`:

| Field | Healthy | What a bad value means |
|---|---|---|
| `lagBytes` | small and not growing (`fileBytes − offset`; 1338 B at 22:55 and 0 at 23:05, both normal) | growing monotonically = the tailer stopped consuming (the loop is stuck, not slow) — `brain_bus_lag_bytes` in Prometheus |
| `heartbeatAgeMs` | under a couple of seconds (the loop polls every `bus_poll_ms`, default 500 ms; 4720 ms and 3559 ms were read in two live samples) | seconds-to-minutes = the tailer loop is wedged while Ktor still answers: `/health` will look fine. `brain_bus_heartbeat_age_seconds` is the honest probe |
| `duplicates` / `inserted` vs `seen` | `duplicates: 0`, `inserted == seen` (149/149 at 22:55, 941/941 at 23:05 — it advances with the live turn) | `duplicates > 0` is a **replay**: the tailer re-read events (offset lost). Not a data-loss bug, but the "cero replay" property is broken and the dashboard's `brain_bus_events_duplicates_total` shows it |

Also read `malformed` (lines the tailer refused) and `shrinks` (the file
got smaller than the offset — a rotation the tailer survived by
re-reading). Both are real events, counted, never swallowed.

### 4.4 The memory breaker

`/memory/status`'s `breaker` is `Closed | Open | HalfOpen`. `Open` means
the embedding provider failed hard enough to trip it; `quarantined` is
the count of attempts kept instead of dropped (nothing is invented away).

The breaker is the **extractor's**: with `pendingTurns == 0` it never
runs, so `Closed` is the honest absence, not a proof of health.

### 4.5 The drills (T7)

Three Nushell drills in `~/Projects/agent-brain/ops/drills/`, each of
which **cuts a real component** (a launchd label, or ollama) and asserts
against `/metrics`, `/ready` and `/bus/status` only (no Grafana):

```bash
# [ACTION] cuts com.zcode.bot-daemon and asserts the scrape is REFUSED,
#          the recovery is a NEW process (counters reset) and bot_zconn_down
#          equals state/zconn.json's own "down"
~/.local/bin/nu-nightly ~/Projects/agent-brain/ops/drills/channel-cut.nu --label com.zcode.bot-daemon

# [ACTION] cuts the brain; /ready and /metrics must be refused, and the tailer
#          must resume from its persisted offset (lag 0, no re-ingest)
~/.local/bin/nu-nightly ~/Projects/agent-brain/ops/drills/bus-cut.nu --label com.zcode.agent-brain

# [ACTION] stops ollama: /ready must say DEGRADED (embed.ok=false) with the
#          real reason, /metrics must keep answering, the breaker opens only
#          if the extractor had work
~/.local/bin/nu-nightly ~/Projects/agent-brain/ops/drills/memory-embedding-cut.nu --base-url http://127.0.0.1:8765
```

They were **not run** for this document (each one cuts a live job; the
brief's rule is that destructive commands are marked, not executed). The
parse gate is read-only and was run for one of them:

```bash
# [read-only] the static gate every .nu script in this ecosystem has
~/.local/bin/nu-nightly -c "nu-check --debug /Users/lattenero/Projects/agent-brain/ops/drills/channel-cut.nu"
# true
```

### 4.6 Prometheus and Grafana

```bash
# [read-only] colima and the four containers
colima status
docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'

# [read-only] the two scrape targets and their health
curl -sS http://127.0.0.1:9090/api/v1/targets | jq -r '.data.activeTargets[] | "\(.labels.job) \(.scrapeUrl) \(.health)"'

# [read-only] both directions of "up"
curl -sS 'http://127.0.0.1:9090/api/v1/query?query=up' | jq -c '.data.result[] | {job: .metric.job, v: .value[1]}'
```

Live: `agent-brain … 8765/metrics up`, `bot … 9102/metrics up`, and both
`up` values `"1"`. Grafana answers `200` on
`http://127.0.0.1:3000/api/health` (anonymous Viewer; the dashboard is
provisioned from `ops/grafana/`).

The stack is **started from a terminal outside ZCode**, never from an
agent session:

```bash
# [ACTION] in ~/Projects/agent-brain, from Ghostty (survives ZCode restarts)
make up      # docker compose up -d --wait + installDist + migrations
make down    # stops the containers, keeps every volume
```

The reason is in `~/.zcode/AGENTS.md`: a `colima start` run from inside a
ZCode session dies with the app's process tree, taking Postgres and
Redpanda with it.

## 5. LaunchAgents and hooks — the inventory (read-only)

**This section was produced without changing anything live.** No
`bootout`, no `disable`, no plist or config edit. States below were read
at 2026-11-03 22:55 with `launchctl list`, `launchctl print` and
`plutil -extract`.

### 5.1 The launchd census

```bash
# [read-only] the whole census in one shot
launchctl list | grep -i zcode

# [read-only] per-label properties, straight from the installed plists
for f in ~/Library/LaunchAgents/com.zcode.*.plist; do
  printf '%-28s ' "$(plutil -extract Label raw "$f")"
  printf 'keepalive=%-5s runatload=%-5s interval=%s\n' \
    "$(plutil -extract KeepAlive raw "$f" 2>/dev/null || echo -)" \
    "$(plutil -extract RunAtLoad raw "$f" 2>/dev/null || echo -)" \
    "$(plutil -extract StartInterval raw "$f" 2>/dev/null || echo -)"
done
```

| Label | Schedule | What it runs | State at 2026-11-03 22:55 | Template |
|---|---|---|---|---|
| `com.zcode.bot-daemon` | `KeepAlive` + `RunAtLoad` | `~/.zcode/bot-daemon/bin/daemon --home ~/.zcode/bot-kotlin` | **running**, pid 63495 | `scripts/com.zcode.bot-daemon.plist` |
| `com.zcode.bot-healer` | every 60 s | `scripts/bot-healer.nu` | loaded, exit 0 | `scripts/com.zcode.bot-healer.plist` |
| `com.zcode.auth-sentinel` | every 30 s | `scripts/auth-sentinel.nu` (SecurityAgent/UserNotificationCenter → Telegram alert, `auth:<pid>` tap consumes `state/auth-dismissed`) | pending first install (2026-11-12) | `scripts/com.zcode.auth-sentinel.plist` |
| `com.zcode.bot-deploy` | one-shot, `RunAtLoad` | `scripts/deploy-daemon.nu` (the `/deploy` spawn) | loaded-and-stopped, exit 0 — **created at runtime** by `DeploySpawn` into `~/.zcode/bot-kotlin/state/deploy-job.plist` | rendered by the daemon, not in `scripts/` |
| `com.zcode.deploy-fork` | manual (`RunAtLoad` **false**) | `deploy-fork-and-retire.nu --real` through a zsh wrapper | loaded, exit 0 | `scripts/com.zcode.deploy-fork.plist` |
| `com.zcode.bot-recorder` | every 60 s | the recorder binary over `~/.zcode/hooks/state` | loaded, exit 0 | `scripts/com.zcode.bot-recorder.plist` |
| `com.zcode.bot-comparator` | every 86400 s + `RunAtLoad` | the comparator, wrapped to always exit 0 | loaded, exit 0 | `scripts/com.zcode.bot-comparator.plist` |
| `com.zcode.beacon` | every 60 s | `scripts/beacon.nu` → healthchecks.io + gist | loaded, exit 0 | `scripts/com.zcode.beacon.plist` |
| `com.zcode.reopen-zcode` | `RunAtLoad` only | `sleep 8 && open -a ZCode && sleep 5 && open -a ZCode` | loaded, exit 0 | none — hand-written in place |
| `com.zcode.agent-brain` | `KeepAlive` + `RunAtLoad`, `ThrottleInterval` 30 | `~/Projects/agent-brain/scripts/serve-brain.nu` | **running**, pid 70721 (last exit 143 = an old SIGTERM) | `~/Projects/agent-brain/scripts/` |
| `com.zcode.agent-brain-index` | manual (both false) | `agent-brain index-repos` | loaded, **last exit 1** — `ClassNotFoundException: dev.lattenero.brain.rag.IndexerKt` | `~/Projects/agent-brain/scripts/` |
| `com.zcode.telegram-cmds` | `Disabled` **true**, not loaded | the retired Python poller | **not loaded** | n/a (retired) |
| `com.zcode.telegram-watch` | `Disabled` **true**, not loaded | the retired Python app watcher | **not loaded** | n/a (retired) |

Two readings that are easy to get wrong:

- **`launchctl list`'s second column is the last exit status, not health.**
  `agent-brain` shows `143` (a SIGTERM from an old reload) while running
  fine; `agent-brain-index` shows `1`, which *is* a real failure.
- **`bot-deploy` has no plist in `scripts/`.** It is rendered at runtime
  into `state/deploy-job.plist` on every `/deploy`; `launchctl print` names
  that path. A `bootout`+`bootstrap` of it is what `/deploy` does — see
  §2.3's error-5 failure mode.

### 5.2 The ZCode hooks — retired, and they must stay retired

```bash
# [read-only] does the CLI config still carry a hooks key?
jq 'has("hooks")' ~/.zcode/cli/config.json
# false — the file has only "mcp" and "plugins"
```

The Python bot's seven hook events are **gone**. That is a deliberate fix:
on 2026-10-30 the hooks resurrected a `watch-commands` process on every
prompt, that process polled getUpdates with the same token as the Kotlin
daemon, and Telegram answered 409 Conflict to both — the bot lost
messages for four hours (`docs/incident-30-10.md`). `enabled: false` was
not honored; deleting the key was. **Do not restore a `hooks` key that
runs a Telegram poller for any bot on this token.**

The Kotlin daemon needs no hooks: it reads the fork's CLI logs, the
channel socket and the state files directly.

`~/.zcode/hooks/` still exists, and it is a mixed bag of live contract
and retired code:

| Path | What it is | Live? |
|---|---|---|
| `state/` | the **shared contract** the Kotlin daemon reads and writes: `outbox.json`, `tg-cooldown`, `tg-last-ph`, `tg-offset`, `bot-session.json`, `bot-workers.json`, `turn-*.marker` (read), `agent-view-*.pid` (read), plus Python-era leftovers (`hb-transport`, `tg-busy`) that nothing here writes | **YES — do not delete** |
| `logs/` | `prompt-worker.log` (frozen 2026-10-30 14:50), `watch-cmds.log` (frozen 2026-10-12), `watch-app.log` (frozen 2026-10-09), `errors.log` | read by the comparator's daily audit — frozen truth (§6.6) |
| `zcode-telegram.py`, `zcode_bot/` (43 entries), `zcode_proto.py`, `__pycache__` | the retired Python implementation | no |
| `venv/` | the retired Python 3.13 venv | no — nothing calls it |
| `bot-provider.json` | **0600, never read, never printed** — the bot's provider key | path only |
| `bin/bot-revive` | the Python-era red button: it kickstarts `com.zcode.telegram-cmds` (retired, `Disabled=true`, not loaded) and waits for `state/hb-transport`, which the Kotlin daemon never writes | **BROKEN — do not use** |
| `bin/install-fork-f81b.sh` | installs a freshly built fork the moment the app quits — an older deploy path, superseded by `~/Projects/ZCode/scripts/build-install.nu` and the `deploy-fork` job (§2.5) | no |
| `capture.sh`, `nodock.sb` | payload-capture debug hook and its sandbox profile | no |
| `backups/` (10 dirs) | deploy backups from 27–28/10 | no |
| `session-brain.nu`, `prompt-laws.nu`, `rag-guard.nu`, `english-law.nu`, `nushell-law.nu`, `nushell-typing.nu`, `session-learn.nu`, `memory-recall.nu`, `prompt-learn.nu` | the **session hooks** registered in `~/.zcode/cli/config.json` (`hooks.events`): brain memory at SessionStart, per-prompt laws, RAG/typing/English guards on Bash/Edit/Write, and since 2026-11-08 the **cross-fork memory loop**: `session-learn.nu` on Stop publishes every session's final reply to `~/.zcode/agent-brain/bus-events.jsonl` (`source=hook`, `kind=turn.completed`, `payload.reply`), `prompt-learn.nu` on UserPromptSubmit publishes the user's question (`kind=user.prompt`), and `memory-recall.nu` (same event) injects the top-3 relevant memories per prompt (`POST /memory/search`, k=3, ≤1500 chars, curl `-m 2` deadline, silence on down/empty) — write and read across ALL sessions, not only the bot-bound one; per-turn logs at `logs/session-learn.jsonl` and `logs/prompt-learn.jsonl` | **YES — fires in new sessions** |

`bin/bot-revive` deserves its own line in the incident book: an emergency
script that would fail *and say so* (`✗ the poller agent would not
kickstart`) while the real recovery sat one command away. The Kotlin
equivalents are §2.2 (`launchctl kickstart`) and §2.4 (`bot-healer.nu
--force`).

## 6. Consolidation proposal — the owner decides the cutover

Nothing here was executed. Each item names the evidence, the step and
its **risk**, in the order I would take them.

### 6.1 The deploy job's loaded residue (the error-5 bug) — highest value

**Evidence.** `com.zcode.deploy-fork` aside, the deploy job
`com.zcode.bot-deploy` is loaded-and-stopped after every successful
`/deploy`. On 2026-11-03 the 14:14 deploy worked
(`deploy job com.zcode.bot-deploy armed at …` + a full build in
`deploy-daemon.log`), then 19:37, 19:37 and 22:45 failed with
`launchctl bootstrap com.zcode.bot-deploy failed: Bootstrap failed: 5:
Input/output error` (daemon log, `DeploySpawn.kt:125`). `DeploySpawn`
ignores the pre-bootstrap `bootout`'s exit code and retries the
bootstrap once after 2 s; the label being *still loaded* is the state
that makes both attempts answer 5.

**Proposed step.** In `DeploySpawn.spawn()`: make the `bootout` verdict
explicit — on a non-zero exit, `print` the job (`launchctl print
gui/<uid>/com.zcode.bot-deploy`) and, when it is loaded, `kickstart` it
instead of `bootstrap`; or retry the bootout with a short backoff and
verify with `launchctl print` (a gone label answers `Could not find
service "<label>" in domain for user gui: <uid>`), then bootstrap.
Cover it with a test that fakes the three-call sequence already recorded
in `DeploySpawnTest`.

**Risk.** Low, but it is the piece that can take the bot down: a wrong
spawn means the deploy never arms the daemon. The change is contained in
one edge class with its own suite, and the failure leg already keeps the
current daemon up and the phone informed. Until it lands, §8.1 is the
manual door.

### 6.2 The two retired Python LaunchAgents

**Evidence.** `com.zcode.telegram-cmds.plist` and
`com.zcode.telegram-watch.plist` are `Disabled=true` and not loaded;
their log files have not been written since 12/10 and 09/10.

**Proposed step.** `launchctl bootout gui/$(id -u)/com.zcode.telegram-cmds`
(and `-watch`) if they ever appear loaded, then move the two plists to
`~/Library/LaunchAgents/disabled/` (a directory launchd does not read).
Keep `retire-python.nu`'s rollback door documented in the README until the
owner decides the Python repo is archival.

**Risk.** Medium-low: a `bootstrap` of either one is the 409 incident
again (§5.2). Removing the *plists* only removes the temptation; it does
not remove the code. The real loss is the rollback path the README still
promises ("relaunch the Python agents and flip `poll` back") — if that
door is still wanted, keep the plists and delete nothing.

### 6.3 The retired Python tree and venv

**Evidence.** `~/.zcode/hooks/zcode_bot/` (43 entries), `venv/`,
`zcode_proto.py`, `zcode-telegram.py`, `__pycache__`, `backups/`,
`capture.sh`, `nodock.sb`. The only script that still points at the
Python tree is `bin/bot-revive` (already broken, §5.2). The Kotlin
daemon touches **only** `state/` (and the comparator reads `logs/`).

**Proposed step.** Move the code (not `state/`, not `logs/`, not
`bot-provider.json`) to an archive directory outside `~/.zcode`, e.g.
`~/.zcode/backup/hooks-retired-2026-11/`, after `bot-revive` is retired
itself; keep `state/` and `logs/` where they are.

**Risk.** Medium: `state/` and `logs/` are load-bearing and sit in the
same directory. A move that takes `state/` with it would deafen the
poller (`tg-offset` is the cursor) and orphan the outbox. Do it with the
daemon stopped and `git`-less mv one subdirectory at a time, verifying
`hb-*` and `tg-offset` afterwards. Also: `bot-provider.json` is 0600 and
must never be copied into a repo.

### 6.4 The spectator orchestrator and the stale turn markers

**Evidence.** `spectator-orchestrator` scans `turn-*.marker` every 2 s
and has started zero turns since 2026-10-30: the only markers present
are three from 29–30/10, and **nothing writes markers any more** — the
Python hooks that did are gone (§5.2), and no ZCode package writes
`state/turn-*.marker`. Its only line in the whole `daemon.jsonl` is
`spectator-orchestrator stopped — shutting down`. It still beats
`hb-spectator`.

**Proposed step.** Either delete the three stale markers and retire the
orchestrator (a wiring change, one commit, `Main.kt`), or keep it and
document it as dormant. Deleting the markers alone is the
no-code-change half.

**Risk.** Low for the deletion (they are inert inputs), but the hazard
that makes it worth doing is not hypothetical — read how thin the gate
is. The scan's inputs today: one of the three markers
(`session_dccc764c…`) **is** the sid in `bot-session.json`, and in LIVE
mode `send.ownsSpectator` is true, so `mac_spectator: false` no longer
blocks. What stops a spawn is (a) `sidIndex` finds no
`model-io-<sid>.jsonl` rollout for it, and (b) `bot-workers.json` —
Python's frozen registry from 30/10 — still lists that sid, so the F15
"its own worker streams it" gate skips it. Two accidents, one of them a
file nobody maintains: if a rollout ever reappears and the registry is
pruned or removed, the spectator would start narrating a six-day-old
turn. Retiring the orchestrator (or just deleting the markers) removes
the hazard and one heartbeat; it also removes the only path that would
narrate a Mac-side turn if markers ever came back. Decide which future
you want.

### 6.5 `com.zcode.reopen-zcode`

**Evidence.** One `zsh -c 'sleep 8 && open -a ZCode && sleep 5 && open -a ZCode'`
at every login, no KeepAlive, no interval. It exists because the app's
own "open at login" was not enough after a reboot (the 30/10 incident's
"cerraba y no abría").

**Proposed step.** Keep it while the Mac's login behavior is what it is;
if the app's own login item is ever made reliable, retiring this is a
one-`bootout`.

**Risk.** Low to remove, **high** if removed at the wrong time: without
the app there is no socket, so the phone can read (`/tasks`, `/status`
answer 📡) but cannot start a turn until someone opens ZCode by hand.
That is exactly the trade this job buys.

### 6.6 The comparator's frozen truth

**Evidence.** `~/.zcode/bot-kotlin/parity/report-2026-11-0{1,2,3}.json`
all read `pythonEvents=2`, `pythonTurns=4`, `verdict=DIVERGENT`: the
Python side of the audit has been frozen since 30/10
(`prompt-worker.log` mtime). The shadow side keeps growing
(`shadowOnly` 165 → 223), so the verdict can no longer change.

**Proposed step.** Declare the comparator post-cutover a **museum
instrument**: keep it running (it costs one JVM a day) or point it at a
live truth (the daemon's own shadow decisions vs the sends it actually
made). Do not "fix" it by deleting the Python logs — they are the last
record of the pre-cutover baseline.

**Risk.** Low to leave as-is; the risk is *misreading* it. A
`DIVERGENT` today is structural, not evidence of a new divergence — the
README says exactly that, and this runbook is where an operator will
look first.

### 6.7 `outbox-firehose.json` — 20 parked entries from 30/10

**Evidence.** `~/.zcode/hooks/state/outbox-firehose.json` holds 20
`{text, ts}` entries from 2026-10-30, and the lane that would read it is
**not built**: `daemon.jsonl` says `firehose lane: OFF (no firehose_token)
— one throat, the shared files`, and `Lanes.kt` constructs no
`fireOutbox` without a split. The file is inert.

**Proposed step.** Archive it (`mv` to `~/.zcode/backup/`) — it is
valuable only as the forensic record of the flood that motivated the
two-lane design (`Lanes.kt`'s header).

**Risk.** Low, with one trap: if a `firehose_token` is ever added to
`telegram-remote.json`, the lane comes alive and would replay those 20
messages. Keeping the file *is* keeping that trap. Owner's call.

### 6.8 `tg-busy` — a Python-era mutex

**Evidence.** `~/.zcode/hooks/state/tg-busy` (17 bytes, mtime 2026-10-25)
is Python's busy flag; the Kotlin daemon uses `live-view-active.json` and
`inbound-queue.json` instead. Nothing in `modules/` reads `tg-busy`.

**Proposed step.** Leave it. It is 17 bytes and deleting it buys nothing;
documenting that it is *not* the current busy flag is worth more.

**Risk.** None.

## 7. Codes — the F-tags, honestly

The F-code namespace belongs to the **retired Python repo**
(`/Users/lattenero/Projects/bot/docs/F-CODES.md`, F0–F86 plus R/D codes).
It is a provenance convention: `# F42: <explanation>` greps and dates a
change. **No behaviour depends on a tag here.** The Kotlin repo cites 27
tags in `modules/`, as history; this table says what the Kotlin code
means by each one and whether the meaning was **carried** unchanged,
**drifted** (reused for a related-but-different law), or **retired**.

Read a tag with its repository — the same label can mean something else
in a sibling repo (`docs/plan-brujula.md` calls agent-brain's anti-while
gate "F1", while Python's F1 was "one single source per concept").

| Tag | What THIS repo means by it | Owner file | Status |
|---|---|---|---|
| F2d | the offset is confirmed **after** dispatch (at-least-once, never at-most-once) | `Poller.kt`, `ChannelFrames.kt` | carried as a law |
| F4d | the live view is OPTIONAL — a failure never breaks anything | `LiveTelegram.kt` | carried as a law |
| F8 / F83 | the **shared cooldown window**: Telegram's 429 names it, its mtime is the expiry, `send()` pre-checks it before spending an attempt, and parking beats burning attempts | `Cooldown.kt`, `Sender.kt` | merged (Python's F8 was the warm worker — retired) |
| F15 | ONE getUpdates reader **and** only the focused task gets a spectator | `SpectatorWatch.kt`, `SpectatorIo.kt` | carried |
| F17 | the explicit park queue — a busy Mac parks, never drops; `/stop`/`/queue` own it | `Routing.kt`, `BotCommands.kt`, `InboundQueue.kt` | carried |
| F20 | turnId correlation rebuilt on the CLI-log path | `SpectatorCore.kt` | carried |
| F22 | `/run`'s store contract: every fired prompt is `/retry`'s ammo; model/mode survive a switch | `BotCommands.kt`, `LastPrompt.kt` | carried |
| F23 | 👀 on receipt, ✅ on handled — reactions answer THE message, zero new messages | `Ack.kt`, `Poller.kt` | carried |
| F30 | queue items written before the tag carry no `ts` — tolerate them | `contracts/StateFormats.kt` | carried as tolerance (the auditor itself is retired) |
| F32 | the turn's FINAL answer is the one that lands; the marker is consumed by it | `SpectatorWatch.kt`, `SpectatorCore.kt` | carried |
| F33 | the rollout-tail spectator and its record reduction | `SpectatorCore.kt` | carried as history (no markers are written — §6.4) |
| F35b | first-cycle fail-fast: a poison update must not crash-loop the cycle | `Poller.kt` | carried |
| F43 | a box has a SIZE; it seals before it overflows | `LiveTurn.kt` | carried |
| F46 | «R.C» labels (round, box); `pre-F46` is the older box/ph vocabulary | `contracts/StateFormats.kt` | carried |
| F48 | `from_owner`'s fail-closed gate: without an owner, NOBODY gets through | `TgConfig.kt` | **drifted** — Python's F48 family was the gateway's reply discipline |
| F50b | the line-count stamp on every file shown (🟢 +N · 🔴 −M · K lines) | `spectator/ToolBox.kt` | carried |
| F55b | numbering on what is SHOWN (patches from `@@`, Write boxes from 1, Edit bare) | `spectator/ToolBox.kt` | carried |
| F57 | one box = one live message (`perbox`), not one message per round | `contracts/StateFormats.kt` | carried |
| F63 | subagents: the task's thread quiet, one line per agent | `SpectatorCore.kt` | carried |
| F67 | a live agent view mutes its parent session's stream (freshness, not existence) | `SpectatorIo.kt` | **drifted** — Python's F67 was the cot-live task-switch gap |
| F75 | the Python reader/writer duality | `contracts/StateFormats.kt` (the comment that retires it) | **retired by owner order** |
| F76 | only the stream focus (or the active sid) may push | `SpectatorIo.kt` | carried |
| F79e | the sync breaker: 5 consecutive failures open it, 2 successes close it | `TgBreaker.kt` | carried |
| F84b | `tg-last-ph`: every `sendMessage` records the chat's newest message id | `LastPh.kt`, `Sender.kt` | carried |
| F85 | the chat hears the wire: one ⚠️/✅ per model-wire episode, a re-fail near a recovery is the same episode | `zconn/ConnTrack.kt` | carried |
| F85c | the retry clock that went deaf because it sat ahead of the clock; the countdown is honest | `zconn/ZconnShadow.kt` (+ `ZconnRetryTest`) | carried (the 1308 lesson) |

**Superseded, with no F-tag carrying the successor:** the actor workers
and their registry (F16 — the fork owns turns now), the nuclear
`killall ZCode` cycle (F4e — `NuclearLadder`), the live-view sidecar
(F4d's original) and the bash-output storm (F86) — the fork's stream
pushes both (`docs/parity-and-engineering.md`'s capability table), the
F79 healer-of-the-healer (the beacon + `bot-healer.nu`), the F81
code-stamp spawns (there are no per-turn processes to spawn).

**Retired with no successor:** the invariant auditor's heals (F30's
actor), the stuck-watch, the CoT-tap deploy dance (F59), the F77
reader/writer modes.

Their rows stay in the Python glossary; there is no equivalence table to
invent here. When a Kotlin comment cites one of them it is dating an
idea, not naming a running mechanism.

## 8. Emergency recipes

Every recipe below is an **ACTION** unless it says otherwise. The
read-only checks that tell you which one to run are in §3 and §4.

### 8.1 The bot is dead (nothing answers, and `/status` never arrives)

**Case A — the daemon job is not loaded at all** (`launchctl print` answers
`Could not find service "com.zcode.bot-daemon"`). A deploy boots the job
out and only then bootstraps it, so a failed deploy leaves exactly this.

```bash
# [read-only] why: the deploy's last words say whether its bootstrap failed
grep -nE "^✗|done\. Live daemon armed" ~/.zcode/bot-kotlin/logs/deploy-daemon.log | tail -5
```

```bash
# [ACTION] re-arm it from the rendered plist (no rebuild)
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.zcode.bot-daemon.plist
launchctl print gui/$(id -u)/com.zcode.bot-daemon | head -8
```

If the bootstrap itself refuses (an unreadable plist, a bad `JAVA_HOME`),
the rebuild path is §2.3.

**Case B — the daemon runs but `/deploy` fails with `Bootstrap failed: 5:
Input/output error`.** The deploy never runs; the bot keeps working and
the phone hears the failure. The deploy job is still loaded from a
previous run (loaded-and-stopped is its normal resting state) and it
blocks a fresh bootstrap.

```bash
# [read-only] is the deploy job still loaded?
launchctl print gui/$(id -u)/com.zcode.bot-deploy | head -6
#   state = not running        <- normal resting state, but it still blocks a bootstrap
```

```bash
# [ACTION] clear the residue, then /deploy again from the phone
launchctl bootout gui/$(id -u)/com.zcode.bot-deploy
launchctl print gui/$(id -u)/com.zcode.bot-deploy
#   Bad request. Could not find service "com.zcode.bot-deploy" in domain for user gui: 501
#   ^ that message is the proof it is gone
```

This recovery was **not exercised** for this document (it changes live
launchd state); the "gone" message above was produced with a
non-existent label, which is the same answer launchd gives.

**Case C — the job is loaded and running but stale.** The healer's own
door is the sanctioned restart:

```bash
# [ACTION] restarts a live daemon (bypasses the three doors)
~/.local/bin/nu-nightly ~/Projects/zcode-bot-kotlin/scripts/bot-healer.nu --force
```

**Case D — the JVM dies on boot.** `launchd.err.log` carries the VM
error; the installed tree is broken — rebuild and reinstall with §2.3.

### 8.2 The channel is down (sends work, turns don't)

`channel DEGRADED: no socket` and/or `/status` answering 📡.

1. Is the app running and is its host up?

```bash
# [read-only] the app's launchd label and the socket's owner
launchctl list | grep application.dev.zcode
lsof -nP -U | grep daemon-local
```

2. **Do not** start/kill the app from an agent session (§2.5). The
   owner's door is `open -a ZCode` **at the Mac**, or `/nuclear` from the
   phone if the app is wedged. `reopen-zcode` only fires at login.

3. If the socket exists and the token exists but `hello` never answers,
   the fork's host is older than the channel protocol
   (`caps=` in the last `channel UP` tells you what it speaks) — §8.4.

4. Sends keep flowing and the poller keeps reading throughout: the
   correct expectation is "the bot answers commands that need no app,
   and says 📡 for the rest".

### 8.3 The outbox is stuck (messages parked, nothing lands)

```bash
# [read-only] how many are parked, and is a 429 window open?
jq 'length' ~/.zcode/hooks/state/outbox.json
now=$(date +%s); m=$(stat -f %m ~/.zcode/hooks/state/tg-cooldown)
echo "active=$([ "$m" -gt "$now" ] && echo yes || echo no)"
```

- `active=yes` — Telegram asked the bot to wait. **Do nothing**: the
  window expires on its own and the drain resumes at 1 msg/s. Touching
  the file to "clear" it burns the flood penalty.
- `active=no` and the depth not moving — the drain Process is dead
  (`outbox-drain` has **no heartbeat file**, by design). Restart the
  daemon (§2.2). The queue is a file: nothing is lost.
- A single 403 in the log means the owner never `/start`ed the bot that
  the lane uses; the message will park forever until they do.

### 8.4 The fork is deployed with an old build

Symptom: `📡 No pude leer la selección — ¿el fork está deployado con los
frames de /plan?`, or a command naming a "fix" the app does not have.

```bash
# [read-only] what the RUNNING host says it is
jq -r 'select(.message|startswith("channel UP")) | .message' \
  ~/.zcode/bot-kotlin/logs/daemon.jsonl | tail -1
# channel UP: hello_ok v1 bot=daemon-local enabled=true server pid=12875 app=3.17.2 caps=22

# [read-only] what the installed app bundle is
defaults read /Applications/ZCode.app/Contents/Info.plist CFBundleShortVersionString

# [read-only] what the checkout is (the deploy builds from here)
git -C ~/Projects/ZCode log -1 --format='%h %s'
```

`app=` is the authority for what is RUNNING; the bundle version and the
checkout tell you what a deploy *would* install. When they disagree, the
fork deploy is the owner's call (§2.5), and the bot keeps saying 📡 in
the meantime — by design, not as a bug.

### 8.5 The brain is up but has no database (colima off)

`/health` may still answer (the HTTP server does not need Postgres), and
`/ready` is the honest one.

```bash
# [read-only] readiness first — a 503 with db.ok=false is the answer
curl -sS -o /tmp/ready.json -w '%{http_code}\n' http://127.0.0.1:8765/ready; cat /tmp/ready.json

# [read-only] the containers and the VM
colima status; docker ps --format '{{.Names}}\t{{.Status}}'
```

- If colima is stopped: **start it from Ghostty, never from a ZCode
  session** (`~/.zcode/AGENTS.md`: the VM is a child of whatever started
  it, and a ZCode restart takes it down). Then, in `~/Projects/agent-brain`:
  `make up` (compose + migrations).
- `serve-brain.nu` already handles the racing case: with Postgres down it
  exits **75** (`EX_TEMPFAIL`) and launchd retries after the
  `ThrottleInterval` of 30 s instead of crash-looping.
- While the brain is down, the bot degrades loudly, not silently:
  `bot_brain_failures_total` / `bot_memory_inject_failures_total` appear,
  and `memory_inject` simply prepends nothing. `/brain` answers its own
  error.

### 8.6 Disk and memory

```bash
# [read-only] the three numbers that matter
df -h /
memory_pressure | tail -2
ps -o pid,rss,etime,comm -p $(launchctl list | awk '$3=="com.zcode.bot-daemon"{print $1}')
```

Measured 2026-11-03: `/` at 1% used, `System-wide memory free
percentage: 32%`, daemon RSS 82 MB after 29 min. The daemon rotates its
log by day (`daemon-YYYY-MM-DD.0.jsonl`); the live dir was 30 MB total.

If the disk is the problem, these are the growth points, in order:
`~/.zcode/bot-kotlin/logs/daemon.jsonl*`, `~/.zcode/bot-recordings/`
(daily traces, content inlined up to 512 KB per file — real prompts:
never put them in a repo), `~/.zcode/hooks/logs/`, and the docker
volumes (`make down` keeps them; `make nuke` drops them — owner's call,
it deletes the brain's data).

### 8.7 Knob rollback (`~/.zcode/bot-kotlin/daemon.json`)

Every knob is read **once at boot**; the file is the deploy, a kickstart
is the apply. Live at 2026-11-03:

```json
{"send":"live","poll":true,"event_bus":true,"memory_inject":true,
 "brain_url":"http://127.0.0.1:8765","metrics":true,"notifications":true}
```

| Knob | Default when absent | OFF means | How to roll back |
|---|---|---|---|
| `send` | `shadow` | `shadow`: nobody sends; `dry-run`: compose+log, no post | see the **warning** below — shadow alone silences the phone |
| `poll` | `false` | the daemon stops reading getUpdates → the phone is **deaf** unless another poller runs | set `true` + kickstart |
| `event_bus` | `false` | no bus appends → the brain's ingest stops hearing the bot | set `true` + kickstart |
| `memory_inject` | `false` | prompts go out without the memory block (no brain call) | set `true` + kickstart |
| `metrics` | `false` | no `/metrics` listener; Grafana's bot panels go `up=0` | set `true` + kickstart |
| `metrics_port` | `9102` | — (read even when OFF, loudly) | — |
| `otel` | `false` | no spans exported | set `true` + `otel_endpoint` + kickstart |
| `notifications` | **`true`** (the inverse house rule: garbage stays ON) | only an explicit `false` silences the 🔔 | remove the key or set `true` |
| `deploy_script` | the repo checkout's `scripts/deploy-daemon.nu` | — | — |
| `brain_url` | `http://127.0.0.1:8765` | — | — |

The apply is always the same, and its proof is in the boot lines:

```bash
# [ACTION] apply a knob change
launchctl kickstart -k gui/$(id -u)/com.zcode.bot-daemon

# [read-only] the boot's own record of what it read
jq -r 'select(.message|test("knob|channel wired|metrics endpoint ON"))
       | ."@timestamp" + " " + .message' ~/.zcode/bot-kotlin/logs/daemon.jsonl | tail -8
```

> **Warning — the README's one-line send rollback is a trap now.**
> The README's rollback (`send: shadow` + `mac_spectator: true` +
> restoring `zconn.json.pre-cutover`) was written for the strangler,
> when Python still spoke. **Python is retired and its agents are
> `Disabled=true`** (§5.1): flipping `send` to `shadow` today means
> *nobody* sends and the phone goes silent. The only safe full rollback
> is the inverse of `retire-python.nu`: relaunch Python's three agents,
> *then* flip `send` to `shadow`. The daemon-only rollbacks (`poll`,
> `event_bus`, `memory_inject`, `metrics`, `otel`, `notifications`) are
> safe on their own — each removes exactly one surface and leaves the
> send path alive.

## 9. What the Python runbook said that no longer holds

The old `docs/runbook.md` is the parity reference and the rollback door;
it is not this system's manual. Every section of it, and its fate:

| Python runbook claim | Here |
|---|---|
| **Update transport: polling or webhook** (ADR-011), `cert.pem` in `~/.zcode/hooks`, `/set_webhook`, `/webhook_info`, `GET :8443/health` | **retired.** The daemon polls only: `getUpdates` with a 3 s long-poll and a bounded retry (`Poller.kt`). There is no webhook path, no certificate and no 8443 listener in this repo. |
| **Runtime: Python 3.13 + its own venv**, `./bootstrap.nu`, pinned `requirements.txt`, rollback to `/usr/bin/python3` | **retired.** One JVM (Corretto 25) under `~/.zcode/bot-daemon`, built by Gradle `installDist`. No venv is installed or read (the old one is inert, §5.2). |
| **The ACTOR model (F16)**: the poller as supervisor, one detached `--run-turn` process per turn, `bot-workers.json` registry, `bot-stop/<sid>`, `bot-q/`, `bot-queue.json`, `bot-nuclear` | **retired.** The fork owns turns; the daemon subscribes to a task over the channel and renders it. The files still exist in `state/` and some are read (`bot-workers.json` as the spectator's skip-list), but nothing spawns or supervises a turn process. |
| **Deploy: `./deploy.nu`, `--rollback` to `~/.zcode/hooks/backups/`, `touch state/reload-now`** | **retired.** The deploy is `scripts/deploy-daemon.nu` (§2.3): build+install+re-arm. There is **no `--rollback` flag**; the rollback is a knob or git + redeploy. `reload-now` no longer exists. |
| **Suite: `pytest -n auto` / `-n 16`, `check_no_duplicates.py`, `selfcheck.py`** | **retired.** The gate is `./gradlew test detekt` (CI: `ci.yml`, Corretto 25, plus the "no manual concurrency" sweep and the installDist smokes). The 2-worker-count protocol died with xdist. |
| **Processes**: `watch-commands`, `watch-app`, `hook`, `--stuck-watch`, `--run-turn` | **retired.** The live process table is §1.1. What survives is the warning: `zcode-host-local-*`, `ZCode` and its `zcode-cli` children are the app's — never kill them from the session (§2.5). |
| **Compact "absorbed" by the app** (ADR-003) | **not ported.** `/compact` here asks the fork through the channel (`BotCommands.kt`); the host's answer frame is the truth. There is no "authoritative pre → truePost" report in this repo, so the old recipe has no equivalent. |
| **`/stop` doesn't halt a phone-only run** (ADR-002) | **replaced.** `/stop` sends `session/stop` over the channel; if the host is down it says so (`📡 … Nothing was stopped.`). There is no phone-only run to explain: a run is the app's. |
| **`/nuclear` = `/stop` markers → SIGTERM the registry pids → `killall -TERM ZCode` → `open -a ZCode` → bot kickstart** | **replaced.** `NuclearLadder` (§2.5): stopAll through the channel, SIGTERM→SIGKILL the CLI runners (external `ps`, comm exact), `launchctl bootout gui/<uid>/application.dev.zcode.*` (the only real kill — `killall` alone gets resurrected by launchd), `open -a ZCode`, wait for `hello_ok`, then `launchctl kickstart -k` the daemon. Two-step confirm and single-flight (`/nuclear` → the ☢️ tap / `/nuclear go`). |
| **Network down → messages fall into the outbox and resend on their own** | **carried.** Same files (`outbox.json`, `tg-cooldown`, `tg-last-ph`), same behaviour, now drained by the daemon every 15 s (§3 Step 5). |
| **Config: `~/.zcode/telegram-remote.json`, `~/.zcode/hooks/bot-provider.json`, hooks in `~/.zcode/cli/config.json` (7 events), `/etc/hosts`** | **partial.** The first two are carried (paths only — never print either). The CLI hooks are **gone on purpose** (§5.2). `/etc/hosts`/IPv6 is a host-level workaround, not part of this bot's config; the beacon's `getMe` (Step 0) is the honest test of Telegram reachability. |

One doc-level discrepancy worth knowing while reading other files: the
README's build section still says the local gate needs a Java 21
override ("detekt 1.23 cannot run on a daemon >= 24") and that "CI runs
plain (Corretto 21)". Neither is true at `fe434b1`: `ci.yml` uses
Corretto **25**, the detekt 2.0 upgrade removed the daemon-version
constraint, and `~/.gradle/gradle.properties` pins
`org.gradle.java.home` to Corretto 25. `./gradlew test detekt` is the
whole gate, and the deploy script (§2.3) is the authority on what runs
locally.

## 10. Evidence — the read-only commands, with their real output

Executed 2026-11-03 between 22:50 and 23:10 (local). Abridged where the
output is long; nothing sensitive is reproduced.

**The census (§5.1).**

```
$ launchctl list | grep -i zcode
-	0	com.zcode.reopen-zcode
63495	0	com.zcode.bot-daemon
-	0	com.zcode.bot-recorder
-	0	com.zcode.deploy-fork
-	0	com.zcode.beacon
-	0	com.zcode.bot-comparator
-	0	com.zcode.bot-healer
12822	0	application.dev.zcode.app.34889990.34893168
-	0	com.zcode.bot-deploy
-	1	com.zcode.agent-brain-index
70721	143	com.zcode.agent-brain
```

**The daemon and the deploy job (§2.1, §2.3).**

```
$ launchctl print gui/501/com.zcode.bot-daemon | head -8
gui/501/com.zcode.bot-daemon = {
	active count = 1
	path = /Users/lattenero/Library/LaunchAgents/com.zcode.bot-daemon.plist
	type = LaunchAgent
	state = running
	program = /Users/lattenero/.zcode/bot-daemon/bin/daemon
	arguments = {

$ launchctl print gui/501/com.zcode.bot-deploy | head -10
gui/501/com.zcode.bot-deploy = {
	active count = 0
	path = /Users/lattenero/.zcode/bot-kotlin/state/deploy-job.plist
	type = LaunchAgent
	state = not running
	program = /Users/lattenero/Projects/zcode-bot-kotlin/scripts/deploy-daemon.nu
```

**The heartbeats (§1.2, §3 Step 1).**

```
$ now=$(date +%s); for f in ~/.zcode/bot-kotlin/state/hb-*; do echo "$(basename "$f") age=$((now - $(stat -f %m "$f")))s"; done
hb-channel age=2s
hb-live-view age=2s
hb-poller age=2s
hb-spectator age=2s
hb-subagent-notifs age=2s
hb-zconn-shadow age=2s
```

**The healer's own view (§2.4).**

```
$ nu-nightly scripts/bot-healer.nu --status
{"hb":"/Users/lattenero/.zcode/bot-kotlin/state/hb-channel","hb_age_s":2,
 "deploy_in_flight":false,"loaded":true,"since_last_heal_s":50816,
 "plan":{"act":false,"why":"heartbeat fresca: 2s","action":"none","quiet":true}}

$ nu-nightly scripts/bot-healer.nu --dry-run
heartbeat fresca: 2s
```

**The deploy's own words (§2.3, §8.1).**

```
$ tail -6 ~/.zcode/bot-kotlin/logs/deploy-daemon.log
==> verification: first heartbeat + armed state (deadline 15s)
[deploy] heartbeat: Nov  3 14:14:48 2026
[deploy] state: {"down":false,"first_down":1.79371439541E9,"last_up":1.793726016106E9,
  "reason":"Provider rejected the model request.","since":1.793726016606E9,"announced_retry":null}
[deploy] log tail: {… "message":"channel UP: hello_ok v1 bot=daemon-local … app=3.17.2 caps=22" …}
done. Live daemon armed: channel + getUpdates poller + send (daemon.json is the knob).
Health: ~/.zcode/bot-kotlin/state/hb-channel · hb-poller · hb-zconn-shadow; log: logs/daemon.jsonl.

$ jq -r 'select(.message|test("deploy")) | ."@timestamp"+" "+.["message"][0:110]' daemon.jsonl | tail -4
2026-11-03T14:14:34 deploy: com.zcode.bot-deploy bootstrapped as its OWN job (survives this daemon's bootout)
2026-11-03T19:37:27 deploy: the spawn FAILED — this daemon stays up
2026-11-03T19:37:58 deploy: the spawn FAILED — this daemon stays up
2026-11-03T22:45:53 deploy: the spawn FAILED — this daemon stays up
```

**The silent-bot ladder (§3).**

```
$ tail -1 ~/.zcode/bot-kotlin/logs/beacon.log
2026-11-03T22:59:47-03:00 ok epoch=1793757583 daemon_age_s=4 poll_fail_recent=false telegram_probe=ok

$ jq -r 'select(.message|test("^channel (UP|bye|DEGRADED)")) | ."@timestamp"+" "+.message' daemon.jsonl | tail -2
2026-11-03T21:46:31 channel UP: hello_ok v1 bot=daemon-local enabled=true server pid=12875 app=3.17.2 caps=22
2026-11-03T22:34:43 channel UP: hello_ok v1 bot=daemon-local enabled=true server pid=12875 app=3.17.2 caps=22

$ jq -r 'select(.message|startswith("route:")) | ."@timestamp"+" "+.message' daemon.jsonl | tail -2
2026-11-03T22:59:35 route: update=770337517 ctxWs=zcode-bot-kotlin active=session_2d5c… alive=true running=0 open=session_2d5c… (zcode…)
2026-11-03T22:59:35 route: text FIRED at session_2d5c… (already aimed at the open conversation)

$ cat ~/.zcode/hooks/state/tg-offset && stat -f '%Sm %N' ~/.zcode/hooks/state/tg-offset
770337518
Nov  3 23:01:08 2026 /Users/lattenero/.zcode/hooks/state/tg-offset

$ jq 'length' ~/.zcode/hooks/state/outbox.json
0
$ now=$(date +%s); m=$(stat -f %m ~/.zcode/hooks/state/tg-cooldown); echo "active=$([ "$m" -gt "$now" ] && echo yes || echo no)"
active=no

$ ls -l ~/.zcode/daemon-local.sock ~/.zcode/daemon-local.token
srw-------@ 1 lattenero  staff   0 Nov  3 13:14 /Users/lattenero/.zcode/daemon-local.sock
-rw-------@ 1 lattenero  staff  65 Nov  3 13:14 /Users/lattenero/.zcode/daemon-local.token

$ lsof -nP -U | grep daemon-local
ZCode\x20 12875 lattenero   29u  unix 0xa2672734442946df      0t0 /Users/lattenero/.zcode/daemon-local.sock
ZCode\x20 12875 lattenero   41u  unix 0xf6c8507244de3382      0t0 /Users/lattenero/.zcode/daemon-local.sock
```

**The daemon's boot record (§1.1, §8.7).**

```
$ jq -r 'select(.message|test("knob|channel wired|telegram menu")) | .message' daemon.jsonl | tail -8
send knob: mode=LIVE (file=/Users/lattenero/.zcode/bot-kotlin/daemon.json)
poll knob: on=true (this daemon OWNS getUpdates)
event_bus knob: on=true (file=/Users/lattenero/.zcode/bot-kotlin/daemon.json)
memory_inject knob: on=true (file=/Users/lattenero/.zcode/bot-kotlin/daemon.json)
metrics knob: on=true port=9102 (file=/Users/lattenero/.zcode/bot-kotlin/daemon.json)
otel knob: on=false endpoint=http://127.0.0.1:4318/v1/traces (file=/Users/lattenero/.zcode/bot-kotlin/daemon.json)
channel wired: client=on socket=/Users/lattenero/.zcode/daemon-local.sock liveView=on poller=ON
telegram menu registered: /tasks /all_tasks /status /plan_model_effort /mode /last /compact /context_window /queue /run /steer /stop /nuclear /quota /usage /ctx /subagents /verbosity /brain /memory /help — 21 commands
```

**The brain (§4).**

```
$ curl -sS http://127.0.0.1:8765/health | jq -c '{status,version,http:.config.http,pg:.config.pg_url,pw_set:.config.pg_password_set}'
{"status":"ok","version":"0.1.0","http":"127.0.0.1:8765","pg":"jdbc:postgresql://127.0.0.1:5433/agent_brain","pw_set":true}

$ curl -sS http://127.0.0.1:8765/ready | jq -c '{status,db:.checks.db.ok,applied:.checks.db.applied,embed:.checks.embed.ok,dim:.checks.embed.dim}'
{"status":"ok","db":true,"applied":4,"embed":true,"dim":768}
# (the same command read applied:3 at 22:55 — a migrate landed in between)

$ curl -sS http://127.0.0.1:8765/bus/status | jq -c '{lagBytes,heartbeatAgeMs,seen,inserted,duplicates,malformed,restarts}'
{"lagBytes":0,"heartbeatAgeMs":3559,"seen":941,"inserted":941,"duplicates":0,"malformed":0,"restarts":0}
# (at 22:55 the same command read lagBytes:1338, seen/inserted 149/149 — the
#  counters advance with the live turn; what must not move is duplicates)

$ curl -sS http://127.0.0.1:8765/rag/stats | jq -c '{documents,chunks}'
{"documents":17460,"chunks":60441}

$ curl -sS http://127.0.0.1:8765/memory/status | jq -c '{pendingTurns,processedTurns,memories,quarantined,breaker}'
{"pendingTurns":0,"processedTurns":33,"memories":49,"quarantined":2,"breaker":"Closed"}

$ curl -sS http://127.0.0.1:8765/metrics | grep -c '^# TYPE'
20

$ curl -sS http://127.0.0.1:9102/metrics | grep -c '^# TYPE'
17

$ lsof -nP -iTCP:8765 -sTCP:LISTEN | tail -1
java    70721 lattenero   82u  IPv6 … TCP 127.0.0.1:8765 (LISTEN)

$ lsof -nP -iTCP:9102 -sTCP:LISTEN | tail -1
java    63495 lattenero   68u  IPv6 … TCP 127.0.0.1:9102 (LISTEN)
```

**Prometheus and Grafana (§4.6).**

```
$ colima status
colima is running using macOS Virtualization.Framework (arch: aarch64, runtime: docker)

$ docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}'
agent-brain-grafana	Up 59 minutes (healthy)	127.0.0.1:3000->3000/tcp
agent-brain-prometheus	Up 59 minutes (healthy)	127.0.0.1:9090->9090/tcp
agent-brain-pg	Up About an hour (healthy)	127.0.0.1:5433->5432/tcp
agent-brain-redpanda	Up About an hour (healthy)	8081-8082/tcp, 9092/tcp, 9644/tcp, 127.0.0.1:19092->19092/tcp

$ curl -sS http://127.0.0.1:9090/api/v1/targets | jq -r '.data.activeTargets[] | "\(.labels.job) \(.scrapeUrl) \(.health)"'
agent-brain http://host.docker.internal:8765/metrics up
bot http://host.docker.internal:9102/metrics up

$ curl -sS 'http://127.0.0.1:9090/api/v1/query?query=up' | jq -c '.data.result[] | {job:.metric.job,v:.value[1]}'
{"job":"agent-brain","v":"1"}
{"job":"bot","v":"1"}

$ curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3000/api/health
200
```

**The parse gates (§4.5).**

```
$ nu-nightly -c "nu-check --debug scripts/bot-healer.nu"                      -> true
$ nu-nightly -c "nu-check --debug scripts/deploy-daemon.nu"                   -> true
$ nu-nightly -c "nu-check --debug ~/Projects/agent-brain/ops/drills/channel-cut.nu" -> true
```

**The hooks are gone (§5.2).**

```
$ jq 'has("hooks")' ~/.zcode/cli/config.json
false
$ jq -r 'keys[]' ~/.zcode/cli/config.json
mcp
plugins

$ stat -f '%Sm %N' ~/.zcode/hooks/logs/prompt-worker.log ~/.zcode/hooks/logs/watch-cmds.log
Oct 30 14:50:26 2026 /Users/lattenero/.zcode/hooks/logs/prompt-worker.log
Oct 12 13:12:27 2026 /Users/lattenero/.zcode/hooks/logs/watch-cmds.log
```

**The dormant spectator (§6.4).**

```
$ ls -lat ~/.zcode/hooks/state/*.marker | head -3
Oct 30 13:03 turn-session_dccc764c…marker
Oct 30 12:36 turn-session_b291a524…marker
Oct 29 18:36 turn-session_0f84de0c…marker

$ jq -r 'select(.message|test("spectator")) | .message' daemon.jsonl | tail -1
spectator-orchestrator stopped — shutting down
```

**The comparator's frozen baseline (§6.6).**

```
$ for f in report-2026-11-0*.json; do echo "$f -> $(jq -r .verdict $f) (pythonEvents=$(jq -r .zconn.pythonEvents $f), pythonTurns=$(jq -r .spectator.pythonTurns $f))"; done
report-2026-11-01.json -> DIVERGENT (pythonEvents=2, pythonTurns=4)
report-2026-11-02.json -> DIVERGENT (pythonEvents=2, pythonTurns=4)
report-2026-11-03.json -> DIVERGENT (pythonEvents=2, pythonTurns=4)
```

**The inert firehose outbox (§6.7).**

```
$ jq -r 'select(.message|test("firehose lane")) | .message' daemon.jsonl | tail -1
firehose lane: OFF (no firehose_token) — one throat, the shared files, exactly as before

$ python3 -I -c "import json,os;print(len(json.load(open(os.path.expanduser('~/.zcode/hooks/state/outbox-firehose.json')))))"
20
```

**Disk and memory (§8.6).**

```
$ df -h / | tail -1
/dev/disk3s1s1   1.8Ti    13Gi   1.6Ti     1%    484k   4.3G    0%   /

$ memory_pressure | tail -1
System-wide memory free percentage: 32%

$ ps -o pid,rss,etime,comm -p 63495 | tail -1
63495  82576  28:53 /Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home/bin/java

$ du -sh ~/.zcode/bot-kotlin/logs ~/.zcode/bot-recordings ~/.zcode/hooks/logs
 30M	/Users/lattenero/.zcode/bot-kotlin/logs
 13M	/Users/lattenero/.zcode/bot-recordings
7.0M	/Users/lattenero/.zcode/hooks/logs
```

**Not executed, by rule:** the T7 drills (§4.5), `bot-healer.nu --force`
(§2.4), every `launchctl kickstart|bootout|bootstrap` (§2.2, §8.1), the
deploy scripts (§2.3, §2.5), `make up|down|nuke` (§4.6, §8.5), and the
app's own restart (§8.2). Each is marked **ACTION** where it appears.

## 11. The brain as memory — verification, evals, and the mem0/Letta question (2026-11-09)

§4 answers "is the brain **up**". This section answers "is it **right**",
how its memory loop actually runs, why we did not adopt mem0/Letta/Zep,
how the evals are composed, and where the fork harness landed. Sources:
the agent-brain source inventory (file refs inline), the 2026-08 web
comparison of open-source memory layers, and live probes run on
2026-11-09 against `http://127.0.0.1:8765`.

### 11.1 The proof ladder — from "up" to "right"

| Layer | What proves it | Where |
|---|---|---|
| 0. process | launchd `state = running` + fresh heartbeat | §2.2 pattern, `com.zcode.agent-brain` |
| 1. dependencies | `/ready`: migrations applied/pending, stored vs expected `embed_dim`, a **live embed probe** (503 body is the answer) | §4.1 |
| 2. ingestion | `/bus/status` three numbers: lag small, heartbeat <2 s, `duplicates == 0` | §4.3 |
| 3. inventory | `/rag/stats` + `/memory/status`: chunks/memories counts, pending 0, quarantine (kept failures, never silent) | §4.1 |
| 4. equivalence | the ranking the Kotlin code reasons about IS the ranking SQL runs — pinned by `RagStoreRrfEquivalenceIT` / `MemorySearchEquivalenceIT` (pure-core twins `Rrf.kt`, `Salience.kt` with law tests) | agent-brain `rag/`, `memory/` |
| 5. quality gates | the eval suites (§11.4): recall 0.9 / extraction 0.6 / grounded 0.8, run in CI on every push | `evals/suites/*.json` |
| 6. live recall | the two read-only probes below — the machine answers a real question correctly, today | `POST /rag/query`, `POST /memory/search` |

Live on 2026-11-09 (all read-only): `/ready` ok (applied 4, embedDim 768,
probe "embedded 1 probe(s)"); bus lag 236 B, heartbeat 1.4 s, seen ==
inserted == 1117, duplicates 0, malformed 0; 18,733 documents / 63,711
chunks; 1,160 memories (693 facts / 200 decisions / 153 preferences),
pendingTurns 0, processedTurns 285, breaker Closed; eval ledger
`recall-core` 20/20 at gate 0.9 (p50 18 ms). A live `POST /rag/query`
for "nushell single quoted strings escapes" returned the exact 0.113
release-notes chunk on escape handling; a live `/memory/search` for the
whatsdigest mix-law returned the ranked decision memory (id 1111) as
the top hit.

The MCP layer is deliberately thin and is not a second brain: 4
read-only tools (`rag_query`, `memory_search`, `memory_recent`,
`bus_status`), one response envelope with trace ids, loopback + bearer
fail-closed (the V10 red-team drill: 10 PASS / 0 FAIL), and the plugin
(`dev-agent-brain`) is a facade over the same HTTP API §4 curls.

### 11.2 The memory loop, end to end

Every ZCode session (fork or CLI) participates automatically:

```
UserPromptSubmit → prompt-learn.nu ─┐        user.prompt
Stop             → session-learn.nu ┴→ ~/.zcode/agent-brain/bus-events.jsonl
                                          │ tailer (file bus, offset persisted)
                                          ▼
                                    bus_events (PG, dedup by producer id)
                                          │ memory worker (pending, attempts < 5)
                                          ▼
                          extractor (qwen2.5:1.5b, local) → schema
                          {facts, decisions, preferences, summary}
                                          │ invalid shape → memory_quarantine (never silent)
                                          ▼
                          embed nomic-embed-text 768 → memories
                          dedup 0.90 cosine (measured gap: true dupes
                          0.9123–0.9845 vs strongest merely-related 0.8789;
                          survivor's salience reinforced)
                                          ▼
recall: memory-recall.nu injects top-3 per prompt (k=3, ≤1500 chars,
2 s deadline, silent-fail) — same fusion as /memory/search
```

**Ranking law (the 8/11 fix):** `RankKey = (rrf DESC, weight DESC, id)`
— fused relevance is the DOMINANT key; `weight = salience × 2^(-age/halfLife)`
only breaks exact relevance ties. Until 8/11, reinforced veterans could
outrank fresh relevant matches (measured defect, fixed in `Salience.kt`,
laws in `SalienceLawsTest`).

### 11.3 Why not mem0 / Letta / Zep

The three open-source leaders bet on three incompatible ideas of memory
(Aug-2026 comparison, digitalapplied.com; vendor papers):

- **Mem0** — passive extraction: an LLM pulls candidate facts per message
  pair, then a second pass reconciles each against the store
  (ADD/UPDATE/DELETE/NOOP). The nearest cousin of our extractor+dedup;
  graph memory sits on its top hosted tier.
- **Letta (MemGPT)** — an agent RUNTIME with memory tiers the agent edits
  itself (+ "sleep-time compute": a second async agent). Adopting it means
  handing over the agent loop — ours already belongs to the fork and the
  sessions.
- **Zep / Graphiti** — a temporal knowledge graph: facts carry
  `valid_at`/`invalid_at` and are invalidated, not deleted. Strongest when
  facts mutate with auditable history; self-hosting means Graphiti **plus**
  a graph DB (Neo4j/FalkorDB) — three systems to operate.

**Benchmark honesty:** every headline number in that space is
self-reported (Mem0 claims 94.4 on LongMemEval; the one independent
measurement found 49.0 — a 45.4-point gap under the same benchmark
name). The comparison's own conclusion — the only trustworthy eval is
the one you run on your own data — is exactly what our suites (§11.4)
do, with gates pinned in-repo.

**Why ours won (and stays):**

1. the data already lives in **our** Postgres — pgvector + FTS + RRF is
   the industry-standard hybrid pattern, not a niche bet;
2. laws as code: property tests + SQL-equivalence ITs pin the ranking —
   a vendor SDK's reconcile ops are a black box by comparison;
3. honest failures: quarantine keeps what extraction failed on, nothing
   is invented away or silently dropped;
4. zero new runtime: mem0 is a Python/TS SDK, Letta a Python server,
   Zep a graph DB — none of that exists in this Kotlin/kap stack; the
   loopback HTTP facade stays;
5. write-path cost is a local 1.5B model (qwen2.5:1.5b), not a per-pair
   hosted-LLM bill that grows with conversation volume.

**When we WOULD adopt:** entity graphs + temporal validity at scale, or
cross-machine shared memory — evaluate Zep/Graphiti then. The memory
layer sits behind `MemoryStore`; the swap is one module, not a rewrite.

### 11.4 The evals — how they are composed

Suites are versioned JSON in `evals/suites/`, copied into the jar.
Gate math: `ok = cases.isNotEmpty() && passed/total >= gate`
(`EvalRunner.kt`). Persisted per (suite, model) in `eval_runs`, ledger
at `GET /eval/latest` (`?matrix=true` for the matrix run).

| Suite | Kind | Gate | Cases | A pass means |
|---|---|---|---|---|
| `recall-core` | recall | 0.9 | 20 (10 literal + 10 paraphrase) | the expected doc is in the top-5 hybrid hits; `needles` verify the top hit actually supports the answer |
| `extraction-core` | extraction | 0.6 | 6 turns | the schema-valid extraction carries the expected durable items and invents nothing on trivial turns |
| `grounded-citation` | grounded | 0.8 | 5 questions | the answer parses as `{answer, citations}`, cites a chunk of the expected doc AND states the fact |

How they run: `make eval` is the CI gate (≈1.5 s, one model — runs on
every push and nightly); `make eval-matrix` runs ≥2 embedding models
into `evals/artifacts/latest-matrix.{md,json}` and **reports** instead
of gating unless `--strict`; `promptfoo` re-asks the recall questions as
an external cross-check in the nightly workflow. Metrics per run: p50/p95
latency and real token counts priced via `evals/prices.json`. Tier 5
(`e2e-real`, the real Telegram bot over MTProto) is wired into a nightly
workflow, informational, waiting the owner's one-time login.

Test-count correction for future readers: agent-brain carries **352
`@Test` methods** across its five-tier pyramid (ADR-006; property/law
tests plus SQL-equivalence ITs; a meta-test fails the build if any
`@Test` returns non-Unit). The "888 tests" figure quoted in older notes
belongs to zcode-bot-kotlin's parity batch — do not misattribute it.

### 11.5 The fork harness — where it landed (2026-11-09)

- Branch `damian` @ `fcf32671`, clean tree; installed app **v3.17.2**
  (the authority is the running host: `channel UP … app=3.17.2`, §8.4);
  upstream merges landed through v3.17.2 and the FORK.md ledger is
  closed (tag `30338eaf`).
- What the harness IS (the phone-facing surface the daemon consumes):
  channel protocol v1 (`caps=22`) on `~/.zcode/daemon-local.sock`; the
  one-tap `/deploy` (the `com.zcode.bot-deploy` sibling job, §2.3); the
  live card (edit-in-place, 5 s pings, in_progress fold); tasks taps
  confirmed by read-back; the plan draft triple written whole on ✅;
  `/mode` 4 rows; rich Telegram messages (pullquote / expandable
  blockquote); the destructive-tap owner gate; `/steer` into a running
  turn.
- Fork-side discipline: tests via `vitest.fork.config.ts`
  (`forkTestFiles`); deploy = `build-install --when-quit` through
  `com.zcode.deploy-fork` — owner-run, never from a session (§2.5);
  ONE deploy per change (the 4/11 lesson).

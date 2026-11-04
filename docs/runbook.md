# Runbook — operating the Kotlin bot

The operational manual of **this** system, ported from the retired Python
bot's `docs/runbook.md` (2026-11-03, U6/E5). It is not a copy: every
recipe below was verified against the code in this repo and, where it is
read-only, executed against the live machine. Python-era recipes that no
longer exist are listed in §9 with what replaced them — or with
"nothing".

**Verified against:** `master` `fe434b1`, 2026-11-03 22:50–23:20
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
| `outbox-drain` | every 15 s | none | paces both lanes' parked messages out of `outbox*.json`, 1 msg/s |

Sources: `modules/daemon/.../Main.kt` (the wiring), `modules/channel/.../ChannelClient.kt`,
`Poller.kt`, `LivePublisher.kt`; the heartbeat constants at the bottom of `Main.kt`.

### 1.2 Heartbeats and what they prove — read this before trusting one

All six `hb-*` files are **mtimes touched by KAP's heartbeat**, which beats
every 30 s *while the process is alive — including during the body's
execution and during restart delays* (`kap-resilience/.../Process.kt`).

That is the honest reading:

- a **fresh** `hb-*` proves the process **loop** is alive;
- it does **not** prove the work is succeeding. A channel that can never
  connect still beats: the reconnect lives inside the body as a retry.

So `hb-channel` fresh + `channel DEGRADED: no socket` in the log is the
normal, correct picture of "the app is closed, sends keep flowing". The
healer (§2.3) exists to catch the other case: the loop itself died.

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
   (the Gradle daemon on 25; detekt needs < 24 — see the README) and
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
failed: 5: Input/output error`.** Measured 2026-11-03 at 19:37 and 22:45:
the label was still loaded from an earlier run (loaded-and-stopped is its
normal resting state), the pre-bootstrap `bootout` failed, and both
bootstrap attempts — the retry included — answered `error 5`. The daemon
stays up and the phone hears the failure; the deploy never runs. The
recovery is in §8.1.

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
Pythons' leftovers → final announce. `--real` quits the app, so the
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
step names what it *proves*, not just what it prints. All five are
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

The token file is 0600 and belongs to the plugin; never print it.

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
  contract the transport still shares): `active=yes` means Telegram
  asked the bot to wait, and every lane honors it.
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

Live on 2026-11-03 22:55 (abridged):

```json
/health  {"status":"ok","version":"0.1.0","config":{"http":"127.0.0.1:8765",
          "pg_url":"jdbc:postgresql://127.0.0.1:5433/agent_brain","pg_password_set":true,
          "embed_provider":"ollama","embed_dim":768,"bus_transport":"file"}}
/ready   {"status":"ok","checks":{"db":{"ok":true,"applied":3,"pending":[],"embedDim":768},
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
| `lagBytes` | small and not growing (`fileBytes − offset`, ~1–2 KB at rest) | growing monotonically = the tailer stopped consuming (the loop is stuck, not slow) — `brain_bus_lag_bytes` in Prometheus |
| `heartbeatAgeMs` | under a couple of seconds (the loop polls every `bus_poll_ms`, default 500 ms) | seconds-to-minutes = the tailer loop is wedged while Ktor still answers: `/health` will look fine. `brain_bus_heartbeat_age_seconds` is the honest probe |
| `duplicates` / `inserted` vs `seen` | `duplicates: 0`, `inserted == seen` (149/149 live) | `duplicates > 0` is a **replay**: the tailer re-read events (offset lost). Not a data-loss bug, but the "cero replay" property is broken and the dashboard's `brain_bus_events_duplicates_total` shows it |

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
which **kills a real job** and asserts against `/metrics`, `/ready` and
`/bus/status` only (no Grafana):

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

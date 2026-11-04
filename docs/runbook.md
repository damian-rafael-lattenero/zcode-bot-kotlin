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

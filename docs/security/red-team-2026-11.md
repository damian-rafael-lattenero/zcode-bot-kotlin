# Red team — the Kotlin bot's surfaces (U9)

Date: 2026-11-03 · Owner-authorized red team of OUR OWN surfaces (plan item U9).
Base: `master` @ `8233ffb` · Work: branch `agent/u9-redteam`
(`/Users/lattenero/Projects/.worktrees/zcode-bot-u9`) · Repo: `zcode-bot-kotlin`.

> **Scope and ethics.** Only surfaces the owner owns: the Telegram bot, its
> daemon, the daemon-local socket, the repo's scripts and the local state.
> No third party was touched, no anti-bot control was evaded, nothing was
> exfiltrated, and **no secret value appears in this document or in the
> repo** — findings name paths and types, never values. Anything that would
> have needed a change to the owner's live configuration is marked
> `[ACTION]` and was not executed.

## 1. Verdict

The bot's trust model is small and it held up: **the Telegram account is the
single credential, and every path from that credential to the Mac runs
through one fail-closed owner filter.** No vector let a non-owner execute a
command, and no vector let third-party text become executable markup or
instructions. Three real defects were found and fixed (one of them a genuine
authorization hazard: the update cursor doubled as an anti-replay control),
and a handful of risks are documented as accepted by design.

| # | Vector | Verdict | Severity |
|---|--------|---------|----------|
| V1 | Owner gate: messages, button taps, attachments | **PASS** (no bypass found) | — |
| V2 | Third-party content → Telegram HTML (prompt/markup injection) | **PASS** after hardening; escaping verified on every renderer | — |
| V3 | `memory_inject` block (indirect prompt injection) | **MITIGATED** (F3, framing) | Low |
| V4 | `tg-offset` cursor loss → replay of old commands | **FIXED** (F1) | **Medium** |
| V5 | Socket/token file permissions (B3a) | **PASS** | — |
| V6 | Socket auth: bad token / malformed frames | **PASS** | — |
| V7 | Secrets: repo, git history, live logs, contract files | **FIXED** (F2: heartbeat URL in argv) + PASS elsewhere | Low |
| V8 | Heartbeat URL in `curl` argv (`beacon.nu`) | **FIXED** (F2) | Low |
| V9 | Secret-bearing config files owner-only | **PASS** | — |
| V10 | MCP surface (U8) | **PENDING** — not built yet | — |

Findings: **F1** (Medium, fixed), **F2** (Low, fixed), **F3** (Low,
mitigated), plus accepted-by-design risks in §5.

## 2. Findings

### F1 — A lost `tg-offset` replayed up to 24 h of updates, re-running `/nuclear go` (Medium, FIXED)

**What.** The poller's cursor is an authorization control in disguise:
`getUpdates(offset)` with `offset = 0` asks Telegram for *every pending
update of the last 24 hours*. `TgOffset.current()` answered `0` both for a
fresh install and for a file it could not parse, and the poller then
dispatched the whole batch:

```kotlin
// master, Poller.runCycle
val offset = TgOffset.current(offsetFile)
val (next, messages) = try { fetch(cfg, offset) } ...
for (message in messages) { dispatch(cfg, message) }
```

Consequence of a single corrupt/absent state file: `/nuclear go` from
yesterday runs the full ☢️ ladder again, `/deploy go` re-arms and fires,
free-text messages are re-sent as prompts (duplicate turns, duplicate
spend). The atomic write (`.tmp` + fsync + rename) removed the *truncation*
that produced this in Python, but the **read** side still mapped "cannot
read" to "replay everything".

**Evidence.** `TgOffsetTest.currentIsTheStoredUpdateIdOrZero` pins the
dangerous mapping (`"not a number"` → `0`, empty file → `0`); the live
cursor is one file: `~/.zcode/hooks/state/tg-offset` (mode 0644, under a
0700 root). No attacker is needed — a cleanup, a restore, or a crash is
enough.

**Fix** (`12c11bd`).

- `TgOffset.read()` returns `CursorReading(value, state)` with
  `LIVE | ABSENT | CORRUPT`.
- `Poller.runCycle` **consumes a never-live batch without dispatching**
  (it saves the cursor past it) and calls `onCursorLost(count)`; the wiring
  speaks `cursorLostText(n)` to the owner ("N updates were dropped, not
  re-run; send it again").
- An **in-memory floor** (`floor = max(floor, next)` before the disk write)
  keeps a failing disk write from re-dispatching within the same process; a
  failed save is logged, never fatal.
- Tests: `PollerCursorSecurityTest` (absent cursor drops a `/nuclear go`
  batch and reports it; corrupt cursor same; the next batch flows; a live
  cursor still dispatches; a read-only disk does not re-dispatch) and
  `TgOffsetTest.readCarriesTheProvenanceOfTheZero`.
- Visible behavior change: **a fresh install no longer processes the last
  24 h of messages** — it starts clean and says so. That is the intended
  price: dropping a stale message is recoverable, replaying `/nuclear` is
  not. Existing poller tests now seed a LIVE cursor explicitly.

### F2 — The dead-man-switch URL travelled in `curl`'s argv (Low, FIXED)

**What.** `scripts/beacon.nu`'s header calls the healthchecks.io URL a
secret ("anyone holding it can forge a ping", i.e. mask an outage), and the
sibling `telegram_probe` already obeyed that law (`-K -`, config on stdin).
`ping_heartbeat` did not:

```nu
let res = (^curl -fsS --retry 2 --retry-delay 2 --max-time 8 $target | complete)
```

`argv` is world-readable through `ps(1)` for the whole call, every beacon
run.

**Evidence.** The drill's V8 runs the REAL `ping_heartbeat` against a fake
`curl` (a Nushell script that records its own argv and stdin) in a temp
HOME. Pre-fix capture:

```
argv:  -fsS --retry 2 --retry-delay 2 --max-time 8 http://sentinel.example/u9-sentinel-42
stdin: ""
```

Post-fix capture:

```
argv:  -fsS --retry 2 --retry-delay 2 --max-time 8 -K -
stdin: url = "http://sentinel.example/u9-sentinel-42"
```

**Fix** (`d01e788`): the URL now rides `curl -K -` on stdin. The drill
fails if the sentinel ever comes back in argv or stops arriving on stdin.

### F3 — `memory_inject` put recalled text in the instruction channel (Low, MITIGATED)

**What.** With `memory_inject` on (it is, live), a free-text prompt is sent
as `"Contexto de memoria (agent-brain):\n- [kind] …\n\n<owner text>"`.
Memories are model-written summaries of *previous* turns, and a turn can
quote a file, a tool output or a web page — so third-party text can end up
in the position where the model reads orders. The old header named the
source but not the trust boundary.

**Why not "fixed".** There is no sandbox here: the model decides. The honest
mitigation is framing plus bounded data handling, both now pinned:

- the header is one line and says what the block is — recalled **data, not
  instructions** (`MemoryContext.HEADER`);
- memories stay one bullet each (newlines collapse), so a payload cannot
  open a fake header or a fake section
  (`MemoryContextTest.theHeaderNamesTheTrustBoundaryAndCannotBeImpersonated`);
- the block stays ≤ 1500 code points, one item ≤ 400, ellipsis-cut without
  splitting a surrogate pair (pre-existing laws, still green).

**Residual risk (accepted):** a poisoned memory can still bias a later
turn's behavior; it cannot change the bot's own routing, and it never
reaches the bot as a command.

**Evidence.** `MemoryInjectorTest.onWithResultsPrependsTheBlockBeforeThePrompt`
(daemon) and `MemoryContextTest` (channel) pin the new header; the drill's
V3 runs both.

## 3. Vector detail

### V1 — the owner gate (no bypass)

The gate lives in `Poller.normalize`, one place, **fail-closed**: with no
`owner_user_id` in `telegram-remote.json`, *nobody* passes (never "the first
message that arrives"). It filters `message`, `edited_message`,
`callback_query` and the attachment ladder, all by `from.id`.

Tested:

- stranger message, stranger tap, stranger photo → never dispatched, cursor
  still advances (`PollerTest.theOwnerGateIsFailClosed`,
  `nonTextUpdatesAdvanceTheCursorButDoNotDispatch`, `AttachmentInTest` line
  ~98);
- no owner in config → nothing dispatched
  (`PollerTest.noOwnerInTheConfigMeansNobodyGetsThrough`);
- owner message/tap/attachment → dispatched;
- a poisoned update whose dispatch throws does not crash-loop the poller
  (`PollerTest.aPoisonedMessageIsSkippedNotCrashLooped`).

Callback forgery: `callback_data` is only ever produced by the bot's own
keyboards, and a tap is an ordinary Telegram update signed by the user —
there is nothing to guess. The destructive taps are additionally gated
downstream: `/deploy` re-checks `ownerUserId()` at confirm time
(`DeployCommand.confirm` → `NotOwner`), `☢️` needs the literal `go` or the
`nuc:go` tap *as the owner*, gifts need a confirmed arm in the same process.

Design note (recommendation, not a defect): `nuc:`/`sst:`/`bg:` taps rely on
the poller gate only. `/deploy` shows the better shape (a second owner check
at the sink). Threading `fromId` into `BotCommands.onCallback` would give
every destructive tap defense in depth; left as a follow-up because it
changes the dispatcher's public shape.

### V2 — third-party content in Telegram HTML

Every dynamic piece now has an escaping test:
`RenderInjectionSecurityTest` (CoT blocks, paged tool output and its header,
RAG citations, memories, brain status, `/tasks` titles+workspaces, balanced
markup) and `NotifInjectionSecurityTest` (subagent bell, seal bell, the tool
close box's output tail). The payload is
`<b>bold</b> & <a href="https://evil.example">click</a>`; the assertions
require `&lt;b&gt;`/`&amp;` and forbid a raw tag. `PyText.esc` (the single
`& < >` escaper) plus the `htmlTruncate` tag-closer are the whole defense —
a raw `<` inside a `<pre>` would earn a 400 «Unclosed tag» and the turn
would be mute, which this project treats as a failure.

Tool *paths* in the Telegram-facing renders are escaped as well
(`ToolBox.box`, `TranscriptFeed.openLine`, `feedBoxOf`). Attachment
filenames are reduced to `[A-Za-z0-9._-]` and capped before they touch the
disk (`attachmentFilename`, `AttachmentWiringTest`) — a `../../` name is
written as `______` in `~/.zcode/hooks/state/attachments/`, so no path
traversal.

### V3 — `memory_inject` (see F3)

### V4 — cursor replay (see F1)

### V5/V6 — the B3a socket

Read-only checks against the live daemon (no mutation, no authenticated
connection):

```
~/.zcode                drwx------  (0700)
~/.zcode/daemon-local.sock  srw-------  (0600)   owner: lattenero
~/.zcode/daemon-local.token -rw-------  (0600)   65 B, 64 hex, rotates per host boot
```

Auth probe with a *wrong* token (non-blocking for the daemon: the host keeps
a `Set` of sockets and never evicts an existing one — verified in
`packages/services/src/bots/daemonChannel.ts`, which is the fork's server):

```
→ {"type":"hello","protocolVersion":1,"token":"u9-drill-not-the-token","clientId":"u9-drill"}
← {"type":"error","code":"auth_failed","fatal":true}
```

Garbage line → `bad_request: invalid JSON line`, connection never
authenticated. Server-side: 10 s handshake timeout, `timingSafeEqual` over
SHA-256 of both tokens, `fatal` errors destroy the socket, line cap 24 MiB.
Client-side: hello token read per connect (so rotation is free), `describe()`
redacts the token in logs (`ChannelClientTest.handshakeWritesTheTokenOnTheWireOnly`),
unparseable server lines are counted and ignored, not fatal
(`ChannelClientTest.unparseableAndUnknownLinesAreIgnoredNotFatal`).

**What the socket's auth does and does not protect** (honest): it protects
against *other users* and accidental clients (0700 dir + 0600 socket), and
against a stale process holding a rotated token. It cannot protect against a
process running as the same uid — that process can read the token file,
replace the socket, or read the state anyway. This is inherent, not a bug.

Deliberately **not** executed: an authenticated hello against the live
socket. It would register as a client of the owner's live bot pipeline; the
authenticated path is covered by `ChannelClientTest` against an in-memory
transport and by the protocol spec (`docs/fork/DAEMON-CHANNEL.md`).
`[ACTION]` if the owner wants the authenticated probe run against a
throwaway host instance.

### V7/V8/V9 — secrets

- Repo (tracked files): no token-shaped string, no private key, no
  config/credential file tracked. The only key-shaped literals are the
  deliberate `sk-fake-…` fixtures in `OpencodeQuotaTest` / `ZaiQuotaGetTest`
  (they exist to prove the key never leaves the header or a log line).
- History (`git log --all -G` for the Bot API token shape): no hits.
- Live logs (`~/.zcode/bot-kotlin/logs`, 15 files, ~19 MB): the *actual*
  token appears 0 times, and no token-shaped string appears at all; the
  heartbeat URL shape appears 0 times.
- Contract files are 0600: `telegram-remote.json`, `daemon-local.token`,
  `hooks/bot-provider.json`, `v2/provider_config.json`, `heartbeat.url`.
- The bot token never reaches an exception message: `TgApi` redacts every
  transport failure (`TgConfigTest.redactMasksTheTokenEverywhereItAppears`)
  and the dry-run's logged URL is `…/bot***/…` (`SendLegTest`). The quota
  legs never put the key in a URL or a message
  (`ZaiQuotaGetTest.aDeadTransportThrowsAMessageWithoutTheKey`,
  `anHttpRefusalThrowsWithoutEchoingTheBody`).
- F2 (the beacon URL in argv) is the one leak found; see above.

**Checklist item that did not survive contact with the code:** the brief
said "verify `bot-provider.json`/`provider_config.json` are NOT read by repo
code". They **are** read — by design: `/quota`'s provider-aware direct rung
(`ChannelWiring.quotaDirectRung` → `OpencodeQuota`/`QuotaDirect`). The keys
ride an `Authorization: Bearer` header only, are never logged, and are never
echoed in an error. Filed as INFO (§5), not as a defect: the alternative
(no direct quota read) would break the owner's U-item.

### V10 — MCP (U8)

Not built yet. The drill reports it as `SKIP` and will keep doing so; when
U8 lands, the MCP surface gets its own pass (tool descriptions as an
injection surface, transport auth, scope of the resources it exposes).

## 4. The drill

`scripts/security-drill.nu` (Nushell, shebang-pinned
`~/.local/bin/nu-nightly`, typed, no bash, no `while(true)`, every I/O
bounded).

```
$ scripts/security-drill.nu            # all vectors
$ scripts/security-drill.nu --skip-live  # repo + JVM only (no state reads)
```

Real output of the run that closed this item (live socket up, 2026-11-03
23:56 America/Argentina/Buenos_Aires):

```
═══ U9 security drill ═══
repo: /Users/lattenero/Projects/.worktrees/zcode-bot-u9

PASS  V1  owner gate (msg/callback/attachment)
       green: PollerTest + AttachmentInTest
PASS  V2  third-party content rendered escaped
       green: RenderInjectionSecurityTest + NotifInjectionSecurityTest
PASS  V3  memory_inject trust boundary
       green: MemoryContextTest + MemoryInjectorTest
PASS  V4  cursor replay trap
       green: PollerCursorSecurityTest + TgOffsetTest
PASS  V5  socket + token permissions
       ~/.zcode=700 socket=600 token=600
PASS  V6  socket auth
       wrong token → {"type":"error","code":"auth_failed","fatal":true}; garbage → refused without hello_ok
PASS  V9  secret config files are owner-only
       telegram-remote.json=600 daemon-local.token=600 bot-provider.json=600 provider_config.json=600 heartbeat.url=600
PASS  V7  secrets sweep
       trackedClean=true historyHits=0 trackedConfigs=0 logsWithToken=0 logsTokenShaped=0 hbUrlInLogs=0 logFiles=15
PASS  V8  heartbeat URL never in argv
       exit=0 argv=-fsS --retry 2 --retry-delay 2 --max-time 8 -K - urlInArgv=false urlInStdin=true
SKIP  V10  MCP surface (U8)
       pending: the MCP surface is red-teamed after U8

drill: 9 PASS · 0 FAIL · 1 SKIP
```

Exit code is 1 on any `FAIL`; `SKIP` is printed and never hidden. The
socket probes only send *unauthenticated* hellos (they cannot register as a
client), the scans read files, and V8 runs the real beacon function against
a fake `curl` in a temp `HOME`, so not even a log line lands in `~/.zcode`.

The drill also runs clean with `--skip-live`
(`6 PASS · 0 FAIL · 4 SKIP`) for CI or a machine without the app running.

## 5. Accepted risks (documented, not fixed)

1. **The phone is the Mac's trust root.** Whoever holds the owner's Telegram
   account can type `/nuclear go`, `/run …`, `/deploy` (needs the confirm
   window) and `/file /any/path` — including `~/.zcode/telegram-remote.json`,
   i.e. the bot token. A `/file` denylist would be theater: the same
   capability buys `cat` through `/run`. Mitigation belongs to the account:
   Telegram 2FA/passcode, no group membership for the bot, and treating the
   phone as a shell. Revisit only if the bot ever leaves single-owner use.
2. **Same-uid processes** are outside the socket's threat model (§V5/V6).
3. **State files are 0644 inside a 0700 root** (`~/.zcode/bot-kotlin/state`,
   `~/.zcode/hooks/state`): the parked texts, the last prompt and the
   downloaded attachments are readable by the owner's own tools and by
   backups. Directory 0700 is the boundary; tightened modes are a
   defense-in-depth follow-up, not a finding.
4. **`/metrics` is loopback-only with no auth** (E2): counters and timers
   with an `outcome` tag — no task ids, no prompts, no titles. Any local
   process (or a browser) can read counts; that is the design (Prometheus
   scrapes it locally).
5. **OTel span attributes carry the task title** (`ATTR_TITLE`) and the
   session id (`OtelSpanTaps`). The default endpoint is
   `http://127.0.0.1:4318`; pointing it off-box ships conversation titles.
   Knob is off today; the warning is what a remote collector changes.
6. **Provider keys are read for `/quota`** (see V7's checklist note):
   intentional, header-only, never logged.
7. **`blackbox-e2e.nu` / `deploy-fork-and-retire.nu` build Bot API URLs with
   the token inside Nushell's own HTTP client** (memory, not argv). This is
   the only shape Telegram's API allows; the exposure is process memory, not
   `ps`.
8. **`BotCommands` logs lengths, never texts**; the outbox's poison log
   prints a 40-char preview of a refused message (bot-authored text). Not a
   secret path; noted for completeness.

## 6. `[ACTION]` — for the owner, not executed here

1. `[ACTION]` Run the drill on the Mac after the next deploy:
   `scripts/security-drill.nu` (expected `9 PASS · 0 FAIL · 1 SKIP`). The
   worktree's copy is enough; the script only reads.
2. `[ACTION]` Authenticated socket probe against a throwaway host instance
   (not the live one) if you want the `hello_ok` path exercised end-to-end
   against the real server; the client side is already covered by tests.
3. `[ACTION]` Decide the follow-up for destructive taps: thread `fromId`
   into `BotCommands.onCallback` so `nuc:`/`sst:`/`bg:` get the same second
   owner check `/deploy` already has (defense in depth; the poller gate is
   the working boundary today).
4. `[ACTION]` Rotate the healthchecks.io heartbeat URL when convenient: it
   sat in `curl`'s argv on every beacon run until `d01e788`. Forgery lets
   someone mask a dead-Mac alert. (Rotating is a healthchecks.io dashboard
   action + editing `~/.zcode/bot-kotlin/heartbeat.url`.)
5. `[ACTION]` Consider 0600 for `~/.zcode/hooks/state/inbound-queue.json`
   and `last-prompt.json` (they hold your own words). Directory 0700 makes
   this optional.

## 7. Reproduction notes

- Everything ran inside the `agent/u9-redteam` worktree; the owner's config,
  launchd jobs and daemon were not modified. The only live-touching actions
  were read-only `stat`/`grep` and unauthenticated socket hellos.
- Commits: `12c11bd` (F1 + tests), `d682b67` (F3 + render/notif escaping
  tests), `d01e788` (F2 + the drill).
- Quality gate on this branch: `./gradlew build detekt` — BUILD SUCCESSFUL
  (the repo's CI gate is `./gradlew test detekt` plus the no-manual-loop
  sweep; there is no `detektAll` task in this project).
- The Python predecessor's docs were not consulted for behavior; the
  authority is the Kotlin code at `8233ffb` and the fork's server source.

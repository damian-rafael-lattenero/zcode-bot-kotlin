# Red team — the Kotlin bot's surfaces (U9) and the MCP surface (V10)

Date: 2026-11-03 (U9) · 2026-11-04 (V10) · Owner-authorized red team of OUR OWN
surfaces (plan items U9 and V10).
Base: `master` @ `8233ffb` (U9) and @ `47cedc6` (V10) · Work: branches
`agent/u9-redteam` and `agent/v10-mcp`
(`/Users/lattenero/Projects/.worktrees/zcode-bot-u9`,
`/Users/lattenero/Projects/.worktrees/zcode-bot-kotlin-v10-mcp`) · Repos:
`zcode-bot-kotlin`, and `agent-brain` for the MCP findings.

> **Scope and ethics.** Only surfaces the owner owns: the Telegram bot, its
> daemon, the daemon-local socket, the repo's scripts and the local state
> (U9); the owner's own `brain-mcp` endpoint on loopback (V10 — one request
> per vector, 5 s each, payloads ≤ 2 MB, no flooding, no restart).
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

The MCP surface (V10) is the second credential, and it held up where it
matters: **no vector reached a tool without the token, and no vector made the
endpoint reveal whether a candidate token was close.** Eight malformed
shapes all answer one 401 body; a session id is not a credential; a foreign
`Host`/`Origin` is 403 before the MCP handler sees a byte. Three low-severity
gaps were found and fixed in `agent-brain`: the unauthenticated `/health`
route sat outside the SDK's DNS-rebinding allowlist, the read-only promise
was prose instead of a `readOnlyHint` a client can enforce, and the SDK's
verbatim echo of a client-supplied tool name into the log could forge audit
lines.

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
| V10 | MCP surface (U8): token gate, guards, body shapes, secret hygiene | **PASS** after F4/F5/F6 (all fixed) | Low ×3 |

Findings: **F1** (Medium, fixed), **F2** (Low, fixed), **F3** (Low,
mitigated), **F4/F5/F6** (Low, fixed — V10), plus accepted-by-design risks in
§5.

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

### F4 — `/health` was readable through a rebound `Host` (Low, FIXED)

**What.** The MCP SDK installs its DNS-rebinding protection as a plugin on its
own route (`/mcp`), not on the application. The endpoint also serves
`/health`, unauthenticated by design, and that route sat outside the
allowlist. Measured against the live service:

```
GET /health  Host: evil.example          → 200
GET /health  Host: 127.0.0.1.evil.example → 200
GET /health  Origin: https://evil.example → 200
GET /health  HTTP/1.0 (no Host at all)    → 200
```

A page that rebinds `evil.example` → `127.0.0.1` is then same-origin with the
response and can read it:

```json
{"status":"ok","server":"brain-mcp","version":"0.1.0","transport":"streamable-http","endpoint":"/mcp","tools":4,"calls":N}
```

`calls` is a live counter of tool calls served: a page can poll it and learn
when the owner's assistant is querying the brain. No corpus, no memory and no
token is exposed — the `/mcp` routes were correctly guarded — but the
fingerprint plus the usage oracle is exactly the scenario the guard exists
for, and §6 of `agent-brain/docs/mcp.md` claimed the SDK's allowlist
"stays on".

**Fix** (`agent-brain` `4d4b9ed`). `McpHttp.installRebindingGuard` applies the
loopback rule to every path that is **not** `/mcp` (which keeps the SDK's own
guard, and its token-first order, as the single owner of that route):

- `hostAllowed` — the `Host` header minus its port, lowercased, must be
  `localhost`, `127.0.0.1`, `[::1]` or `::1`; a non-numeric port makes the
  header malformed and is rejected, and a **missing** `Host` (HTTP/1.0) is
  rejected too. `127.0.0.1.evil.example` never reads as `127.0.0.1`;
  `127.0.0.1:80@evil.example` is refused rather than truncated.
- `originAllowed` — absent (a non-browser client) is allowed; anything that
  parses to a non-loopback host, `null`, or an unparseable value is 403.
- The 403 body is a **fixed** `{"error":"forbidden","message":"Host and Origin
  must be loopback"}`. The SDK's own 403 quotes the offending value back
  (`Invalid Host: evil.example`); ours does not, because a reflected value is
  text the caller chose written into our answer.

**Tests** (`McpHttpTest`, raw socket so the headers are exactly what a rebound
page sends): `health answers only a loopback Host and Origin` (four allowed
Hosts, four foreign ones, four bad Origins, no-Host), `a rejected Host is
never echoed back and the MCP path keeps the SDK guard` (403 body has no
reflection; `/mcp` still answers 401 first), `the Host and Origin parsers
strip ports and refuse anything else`.

**Verified live after the redeploy** (`[ACTION]` 2): `GET /health` with
`Host: evil.example` must answer 403. The assertion lives in the JVM test (it
runs on every build) rather than in the drill, because a live process can only
prove the build that is deployed and the drill must stay green on the pre-fix
binary until the owner restarts it.

### F5 — the read-only promise was prose, not a hint (Low, FIXED)

**What.** All four tool descriptions end in "Read-only." and
`agent-brain/docs/mcp.md` calls the surface read-only, but `tools/list`
carried **no `annotations`** on any tool:

```
rag_query :: Hybrid (semantic + full-text) search … :: annotations= None
memory_search :: … :: annotations= None
memory_recent :: … :: annotations= None
bus_status :: … :: annotations= None
```

**Why it matters.** `readOnlyHint` is the machine-readable form of that
promise and the field a harness gates on: a client that honours it can
auto-approve the call, while a client in a read-only or plan mode refuses a
tool that does not declare it. Without the hint the endpoint's central
security property — *a stolen token reads the corpus, it cannot write* — was
unenforceable by the client and invisible to a review: a future write tool
would have looked exactly like these four.

**Fix** (`4d4b9ed`). One constant, `READ_ONLY_TOOL`
(`readOnlyHint = true`, `destructiveHint = false`, `openWorldHint = false`),
passed as `toolAnnotations` for every tool in `newServer`; a write tool would
have to opt out of it visibly. `idempotentHint` is deliberately not set — the
spec only gives it meaning when `readOnlyHint` is false.

**Test** `McpHttpTest.every tool declares readOnlyHint so a client can enforce
the read-only promise` asserts the three hints on all four tools over HTTP.

### F6 — a caller could forge a line in the audit log (Low, FIXED)

**What.** The log is the evidence for every rejection on this surface, and one
piece of it is written with caller-controlled text. The SDK logs the tool name
from `tools/call` verbatim:

```
INFO  FeatureRegistry[Tool] - Getting Tool: "__nope__"
INFO  FeatureRegistry[Tool] - Tool not found: "__nope__"
ERROR i.m.k.sdk.server.Server - Tool not found: __nope__
```

logback writes the message unescaped, so a tool name containing a newline
starts a new log line. A token holder could therefore inject a forged entry —
including a fake `rejected an MCP request … without a valid token`, which is
the one line an owner looks for after an incident.

**What it is not.** The request body never reaches the log: the 2 MB padding
of the oversized-body probe appears 0 times, and parse errors are answered
without a stack trace. The vector is narrow (tool name in, log line out) and
needs the token, hence Low.

**Fix** (`4d4b9ed`). The appender pattern is now
`%replace(%msg){'[\r\n]+', ' '}`: one event, one line, for every logger —
ours and the SDK's. Stack traces stay multi-line on purpose (`%ex` is not
touched); their text is ours, not the caller's.

**Test** `core/src/test/.../LogLinePolicyTest` reads the **shipped**
`logback.xml`, formats an event whose message contains a newline plus a forged
`WARN` line, and asserts a single output line; a control run with a bare
`%msg` asserts the same text spans two, so the test cannot pass vacuously.

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

Unauthorized queries and id replay (server source, `daemonChannel.ts`): an
unknown query kind answers `{"type":"error","code":"unknown_query"}` and the
connection survives (additive protocol), an unknown request type answers
`bad_request`, an oversized line is `bad_request` + `fatal` + destroy, and an
inbound whose `messageId` was seen inside the 2-minute window answers
`inbound_result {duplicate:true}` **without** re-sending the prompt — the
at-least-once retry after a lost answer is idempotent on the expensive part.
On the client, a frame whose id matches no waiter is dropped by design
(`live.pending[id]?.complete`), so a replayed or forged `inbound_result` is a
no-op. `inbound` with neither text nor attachments is refused
(`bad_request`), so an empty frame cannot start a turn.

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

### V10 — the MCP surface (U8), live

The target is the owner's own service: `brain-mcp serve --transport http` on
`127.0.0.1:8767` under LaunchAgent `com.zcode.brain-mcp`, talking to the real
brain on `:8765`. Every probe is **one request**, `curl -m 5`, payload
≤ 2 MB, no flooding, no restart. The token was read straight from
`~/.zcode/agent-brain/mcp.token` into a shell variable inside a pipeline and
sent through `curl -K -` on **stdin**, so it never appeared in any process's
argv — including the drill's own.

| # | Vector | Observed | Verdict |
|---|--------|----------|---------|
| 1 | No `Authorization` header | `401`, `WWW-Authenticate: Bearer realm="brain-mcp"`, `{"error":"unauthorized","message":"missing or invalid bearer token"}`, 10 ms | PASS |
| 2 | Empty, too-short, wrong, garbage, other scheme, no scheme, trailing junk | eight shapes, all `401`, all the **byte-identical** body; a single-character mutation of the real token at the first, middle and last position → `401`; `token` minus its last character → `401`; no response headers hint at prefix or length | PASS (no oracle) |
| 3 | Valid token | `initialize` → `200`, `mcp-session-id: <uuid>`, `serverInfo {name: brain-mcp, version: 0.1.0}`; `tools/list` on that session → `200`, exactly 4 tools; `tools/list` with no session → `400 Server not initialized`; re-`initialize` on a live session → `400` | PASS |
| 4 | Valid token + foreign `Host` / `Origin` | `Host: evil.example` → `403` (`Invalid Host: evil.example`); `Host: 127.0.0.1.evil.example` → `403`; `Host: brain.example.com` → `403`; `Origin: https://evil.example` → `403`; `Origin: null` → `403`; loopback `Host`/`Origin` → `200`. Foreign `Host` **without** a token → `401` (the token answers first: no oracle, no rebinding freebie) | PASS |
| 4b | HTTP/1.0 with no `Host` at all; empty `Host` | both `403` (fail closed, not "skip the check") | PASS |
| 5 | Unknown JSON-RPC method | `200` + `{"error":{"code":-32601}}` (JSON-RPC 2.0 shape, not a 5xx) | PASS |
| 5 | Malformed JSON / empty body | `400` + `-32700 Parse error`; empty body → `400` + `-32700` | PASS |
| 5 | ~2 MB body (no token) | `401` in 5.5 ms with `size_upload=0`: the gate answers **before the body is read**, so a stranger cannot make the endpoint buffer anything | PASS |
| 5 | ~2 MB body (valid token) | `200` + `-32602 Invalid params` in 47 ms: parsed and bounded, no crash, no 5xx. It is *not* rejected — see the accepted risk in §5 | PASS (with note) |
| 5 | Wrong `Content-Type` | `415 Unsupported Media Type: Content-Type must be application/json` | PASS |
| 5 | Unknown tool name | `200` + `isError: true`, `Tool <name> not found` — and the name is what finds F6 | PASS |
| 6 | A plausible/real session id with no token; a forged uuid; a valid session with a wrong token | `401`, `401`, `401` — a session id is never a credential | PASS |
| 6b | `GET` / `DELETE /mcp` with no token; `OPTIONS` preflight from a foreign origin | `401`, `401`, `401` (the guard runs for every method; no CORS headers are ever emitted) | PASS |
| 7 | Token in the service's argv / environment | `ps -o args` → the classpath and `serve --transport http --port 8767` only; `ps -E -o command=` → `AB_MCP_TOKEN_FILE=…/mcp.token`, no value | PASS |
| 7 | Token in the service log (`~/Library/Logs/brain-mcp.log`) | 0 exact-token lines, 0 64-hex-shaped lines; startup records `token from file` and nothing else | PASS |
| 7 | Token in any error body | every body collected above (`401`, `403`, `400`, `415`, JSON-RPC error) matched against the token in process: 0 hits | PASS |
| 8 | Keep-alive / timeout | a complete request answers in 10 ms and the connection is kept (normal HTTP/1.1); an SSE `GET` with a valid token streams and never blocks the client (bounded at 1 s); a client that sends headers and stops gets no answer and no close within 6 s — the bound is the engine's, not ours (see §5) | PASS (bounded) |

**Not exercised, deliberately:** an authenticated `tools/call` that reads the
real corpus beyond `tools/list`. `tools/list` + one `initialize` is enough to
prove the contract, and every call would have written a `calls` count and a
tool log line into the owner's live service. The tool handlers themselves are
covered by `McpHttpTest` against a fake brain and by `BrainMcpStdioTest`.

**Which rows are drill assertions.** Rows 1–4b, 5 (unknown method, malformed,
empty, wrong Content-Type, the unauthenticated 2 MB) and 6–7 are what
`scripts/security-drill.nu` V10 asserts on every run. Two rows are the manual
probe's observations, kept here as evidence and not asserted by the drill
because each needs either a stateful session or a >6 s wait: the
authenticated 2 MB body (`200`, 47 ms — §5 risk 9) and the stalled-connection
bound (§5 risk 10). `host403=3/3` in the drill detail is the `/mcp` allowlist;
the `/health` half of F4 is asserted by `McpHttpTest` (a raw socket, which is
the only way to send `Host` exactly as a rebound page would) and verified on
the live service by the curl in `[ACTION]` 2 after the redeploy. The drill
still prints `readOnlyHint` from the deployed build: `false` until that
redeploy, `true` after it.

## 4. The drill

`scripts/security-drill.nu` (Nushell, shebang-pinned
`~/.local/bin/nu-nightly`, typed, no bash, no `while(true)`, every I/O
bounded).

```
$ scripts/security-drill.nu            # all vectors
$ scripts/security-drill.nu --skip-live  # repo + JVM only (no state reads)
```

Real output of the run that closed U9 (live socket up, 2026-11-03
23:56 America/Argentina/Buenos_Aires), and of the V10 run (2026-11-04, live
MCP endpoint up):

```
═══ U9 + V10 security drill ═══
repo: /Users/lattenero/Projects/.worktrees/zcode-bot-kotlin-v10-mcp

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
       trackedClean=true historyHits=0 trackedConfigs=0 logsWithToken=0 logsTokenShaped=0 hbUrlInLogs=0 logFiles=16
PASS  V8  heartbeat URL never in argv
       exit=0 argv=-fsS --retry 2 --retry-delay 2 --max-time 8 -K - urlInArgv=false urlInStdin=true
PASS  V10  MCP surface (U8)
       401=8/8 sameBody=true init=200 session=true tools=4 readOnlyHint=false host403=3/3 origin403=2/2 unknown=200/-32601 malformed=400 empty=400 2MB=401 uploaded=0 415=415 sidAlone=401 argvEnvClean=true logClean=true noEcho=true max=0.01s

drill: 10 PASS · 0 FAIL · 0 SKIP
```

`readOnlyHint=false` is the deployed build (the pre-fix binary); after
`[ACTION]` 2 it prints `true`. `uploaded=0` is the measured proof that the
2 MB unauthenticated body is never read.

Exit code is 1 on any `FAIL`; `SKIP` is printed and never hidden. The
socket probes only send *unauthenticated* hellos (they cannot register as a
client), the MCP probes are one bounded request per shape (~1 session, eight
401s that land in the service log as "rejected … without a valid token" —
the audit trail doing its job), the scans read files, and V8 runs the real
beacon function against a fake `curl` in a temp `HOME`, so not even a log
line lands in `~/.zcode`.

The drill also runs clean with `--skip-live` (`6 PASS · 0 FAIL · 4 SKIP`) for
CI or a machine without the app running; V10 is skipped there with V5/V6/V9,
and skipped (with a reason) when port 8767 is closed or the token file is
absent.

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
9. **No request-body cap on the authenticated path (V10).** A 2 MB body with
   a valid token is parsed and answered in 47 ms, and the process runs with
   `-Xmx128m` (`applicationDefaultJvmArgs`), so a token holder could OOM it —
   launchd would restart it. Not capped because (a) the unauthenticated path
   answers `401` before reading a byte (measured `size_upload=0`), so the
   amplification needs the token, i.e. the owner, who is already outside every
   threat model here; and (b) a `Content-Length` check is bypassed by a
   chunked body, so it would suggest protection it does not give. Revisit if
   the endpoint ever leaves single-owner use.
10. **The connection read bound is the engine's, not ours.** A client that
   sends a request head and then stops holds a connection until
   `CIOApplicationEngine$Configuration.connectionIdleTimeoutSeconds` expires —
   verified in the deployed jar as `bipush 45` (45 s). Measured: no answer and
   no close within 6 s. Loopback only, no session is created, and each such
   connection is one connection, not a session. A stricter read timeout would
   mean an engine swap or a proxy; not worth it for a loopback single-owner
   service.
11. **`readOnlyHint` is a hint, not an enforcement (V10/F5).** It tells a
   cooperating client that the four tools do not write; the guarantee itself
   is the four GET-shaped handlers. A client that ignores hints loses nothing
   it had.

## 6. `[ACTION]` — for the owner, not executed here

1. `[ACTION]` Run the drill on the Mac after the next deploy:
   `scripts/security-drill.nu` (expected `10 PASS · 0 FAIL · 0 SKIP`). The
   worktree's copy is enough; the script only reads (and, for V10, sends one
   bounded request per shape).
2. `[ACTION]` **Redeploy the MCP service to pick up F4/F5/F6.** Not done here:
   the drill forbids restarting the live process, and the fixes live on
   `agent/v10-mcp` in `agent-brain`. Exact steps, after reviewing and merging
   that branch into `master`:

   ```
   cd ~/Projects/agent-brain
   ./gradlew :brain-mcp:installDist          # rewrites brain-mcp/build/install/brain-mcp
   launchctl kickstart -k gui/$(id -u)/com.zcode.brain-mcp
   curl -s http://127.0.0.1:8767/health      # 200, "calls" reset
   curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: evil.example' \
        http://127.0.0.1:8767/health         # must now be 403 (was 200)
   ```

   Then re-run `scripts/security-drill.nu` from the bot repo: the V10 detail
   must flip `readOnlyHint=false` → `true` and the `/health` line to 403.
3. `[ACTION]` Authenticated socket probe against a throwaway host instance
   (not the live one) if you want the `hello_ok` path exercised end-to-end
   against the real server; the client side is already covered by tests.
4. `[ACTION]` Decide the follow-up for destructive taps: thread `fromId`
   into `BotCommands.onCallback` so `nuc:`/`sst:`/`bg:` get the same second
   owner check `/deploy` already has (defense in depth; the poller gate is
   the working boundary today).
5. `[ACTION]` Rotate the healthchecks.io heartbeat URL when convenient: it
   sat in `curl`'s argv on every beacon run until `d01e788`. Forgery lets
   someone mask a dead-Mac alert. (Rotating is a healthchecks.io dashboard
   action + editing `~/.zcode/bot-kotlin/heartbeat.url`.)
6. `[ACTION]` Consider 0600 for `~/.zcode/hooks/state/inbound-queue.json`
   and `last-prompt.json` (they hold your own words). Directory 0700 makes
   this optional.
7. `[ACTION]` Consider rotating `~/.zcode/agent-brain/mcp.token` once, with
   the redeploy above (`openssl rand -hex 32 > ~/.zcode/agent-brain/mcp.token
   && chmod 600 …`): the token is read once at startup, so rotation is free at
   a restart. Not required by any finding — hygiene for a secret that has now
   been used by more processes (the drill reads it too).

## 7. Reproduction notes

- Everything ran inside the `agent/u9-redteam` / `agent/v10-mcp` worktrees;
  the owner's config, launchd jobs and daemon were not modified. The only
  live-touching actions were read-only `stat`/`grep`/`ps`/`lsof`, `jcmd
  GC.heap_info`, unauthenticated socket hellos, and the V10 MCP probes (one
  request per vector, ~1 session, service not restarted).
- Commits (U9): `12c11bd` (F1 + tests), `d682b67` (F3 + render/notif escaping
  tests), `d01e788` (F2 + the drill).
- Commits (V10): `4d4b9ed` in `agent-brain` (F4/F5/F6 + `McpHttpTest` /
  `LogLinePolicyTest`), and the branch head of `agent/v10-mcp` in this repo
  for the drill and this report.
- Quality gate (U9 branch): `./gradlew build detekt` — BUILD SUCCESSFUL
  (the repo's CI gate is `./gradlew test detekt` plus the no-manual-loop
  sweep; there is no `detektAll` task in this project).
- Quality gate (V10, `agent-brain`): `./gradlew :brain-mcp:build
  :brain-mcp:detekt :core:detekt :core:test --tests '*LogLinePolicyTest'` →
  BUILD SUCCESSFUL. `:brain-mcp:test` = 31 tests, 0 failed, 0 skipped
  (`McpHttpTest` 15 — three of them new, `TokenAuthTest` 9,
  `BrainMcpStdioTest` 4, `McpHttpProcessTest` 3); `LogLinePolicyTest` 2.
  Full `./gradlew build` reaches the eval tier and fails there on three
  `EvalChatSuitesIT` cases:
  `ProviderUnavailable: ollama chat timed out at http://127.0.0.1:11434`.
  Measured separately on the same machine: 46–56 s for an 8-token reply when
  the local model is cold (`qwen2.5:1.5b`, `-np 1`). Provider-side and
  unrelated to this branch — no file this branch touches is on that path.
- The Python predecessor's docs were not consulted for behavior; the
  authority is the Kotlin code at `8233ffb` / `47cedc6` and the fork's server
  source.

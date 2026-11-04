#!/Users/lattenero/.local/bin/nu-nightly
#
# security-drill.nu — the red-team drill (U9) plus V10's pass over the MCP
# surface: one PASS/FAIL/SKIP per vector.
#
# It runs the vectors that CAN be run without touching the owner's live
# configuration:
#
#   V1  the owner gate (message / callback / attachment, fail-closed)   → JVM tests
#   V2  third-party content in Telegram HTML (escaping)                 → JVM tests
#   V3  the memory_inject trust boundary (data, not instructions)       → JVM tests
#   V4  the cursor replay trap (lost tg-offset ≠ replay /nuclear)       → JVM tests
#   V5  socket + token file permissions (read-only stat)                → live, read-only
#   V6  socket auth: a bad-token / garbage hello never gets hello_ok    → live, read-only
#   V7  secrets: repo, history, live logs, contract files               → local scan
#   V8  the beacon's heartbeat URL never rides curl's argv              → runtime, fake curl
#   V9  the secret-bearing config files are owner-only                  → live, read-only
#   V10 the MCP surface (U8) over streamable HTTP                       → live, read-only
#
# Nothing here writes to the owner's state: the socket probes never
# authenticate (the host keeps a Set of sockets, a stranger never evicts
# the daemon), the scans read files, the beacon drill runs the REAL function
# against a FAKE curl in a temp HOME (so not even a log line lands in
# ~/.zcode), and the MCP probes are one bounded request per vector (5 s each,
# ~1 session, no flooding) that never echo the token. The eight rejected MCP
# probes do land in the service log as "rejected … without a valid token" —
# that is the audit trail working, and V10 checks the token is not in it.
#
# Usage:
#   scripts/security-drill.nu                # everything
#   scripts/security-drill.nu --skip-live    # repo + JVM only (no socket/config reads)
#
# Exit code: 0 when no vector FAILED (SKIP does not fail the drill — it is
# said, never hidden), 1 otherwise.

type Check = record<id: string, name: string, verdict: string, detail: string>

# One bounded MCP request: what came back, and how long it took.
type Answer = record<status: int, body: string, head: string, elapsed: float, uploaded: int>

const TOKEN_SHAPE = '[0-9]{8,10}:[A-Za-z0-9_-]{35}'
const HB_URL_SHAPE = 'hc-ping\.com/[A-Za-z0-9-]{8,}'
const MCP_URL = "http://127.0.0.1:8767/mcp"
const MCP_TOOLS = "bus_status,memory_recent,memory_search,rag_query"
const MCP_SECONDS = 5

def pass [id: string, name: string, detail: string]: nothing -> Check {
    {id: $id, name: $name, verdict: "PASS", detail: $detail}
}

def fail [id: string, name: string, detail: string]: nothing -> Check {
    {id: $id, name: $name, verdict: "FAIL", detail: $detail}
}

def skip [id: string, name: string, detail: string]: nothing -> Check {
    {id: $id, name: $name, verdict: "SKIP", detail: $detail}
}

# The macOS mode of a path as its octal string ("600", "700"); "" when absent.
def mode-of [path: string]: nothing -> string {
    if not ($path | path exists) { return "" }
    ^stat -f '%Lp' $path | str trim
}

# One Gradle test invocation; PASS names the tests that proved the vector.
def jvm-vector [id: string, name: string, label: string, repo: string, args: list<string>]: nothing -> Check {
    let gradlew = ($repo | path join "gradlew")
    let r = (^$gradlew -p $repo ...$args "--console=plain" "-q" | complete)
    let tail = ($r.stdout | lines | last 4 | str join " · ")
    if $r.exit_code == 0 {
        pass $id $name $"green: ($label)"
    } else {
        fail $id $name $"gradle exit ($r.exit_code) on ($label): ($tail)($r.stderr | lines | last 2 | str join ' · ')"
    }
}

# A raw line into the daemon-local socket, bounded by nc's -w 2; answers stdout.
def socket-probe [sock: string, line: string]: nothing -> string {
    let r = ($line | ^nc -U -w 2 $sock | complete)
    $r.stdout | str trim
}

def vector-socket-perms [home: string]: nothing -> Check {
    let dir = ($home | path join ".zcode")
    let sock = ($dir | path join "daemon-local.sock")
    let token = ($dir | path join "daemon-local.token")
    let dirMode = (mode-of $dir)
    let sockMode = (mode-of $sock)
    let tokenMode = (mode-of $token)
    let detail = $"~/.zcode=($dirMode) socket=($sockMode) token=($tokenMode)"
    if ($dirMode == "700") and ($sockMode == "600") and ($tokenMode == "600") {
        pass "V5" "socket + token permissions" $detail
    } else {
        fail "V5" "socket + token permissions" $"expected 700/600/600 — ($detail)"
    }
}

def vector-socket-auth [home: string]: nothing -> Check {
    let sock = ($home | path join ".zcode/daemon-local.sock")
    if not ($sock | path exists) {
        return (skip "V6" "socket auth" "socket absent — the fork host is not running")
    }
    # 1. a syntactically valid hello with a WRONG token must never authenticate
    let wrong = ((({type: "hello", protocolVersion: 1, token: "u9-drill-not-the-token", clientId: "u9-drill"} | to json -r) + "\n"))
    let wrongAnswer = (socket-probe $sock $wrong)
    # 2. a garbage line must be refused as such (and never authenticate)
    let junk = "not-a-frame { \"type\": \"hello\"\n"
    let junkAnswer = (socket-probe $sock $junk)
    let authed = ($wrongAnswer | str contains "hello_ok") or ($junkAnswer | str contains "hello_ok")
    let refused = ($wrongAnswer | str contains "auth_failed")
    if $authed {
        return (fail "V6" "socket auth" "the socket answered hello_ok to an unauthenticated client!")
    }
    if $refused {
        pass "V6" "socket auth" $"wrong token → ($wrongAnswer | str substring 0..60); garbage → refused without hello_ok"
    } else {
        fail "V6" "socket auth" $"unexpected handshake answer: ($wrongAnswer| str substring 0..120)"
    }
}

def vector-secrets [repo: string, home: string]: nothing -> Check {
    # tracked files
    let tracked = (^git -C $repo grep -I -q -E $TOKEN_SHAPE | complete)
    # the whole history (all refs)
    let history = (^git -C $repo log --all --oneline -G $TOKEN_SHAPE | lines | length)
    # config files that must never be tracked
    let trackedConfigs = (^git -C $repo ls-files -- telegram-remote.json daemon-local.token provider_config.json bot-provider.json heartbeat.url daemon-local.sock | lines | where {|l| ($l | str trim) != ""} | length)
    # the live logs: the real token, and any token-shaped string
    let tokenFile = ($home | path join ".zcode/daemon-local.token")
    let logs = ($home | path join ".zcode/bot-kotlin/logs")
    let logFiles = (if ($logs | path exists) { ls $logs | where {|f| $f.name =~ '\.(log|jsonl)$'} | get name } else { [] })
    let token = (if ($tokenFile | path exists) { open --raw $tokenFile | str trim } else { "" })
    let withToken = ($logFiles | each {|f| if ($token | is-empty) { null } else if (open --raw $f | str contains $token) { $f } else { null } } | compact | length)
    let shaped = (if ($logs | path exists) { (^grep -rlE $TOKEN_SHAPE $logs | lines | where {|l| ($l | str trim) != ""} | length) } else { 0 })
    let hbInLogs = (if ($logs | path exists) { (^grep -rlE $HB_URL_SHAPE $logs | lines | where {|l| ($l | str trim) != ""} | length) } else { 0 })
    let detail = $"trackedClean=($tracked.exit_code == 1) historyHits=($history) trackedConfigs=($trackedConfigs) logsWithToken=($withToken) logsTokenShaped=($shaped) hbUrlInLogs=($hbInLogs) logFiles=($logFiles | length)"
    let clean = ($tracked.exit_code == 1) and ($history == 0) and ($trackedConfigs == 0) and ($withToken == 0) and ($shaped == 0) and ($hbInLogs == 0)
    if $clean { pass "V7" "secrets sweep" $detail } else { fail "V7" "secrets sweep" $detail }
}

def vector-config-perms [home: string]: nothing -> Check {
    let files = [
        ($home | path join ".zcode/telegram-remote.json")
        ($home | path join ".zcode/daemon-local.token")
        ($home | path join ".zcode/hooks/bot-provider.json")
        ($home | path join ".zcode/v2/provider_config.json")
        ($home | path join ".zcode/bot-kotlin/heartbeat.url")
    ]
    let rows = ($files | each {|f| {path: $f, mode: (mode-of $f)} })
    let missing = ($rows | where {|r| $r.mode == ""})
    let loose = ($rows | where {|r| ($r.mode != "") and ($r.mode != "600") and ($r.mode != "400")})
    let detail = ($rows | each {|r| $"($r.path | path basename)=($r.mode)" } | str join " ")
    if ($loose | is-empty) {
        pass "V9" "secret config files are owner-only" $detail
    } else {
        fail "V9" "secret config files are owner-only" $"loose: ($loose | get path | str join ", ") — ($detail)"
    }
}

# The runtime half of F2: the REAL ping_heartbeat, a FAKE curl on PATH that
# records its own argv and stdin. The URL must ride stdin (-K -), never argv.
def vector-beacon-argv [repo: string]: nothing -> Check {
    let tmp = (mktemp -d)
    let binDir = ($tmp | path join "bin")
    mkdir $binDir
    # a temp HOME with the beacon's own log dir, so a failing ping can log
    # WITHOUT touching ~/.zcode (the drill writes nothing the owner owns)
    mkdir ($tmp | path join ".zcode/bot-kotlin/logs")
    let argvFile = ($tmp | path join "argv.json")
    let fake = ($binDir | path join "curl")
    # the fake curl: records its argv AND its stdin, exits 0 (nushell, not bash)
    let script = ([
        $"#!($nu.current-exe)"
        "def --wrapped main [...args: string] {"
        "    let body = (open --raw /dev/stdin)"
        $"    { argv: $args, stdin: $body } | to json | save -f ($argvFile)"
        "}"
    ] | str join (char nl))
    $script | save -f $fake
    ^chmod +x $fake
    let sentinel = $"u9-argv-(random uuid)"
    let target = $"http://sentinel.example/($sentinel)"
    let beacon = ($repo | path join "scripts/beacon.nu")
    let call = $"source '($beacon)'; ping_heartbeat '($target)' false 'drill'"
    let fakePath = ([$binDir, ($env.PATH | into string)] | str join (char esep))
    let ran = (with-env {PATH: $fakePath, HOME: $tmp} {
        ~/.local/bin/nu-nightly --no-config-file -c $call | complete
    })
    let captured = (if ($argvFile | path exists) { open $argvFile } else { {argv: [], stdin: ""} })
    let inArgv = ($captured.argv | any {|a| $a | str contains $sentinel})
    let inStdin = ($captured.stdin | str contains $sentinel)
    let hasK = ($captured.argv | any {|a| $a == "-K"})
    let detail = $"exit=($ran.exit_code) argv=($captured.argv | str join ' ') urlInArgv=($inArgv) urlInStdin=($inStdin)"
    if ($ran.exit_code != 0) {
        return (fail "V8" "heartbeat URL never in argv" $"the beacon call failed: ($ran.stderr | lines | last 2 | str join ' · ') ($detail)")
    }
    if $inArgv {
        return (fail "V8" "heartbeat URL never in argv" $"LEAKED: ($detail)")
    }
    if (not $inStdin) or (not $hasK) {
        return (fail "V8" "heartbeat URL never in argv" $"the URL never reached curl's stdin config: ($detail)")
    }
    pass "V8" "heartbeat URL never in argv" $detail
}

# One bounded request to /mcp. The Authorization header rides curl's CONFIG on
# STDIN (-K -), never its argv: the drill obeys the law it checks, so the
# token cannot show up in `ps` for the duration of the probe either.
def mcp-call [
    auth: list<string>,
    method: string,
    body: string,
    extra: list<string>,
    contentType: string,
]: nothing -> Answer {
    let dir = (^mktemp -d /tmp/brain-mcp-drill.XXXXXX | str trim)
    let bodyPath = ($dir | path join "body.bin")
    let outPath = ($dir | path join "out.txt")
    let headPath = ($dir | path join "head.txt")
    $body | save -f $bodyPath
    let lines = ([
            ('header = "Content-Type: ' + $contentType + '"')
            'header = "Accept: application/json, text/event-stream"'
        ] | append $auth | append $extra)
    let r = ($lines | str join (char nl) | ^curl -sS -m $MCP_SECONDS -o $outPath -D $headPath -X $method -K - --data-binary ('@' + $bodyPath) -w '%{http_code} %{time_total} %{size_upload}' $MCP_URL | complete)
    let parts = ($r.stdout | str trim | split row " ")
    let answer = (if ($outPath | path exists) { open --raw $outPath } else { "" })
    let head = (if ($headPath | path exists) { open --raw $headPath } else { "" })
    rm -rf $dir
    {
        status: (try { $parts.0 | into int } catch { 0 })
        elapsed: (try { $parts.1 | into float } catch { 0.0 })
        uploaded: (try { $parts.2 | into int } catch { 0 })
        body: $answer
        head: $head
    }
}

def mcp-port-open []: nothing -> bool {
    (^nc -z -w 2 127.0.0.1 8767 | complete).exit_code == 0
}

# One character of the real token changed, so the probe can be a near miss
# without ever printing (or logging) the secret.
def mcp-mutate [token: string, at: string]: nothing -> string {
    let chars = ($token | split chars)
    let flip = {|c: string| if $c == "0" { "1" } else { "0" } }
    let head = ($chars | take (($chars | length) - 1) | str join "")
    # `last`, not `skip`: this file defines its own `skip` (the verdict helper).
    let rest = ($chars | last (($chars | length) - 1) | str join "")
    if $at == "first" {
        ((do $flip ($chars | first)) + $rest)
    } else {
        ($head + (do $flip ($chars | last)))
    }
}

# V10 — the MCP surface (U8), live and read-only. Eight groups, one request
# per shape: the token gate (no header and seven malformed shapes answer the
# SAME 401 body), the valid path (initialize + tools/list), the SDK's
# Host/Origin allowlist (403, and the token still answers first), the body
# shapes (unknown method, malformed, empty, ~2 MB, wrong Content-Type), a
# session id alone (never a credential), and the secret's absence from the
# service's argv/env, its log and every error body. The code-level half
# (readOnlyHint, the /health rebinding guard, the one-line log policy) lives
# in agent-brain's McpHttpTest / TokenAuthTest / LogLinePolicyTest, because a
# live process only proves the build that is deployed.
def vector-mcp [home: string]: nothing -> Check {
    let name = "MCP surface (U8)"
    if not (mcp-port-open) {
        return (skip "V10" $name "port 8767 is closed — the MCP service is not running")
    }
    let tokenFile = ($home | path join ".zcode/agent-brain/mcp.token")
    if not ($tokenFile | path exists) {
        return (skip "V10" $name "no ~/.zcode/agent-brain/mcp.token — the MCP service has no token")
    }
    let token = (open --raw $tokenFile | str trim)
    if ($token | is-empty) {
        return (skip "V10" $name "the token file is empty — refusing to probe with a guessed secret")
    }
    let auth = [('header = "Authorization: Bearer ' + $token + '"')]
    let init = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"v10-drill","version":"1"}}}'
    let listReq = '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

    # 1/2 — the gate: no header, no value, too short, wrong, another scheme,
    # garbage, and a one-character miss at each end all answer ONE body.
    let shapes = [
        {label: "none", auth: []}
        {label: "no-value", auth: ['header = "Authorization: Bearer"']}
        {label: "too-short", auth: ['header = "Authorization: Bearer abc"']}
        {label: "wrong", auth: ['header = "Authorization: Bearer 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"']}
        {label: "basic", auth: ['header = "Authorization: Basic dXNlcjpwYXNz"']}
        {label: "garbage", auth: ['header = "Authorization: Bearer ,;<>{}|%#"']}
        {label: "first-char", auth: [('header = "Authorization: Bearer ' + (mcp-mutate $token "first") + '"')]}
        {label: "last-char", auth: [('header = "Authorization: Bearer ' + (mcp-mutate $token "last") + '"')]}
    ]
    let gated = ($shapes | each {|s|
            let a = (mcp-call $s.auth "POST" $init [] "application/json")
            {label: $s.label, status: $a.status, body: $a.body, session: ($a.head | str contains "mcp-session-id"), elapsed: $a.elapsed}
        })
    let reference = ($gated | where label == "none" | first | get body)
    let badGate = ($gated | where {|a| ($a.status != 401) or ($a.body != $reference) or $a.session })

    # 3 — the valid token: a session and the four tools.
    let opened = (mcp-call $auth "POST" $init [] "application/json")
    let sessionRows = ($opened.head | lines | parse --regex '(?i)^mcp-session-id:\s*(?P<id>\S+)')
    let session = (if ($sessionRows | is-empty) { "" } else { $sessionRows | get id | first })
    let onSession = ($auth | append ('header = "Mcp-Session-Id: ' + $session + '"'))
    let listed = (mcp-call $onSession "POST" $listReq [] "application/json")
    let tools = (try { ($listed.body | from json).result.tools } catch { [] })
    let names = (try { $tools | get name | sort | str join "," } catch { "" })
    let readOnly = (try { $tools | all {|t| ($t | get annotations.readOnlyHint) == true } } catch { false })

    # 4 — the SDK's DNS-rebinding allowlist, with a VALID token.
    let foreignHosts = ([
            'header = "Host: evil.example"'
            'header = "Host: 127.0.0.1.evil.example"'
            'header = "Host: brain.example.com"'
        ] | each {|h| mcp-call $auth "POST" $init [$h] "application/json" })
    let foreignOrigins = ([
            'header = "Origin: https://evil.example"'
            'header = "Origin: null"'
        ] | each {|o| mcp-call $auth "POST" $init [$o] "application/json" })

    # 5 — body shapes. The ~2 MB body goes WITHOUT a token: the gate must
    # answer before the body is read, so the endpoint buffers nothing for a
    # stranger (the measured upload is reported, not asserted: a kernel buffer
    # can absorb part of it before the RST).
    let big = ('{"jsonrpc":"2.0","id":9,"method":"initialize","params":{"clientInfo":{"name":"' + ("" | fill -w 2000000 -c "A") + '"}}}')
    let unknown = (mcp-call $onSession "POST" '{"jsonrpc":"2.0","id":3,"method":"does/not/exist","params":{}}' [] "application/json")
    let malformed = (mcp-call $onSession "POST" '{"jsonrpc":"2.0",' [] "application/json")
    let empty = (mcp-call $onSession "POST" "" [] "application/json")
    let oversized = (mcp-call [] "POST" $big [] "application/json")
    let wrongType = (mcp-call $onSession "POST" $init [] "text/plain")

    # 6 — a session id is not a credential.
    let sessionOnly = (mcp-call [('header = "Mcp-Session-Id: ' + $session + '"')] "POST" $listReq [] "application/json")

    # 7 — the secret, where it must NOT be. `ps -E` covers argv AND the
    # environment; the log and every body above are matched in process (the
    # token never reaches grep's argv).
    let pids = (^lsof -nP -iTCP:8767 -sTCP:LISTEN -t | lines | where {|l| ($l | str trim) != "" })
    let pid = (if ($pids | is-empty) { "" } else { $pids | first | str trim })
    let serviceText = (if $pid == "" { "" } else { (^ps -E -o command= -p $pid | complete).stdout })
    let argvClean = (($pid != "") and (not ($serviceText | str contains $token)))
    let logFile = ($home | path join "Library/Logs/brain-mcp.log")
    let logClean = (if not ($logFile | path exists) { true } else { not ((open --raw $logFile) | str contains $token) })
    let bodies = ($gated | get body | append [
            $opened.body $listed.body $unknown.body $malformed.body $empty.body $oversized.body $wrongType.body $sessionOnly.body
        ] | append ($foreignHosts | get body) | append ($foreignOrigins | get body))
    let noEcho = ($bodies | all {|b| not ($b | str contains $token) })
    let maxElapsed = ($gated | get elapsed | append [
            $opened.elapsed $listed.elapsed $unknown.elapsed $malformed.elapsed $empty.elapsed $oversized.elapsed $wrongType.elapsed $sessionOnly.elapsed
        ] | append ($foreignHosts | get elapsed) | append ($foreignOrigins | get elapsed) | math max)

    let checks = [
        {name: "401-shapes", ok: (($badGate | is-empty) and (($gated | length) == ($shapes | length)))}
        {name: "valid-init", ok: (($opened.status == 200) and ($session != ""))}
        {name: "tools-list", ok: (($listed.status == 200) and ($names == $MCP_TOOLS))}
        {name: "foreign-host-403", ok: ($foreignHosts | all {|a| $a.status == 403 })}
        {name: "foreign-origin-403", ok: ($foreignOrigins | all {|a| $a.status == 403 })}
        {name: "unknown-method", ok: (($unknown.status == 200) and (($unknown.body | from json | get error.code) == -32601))}
        {name: "malformed-json", ok: ($malformed.status == 400)}
        {name: "empty-body", ok: ($empty.status == 400)}
        {name: "oversized-body", ok: (($oversized.status == 401) and ($oversized.elapsed <= $MCP_SECONDS))}
        {name: "wrong-content-type", ok: ($wrongType.status == 415)}
        {name: "session-is-not-a-credential", ok: ($sessionOnly.status == 401)}
        {name: "token-not-in-argv-or-env", ok: $argvClean}
        {name: "token-not-in-log", ok: $logClean}
        {name: "no-echo", ok: $noEcho}
        {name: "bounded", ok: ($maxElapsed <= $MCP_SECONDS)}
    ]
    let failed = ($checks | where {|c| not $c.ok } | get name)
    let detail = $"401=($gated | where status == 401 | length)/8 sameBody=(($badGate | is-empty)) init=($opened.status) session=(($session | str length) == 36) tools=($tools | length) readOnlyHint=($readOnly) host403=($foreignHosts | where status == 403 | length)/3 origin403=($foreignOrigins | where status == 403 | length)/2 unknown=($unknown.status)/-32601 malformed=($malformed.status) empty=($empty.status) 2MB=($oversized.status) uploaded=($oversized.uploaded) 415=($wrongType.status) sidAlone=($sessionOnly.status) argvEnvClean=($argvClean) logClean=($logClean) noEcho=($noEcho) max=($maxElapsed | math round --precision 2)s"
    if ($failed | is-empty) {
        pass "V10" $name $detail
    } else {
        fail "V10" $name $"($failed | str join ', ') — ($detail)"
    }
}

def main [--skip-live]: nothing -> nothing {
    let repo = ($env.FILE_PWD | path dirname)
    let home = $nu.home-dir
    mut checks: list<Check> = []

    print "═══ U9 + V10 security drill ═══"
    print $"repo: ($repo)"
    print ""

    # V1 — the owner gate: a stranger's message, tap and attachment all die at
    # the poller's fail-closed filter; the owner's still ride.
    $checks = ($checks | append (jvm-vector "V1" "owner gate (msg/callback/attachment)" "PollerTest + AttachmentInTest" $repo [
        ":modules:channel:test" "--tests" "*PollerTest" "--tests" "*AttachmentInTest"
    ]))
    $checks = ($checks | append (jvm-vector "V2" "third-party content rendered escaped" "RenderInjectionSecurityTest + NotifInjectionSecurityTest" $repo [
        ":modules:channel:test" "--tests" "*RenderInjectionSecurityTest"
        ":modules:daemon:test" "--tests" "*NotifInjectionSecurityTest"
    ]))
    $checks = ($checks | append (jvm-vector "V3" "memory_inject trust boundary" "MemoryContextTest + MemoryInjectorTest" $repo [
        ":modules:channel:test" "--tests" "*MemoryContextTest"
        ":modules:daemon:test" "--tests" "*MemoryInjectorTest"
    ]))
    $checks = ($checks | append (jvm-vector "V4" "cursor replay trap" "PollerCursorSecurityTest + TgOffsetTest" $repo [
        ":modules:channel:test" "--tests" "*PollerCursorSecurityTest"
        ":modules:transport:test" "--tests" "*TgOffsetTest"
    ]))

    if $skip_live {
        $checks = ($checks | append (skip "V5" "socket + token permissions" "--skip-live"))
        $checks = ($checks | append (skip "V6" "socket auth" "--skip-live"))
        $checks = ($checks | append (skip "V9" "secret config files are owner-only" "--skip-live"))
    } else {
        $checks = ($checks | append (vector-socket-perms $home))
        $checks = ($checks | append (vector-socket-auth $home))
        $checks = ($checks | append (vector-config-perms $home))
    }

    $checks = ($checks | append (vector-secrets $repo $home))
    $checks = ($checks | append (vector-beacon-argv $repo))
    $checks = ($checks | append (if $skip_live { skip "V10" "MCP surface (U8)" "--skip-live" } else { vector-mcp $home }))

    for c in $checks {
        print $"($c.verdict | fill -a right -w 4)  ($c.id)  ($c.name)"
        print $"       ($c.detail)"
    }

    let failed = ($checks | where {|c| $c.verdict == "FAIL"} | length)
    let skipped = ($checks | where {|c| $c.verdict == "SKIP"} | length)
    let passed = ($checks | where {|c| $c.verdict == "PASS"} | length)
    print ""
    print $"drill: ($passed) PASS · ($failed) FAIL · ($skipped) SKIP"
    if $failed > 0 { exit 1 }
}

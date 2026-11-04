#!/Users/lattenero/.local/bin/nu-nightly
#
# security-drill.nu — U9's red-team drill: one PASS/FAIL/SKIP per vector.
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
#   V10 the MCP surface (U8)                                            → PENDING, always SKIP
#
# Nothing here writes to the owner's state: the socket probes never
# authenticate (the host keeps a Set of sockets, a stranger never evicts
# the daemon), the scans read files, and the beacon drill runs the REAL
# function against a FAKE curl in a temp HOME (so not even a log line
# lands in ~/.zcode).
#
# Usage:
#   scripts/security-drill.nu                # everything
#   scripts/security-drill.nu --skip-live    # repo + JVM only (no socket/config reads)
#
# Exit code: 0 when no vector FAILED (SKIP does not fail the drill — it is
# said, never hidden), 1 otherwise.

type Check = record<id: string, name: string, verdict: string, detail: string>

const TOKEN_SHAPE = '[0-9]{8,10}:[A-Za-z0-9_-]{35}'
const HB_URL_SHAPE = 'hc-ping\.com/[A-Za-z0-9-]{8,}'

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

def main [--skip-live]: nothing -> nothing {
    let repo = ($env.FILE_PWD | path dirname)
    let home = $nu.home-dir
    mut checks: list<Check> = []

    print "═══ U9 security drill ═══"
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
    $checks = ($checks | append (skip "V10" "MCP surface (U8)" "pending: the MCP surface is red-teamed after U8"))

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

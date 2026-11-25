#!/opt/homebrew/bin/nu
# beacon-decision.nu — the /fail decision's laws, as values: no clock, no
# network, no launchd. The beacon's whole honesty rides on these boundaries.
use ../lib/beacon-decision.nu *

def expect [actual: bool, expected: bool, what: string]: nothing -> nothing {
    if $actual != $expected {
        error make {msg: $"FAIL: ($what) — expected ($expected), got ($actual)"}
    }
}

def expect_str [actual: string, expected: string, what: string]: nothing -> nothing {
    if $actual != $expected {
        error make {msg: $"FAIL: ($what) — expected '($expected)', got '($actual)'"}
    }
}

# A transient blip (the ~200/day TCP resets) never rings: the next cycle
# recovers in ~3 s, so anything inside the window is still "heard".
expect (decide_down 0 false).down false "fresh cursor (0 s)"
expect (decide_down 4 false).down false "the normal 3-4 s cadence"
expect (decide_down 179 false).down false "one second inside the window"

# The boundary itself: 180 s of silence is 60 failed cycles — still inside;
# the first second past it IS the outage worth the phone.
expect (decide_down 180 false).down false "180 s is still inside the window"
expect (decide_down 181 false).down true "181 s: the first ring"
expect (decide_down 3600 false).down true "an hour of silence"
expect (decide_down 999999999 false).down true "an absent cursor (the bot never heard the phone)"

# A deploy owns the daemon: green while the marker is fresh, whatever the
# poller says (the same hands-off rule the healer obeys).
expect (decide_down 999999999 true).down false "deploy in flight stays green"
expect (deploy_in_flight 0) true "a marker written now"
expect (deploy_in_flight 899) true "one second inside the deploy window"
expect (deploy_in_flight 900) false "900 s: the window closed"
expect (deploy_in_flight 999999999) false "an absent marker is not a deploy"

# The reason is the receipt the log carries: it must name the real cause.
expect_str (decide_down 200 false).reason "poller silent 200s" "silent reason"
expect_str (decide_down 5 false).reason "poller heard Telegram 5s ago" "healthy reason"

# The law that kills the flap: the /fail input is the cursor's mtime, never a
# log line. A regression to log parsing must fail HERE, not on the phone.
const SELF: path = (path self)
const SCRIPTS: path = ($SELF | path dirname | path dirname)
const BEACON: path = ($SCRIPTS | path join "beacon.nu")
let body: string = (open --raw $BEACON)
if not ($body | str contains "decide_down") {
    error make {msg: "FAIL: beacon.nu does not call decide_down"}
}
if not ($body | str contains "tg-offset") {
    error make {msg: "FAIL: beacon.nu does not read the poller's cursor"}
}
if ($body | str contains "getUpdates failed") {
    error make {msg: "FAIL: beacon.nu parses poller log lines again — the flap is back"}
}

print "beacon-decision: 17 laws green"

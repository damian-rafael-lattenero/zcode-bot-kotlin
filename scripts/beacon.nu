#!/Users/lattenero/.local/bin/nu-nightly
#
# The Mac's half of the dead man's switch (see the zcode-watchdog repo).
#
# Two independent witnesses, both fed from this one launchd job:
#
#   1. healthchecks.io — PRIMARY. Every run pings a hosted heartbeat;
#      if the Mac (or its internet) dies, the pings stop and the service
#      alerts via its own Telegram bot, outside the Mac's failure domain.
#   2. the gist — SECONDARY/diagnostic. The GitHub watchdog can still
#      read it manually; it carries timestamps and health flags, no
#      secrets.
#
# The heartbeat URL is a secret (anyone holding it can forge a ping), so
# it lives in ~/.zcode/bot-kotlin/heartbeat.url, never in this repo.

const GIST_ID = "d61ac1dd354d8d69579a2df683db1688"
const GIST_FILE = "beacon.json"
const DAEMON_LOG = "~/.zcode/bot-kotlin/logs/daemon.jsonl"
const BEACON_LOG = "~/.zcode/bot-kotlin/logs/beacon.log"
const HEARTBEAT_URL_FILE = "~/.zcode/bot-kotlin/heartbeat.url"
const POLL_FAIL_NEEDLE = "getUpdates failed"
const POLL_FAIL_WINDOW = 10min
# The heartbeat's /fail signal is intentionally tighter than the gist's
# diagnostic window: a single transient getUpdates error should not keep
# the check down for ten minutes.
const POLL_FAIL_ALERT_WINDOW = 2min
const TAIL_LINES = 400

# The wire shape — one schema, writer and reader (the house rule).
type Beacon = record<epoch: int, iso: string, daemon_log_age_s: int, poll_fail_recent: bool, beacon_version: int>

def expanded [path: string]: nothing -> string {
    $path | path expand
}

def now_epoch []: nothing -> int {
    date now | format date "%s" | into int
}

# mtime as epoch; -1 = absent (gist staleness is the only alarm — the
# daemon fields are diagnostics, never the alert itself).
def file_epoch [path: string]: nothing -> int {
    if not ($path | path exists) { return (-1) }
    ^stat -f %m $path | str trim | into int
}

# True when the daemon's poller logged a Telegram failure inside the
# window — the "Mac alive but Telegram unreachable" case (the beacon
# still ships because GitHub is reachable).
def poll_fail_since [log: string, window: duration]: nothing -> bool {
    if not ($log | path exists) { return false }
    let cutoff = (date now) - $window
    open --raw $log
    | lines
    | last $TAIL_LINES
    | where {|line| $line | str contains $POLL_FAIL_NEEDLE }
    | each {|line|
        try {
            let ts = ($line | from json | get "@timestamp" | into datetime)
            $ts > $cutoff
        } catch {
            false
        }
    }
    | any {|hit| $hit }
}

def poll_fail_recent [log: string]: nothing -> bool {
    poll_fail_since $log $POLL_FAIL_WINDOW
}

def log_line [message: string]: nothing -> nothing {
    let stamp = (date now | format date "%Y-%m-%dT%H:%M:%S%:z")
    $"($stamp) ($message)\n" | save --append (expanded $BEACON_LOG)
}

def heartbeat_url []: nothing -> string {
    let path = (expanded $HEARTBEAT_URL_FILE)
    if not ($path | path exists) { return "" }
    open --raw $path | str trim
}

# One ping per run. The success ping proves the Mac reached the outside
# world; /fail is the honest signal when the Telegram poller is down, so
# healthchecks.io can raise the "Mac alive, Telegram dead" case the Mac
# itself cannot announce. A failed ping is logged, never fatal: the next
# run retries, and the absence of pings is exactly what the service
# watches for.
def ping_heartbeat [url: string, poll_fail: bool]: nothing -> nothing {
    if ($url | is-empty) {
        log_line "heartbeat SKIP: no ~/.zcode/bot-kotlin/heartbeat.url"
        return
    }
    let target = if $poll_fail { $"($url)/fail" } else { $url }
    let res = (^curl -fsS --retry 2 --retry-delay 2 --max-time 8 $target | complete)
    let body = ($res.stdout | str trim)
    if $res.exit_code == 0 {
        if ($body | str contains "not found") {
            log_line $"heartbeat ERROR: check not found — bad heartbeat.url? fail=($poll_fail) body=($body)"
        } else if $poll_fail {
            # A normal success is visible in the healthchecks.io dashboard;
            # only the /fail signal is worth a local log line.
            log_line $"heartbeat ok fail=true body=($body)"
        }
    } else {
        log_line $"heartbeat FAIL fail=($poll_fail) exit=($res.exit_code) stderr=($res.stderr | str trim)"
    }
}

def main []: nothing -> nothing {
    let log = (expanded $DAEMON_LOG)
    let beacon: Beacon = {
        epoch: (now_epoch),
        iso: (date now | format date "%Y-%m-%dT%H:%M:%S%:z"),
        daemon_log_age_s: ((now_epoch) - (file_epoch $log)),
        poll_fail_recent: (poll_fail_recent $log),
        beacon_version: 1,
    }
    # Heartbeat FIRST: a GitHub outage must not look like a Mac outage.
    ping_heartbeat (heartbeat_url) (poll_fail_since $log $POLL_FAIL_ALERT_WINDOW)
    let tmp = (mktemp)
    $beacon | to json | save --force $tmp
    let pushed = (^gh gist edit $GIST_ID $tmp --filename $GIST_FILE | complete)
    rm --force $tmp
    if $pushed.exit_code == 0 {
        log_line $"ok epoch=($beacon.epoch) daemon_age_s=($beacon.daemon_log_age_s) poll_fail_recent=($beacon.poll_fail_recent)"
    } else {
        log_line $"FAIL exit=($pushed.exit_code) stderr=($pushed.stderr | str trim)"
        error make {msg: $"beacon push failed: ($pushed.stderr | str trim)"}
    }
}

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
const TELEGRAM_CFG = "~/.zcode/telegram-remote.json"
const POLL_FAIL_NEEDLE = "getUpdates failed"
const POLL_RECOVERED_NEEDLE = "poller RECOVERED"
const POLL_FAIL_WINDOW = 10min
# Staleness bound for the log signal. The poller writes a failure every
# ~30.2 s while Telegram is down, so the newest one is always < 31 s old;
# 60 s covers two cycles with margin. A 30 s window is not enough: with
# the beacon sampling every 60 s it would miss roughly every other
# failure (aliasing), flapping the check between /fail and a normal ping.
def poll_fail_alert_window []: nothing -> duration { 60sec }
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

# Is the poller broken RIGHT NOW, according to its own log? The last
# relevant event wins: a "poller RECOVERED" line after the failure means
# the mute window closed and Telegram polling works again, so a stale
# "getUpdates failed" must not keep the check down for a whole window.
# The window only bounds how old an unresolved failure may be before we
# stop trusting the log (e.g. the poller thread died silently).
def poller_failing [log: string, window: duration]: nothing -> bool {
    if not ($log | path exists) { return false }
    let cutoff = (date now) - $window
    let events = (
        open --raw $log
        | lines
        | last $TAIL_LINES
        | each {|line|
            try {
                let rec = ($line | from json)
                let msg = ($rec | get message)
                let ts = ($rec | get "@timestamp" | into datetime)
                if ($msg | str contains $POLL_FAIL_NEEDLE) {
                    {ts: $ts, failed: true}
                } else if ($msg | str contains $POLL_RECOVERED_NEEDLE) {
                    {ts: $ts, failed: false}
                } else {
                    null
                }
            } catch { null }
        }
        | where {|event| $event != null }
    )
    if ($events | is-empty) { return false }
    let last = ($events | last)
    $last.failed and ($last.ts > $cutoff)
}

# Ask Telegram directly. getMe is cheap, side-effect free, and answers
# "is Telegram reachable RIGHT NOW" — which the log window can only
# guess, and then keeps guessing for a whole window after recovery.
# Returns "skip" when there is no token to probe with, so the caller
# falls back to the log. The token never touches argv: curl reads its
# config (including the URL) from stdin.
def telegram_probe []: nothing -> string {
    let path = (expanded $TELEGRAM_CFG)
    if not ($path | path exists) { return "skip" }
    let token = (try { open --raw $path | from json | get token } catch { "" })
    if ($token | is-empty) { return "skip" }
    let cfg = $"url = \"https://api.telegram.org/bot($token)/getMe\"\n"
    let res = ($cfg | ^curl -fsS --max-time 6 -K - | complete)
    if $res.exit_code == 0 { "ok" } else { "fail" }
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
# world; /fail is the honest signal when Telegram is unreachable, so
# healthchecks.io can raise the "Mac alive, Telegram dead" case the Mac
# itself cannot announce. A failed ping is logged, never fatal: the next
# run retries, and the absence of pings is exactly what the service
# watches for.
#
# THE URL IS A BEARER CREDENTIAL (this file's header says so): anyone
# holding it can forge a ping and mask an outage. It therefore rides
# curl's CONFIG ON STDIN (-K -), never argv — argv is visible to every
# local observer through ps(1) for the whole call (U9 finding F2; the
# telegram probe above already obeyed this law).
def ping_heartbeat [url: string, telegram_down: bool, reason: string]: nothing -> nothing {
    if ($url | is-empty) {
        log_line "heartbeat SKIP: no ~/.zcode/bot-kotlin/heartbeat.url"
        return
    }
    let target = if $telegram_down { $"($url)/fail" } else { $url }
    let curl_cfg = $"url = \"($target)\"\n"
    let res = ($curl_cfg | ^curl -fsS --retry 2 --retry-delay 2 --max-time 8 -K - | complete)
    let body = ($res.stdout | str trim)
    if $res.exit_code == 0 {
        if ($body | str contains "not found") {
            log_line $"heartbeat ERROR: check not found — bad heartbeat.url? fail=($telegram_down) body=($body)"
        } else if $telegram_down {
            # A normal success is visible in the healthchecks.io dashboard;
            # only the /fail signal is worth a local log line.
            log_line $"heartbeat ok fail=true reason=($reason) body=($body)"
        }
    } else {
        log_line $"heartbeat FAIL fail=($telegram_down) exit=($res.exit_code) stderr=($res.stderr | str trim)"
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
    # Two independent signals, ORed: a live getMe proves Telegram is
    # reachable right now (and clears a stale outage instantly), while
    # the poller's own last event catches breakage getMe cannot see
    # (409 Conflict from a second poller, 401, 429).
    let probe = (telegram_probe)
    let log_failing = (poller_failing $log (poll_fail_alert_window))
    let telegram_down = ($probe == "fail") or $log_failing
    let reason = if $probe == "fail" { "telegram-probe" } else { "poll-log" }
    # Heartbeat FIRST: a GitHub outage must not look like a Mac outage.
    ping_heartbeat (heartbeat_url) $telegram_down $reason
    let tmp = (mktemp)
    $beacon | to json | save --force $tmp
    let pushed = (^gh gist edit $GIST_ID $tmp --filename $GIST_FILE | complete)
    rm --force $tmp
    if $pushed.exit_code == 0 {
        log_line $"ok epoch=($beacon.epoch) daemon_age_s=($beacon.daemon_log_age_s) poll_fail_recent=($beacon.poll_fail_recent) telegram_probe=($probe)"
    } else {
        log_line $"FAIL exit=($pushed.exit_code) stderr=($pushed.stderr | str trim)"
        error make {msg: $"beacon push failed: ($pushed.stderr | str trim)"}
    }
}

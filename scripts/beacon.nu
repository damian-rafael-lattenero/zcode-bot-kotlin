#!/Users/lattenero/.local/bin/nu-nightly
#
# The Mac's half of the dead man's switch (docs/watchdog.md).
#
# The bot lives 100% on this Mac — when the cable dies, the thing that
# would tell the phone is dead with it. So the Mac stamps an unlisted
# gist with proof of life every 5 minutes (com.zcode.beacon.plist) and
# the GitHub Actions watchdog reads it and sends the Telegram alert the
# Mac cannot. The gist is unlisted (secret-by-URL) and carries NO
# secrets — only timestamps and health flags.

const GIST_ID = "d61ac1dd354d8d69579a2df683db1688"
const GIST_FILE = "beacon.json"
const DAEMON_LOG = "~/.zcode/bot-kotlin/logs/daemon.jsonl"
const BEACON_LOG = "~/.zcode/bot-kotlin/logs/beacon.log"
const POLL_FAIL_NEEDLE = "getUpdates failed"
const POLL_FAIL_WINDOW = 10min
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
def poll_fail_recent [log: string]: nothing -> bool {
    if not ($log | path exists) { return false }
    let cutoff = (date now) - $POLL_FAIL_WINDOW
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

def log_line [message: string]: nothing -> nothing {
    let stamp = (date now | format date "%Y-%m-%dT%H:%M:%S%:z")
    $"($stamp) ($message)\n" | save --append (expanded $BEACON_LOG)
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

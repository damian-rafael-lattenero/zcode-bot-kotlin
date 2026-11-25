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
#
# The /fail half — "the Mac is alive but the bot cannot hear Telegram" — is
# decided from ONE positive signal: the poller's cursor file, rewritten at
# the end of every successful getUpdates cycle, so its mtime is the last time
# the bot heard the phone. A transient TCP reset (the long poll's keep-alive
# dies ~200x/day, recovering on the next cycle) never rings; only a
# SUSTAINED silence does. The decision is pure and lives in
# lib/beacon-decision.nu, tested by tests/beacon-decision.nu.

const GIST_ID: string = "d61ac1dd354d8d69579a2df683db1688"
const GIST_FILE: string = "beacon.json"
const DAEMON_LOG: string = "~/.zcode/bot-kotlin/logs/daemon.jsonl"
const BEACON_LOG: string = "~/.zcode/bot-kotlin/logs/beacon.log"
const HEARTBEAT_URL_FILE: string = "~/.zcode/bot-kotlin/heartbeat.url"
# TgOffset.save rewrites the cursor at the end of EVERY successful cycle —
# updates or not — so its mtime is the receipt this check reads.
const POLLER_OFFSET: string = "~/.zcode/hooks/state/tg-offset"
# deploy-daemon.nu writes this for its whole run: the daemon is SUPPOSED to
# be down then, so a fresh marker keeps the check green (the healer's rule).
const DEPLOY_MARKER: string = "~/.zcode/bot-kotlin/state/deploying"

const SELF: path = (path self)
const DECISION_LIB: path = ($SELF | path dirname | path join "lib/beacon-decision.nu")
source $DECISION_LIB

# The wire shape — one schema, writer and reader (the house rule).
type Beacon = record<epoch: int, iso: string, daemon_log_age_s: int, poller_silent_s: int, beacon_version: int>

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

# Age in seconds. An absent file is infinitely old: a bot that never wrote a
# cursor never heard the phone — exactly the case this script exists for.
def file_age_s [path: string, now: int]: nothing -> int {
    let m: int = (file_epoch $path)
    if $m < 0 { return 999999999 }
    $now - $m
}

def log_line [message: string]: nothing -> nothing {
    let stamp: string = (date now | format date "%Y-%m-%dT%H:%M:%S%:z")
    $"($stamp) ($message)\n" | save --append (expanded $BEACON_LOG)
}

def heartbeat_url []: nothing -> string {
    let path: path = (expanded $HEARTBEAT_URL_FILE)
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
# local observer through ps(1) for the whole call (U9 finding F2).
def ping_heartbeat [url: string, telegram_down: bool, reason: string]: nothing -> nothing {
    if ($url | is-empty) {
        log_line "heartbeat SKIP: no ~/.zcode/bot-kotlin/heartbeat.url"
        return
    }
    let target: string = if $telegram_down { $"($url)/fail" } else { $url }
    let curl_cfg: string = $"url = \"($target)\"\n"
    let res: record = ($curl_cfg | ^curl -fsS --retry 2 --retry-delay 2 --max-time 8 -K - | complete)
    let body: string = ($res.stdout | str trim)
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

def main [--status]: nothing -> nothing {
    let now: int = (now_epoch)
    let log: string = (expanded $DAEMON_LOG)
    let poller_silent_s: int = (file_age_s (expanded $POLLER_OFFSET) $now)
    let deploy_age_s: int = (file_age_s (expanded $DEPLOY_MARKER) $now)
    let plan: record<down: bool, reason: string> = (
        decide_down $poller_silent_s (deploy_in_flight $deploy_age_s)
    )
    let beacon: Beacon = {
        epoch: $now,
        iso: (date now | format date "%Y-%m-%dT%H:%M:%S%:z"),
        daemon_log_age_s: ($now - (file_epoch $log)),
        poller_silent_s: $poller_silent_s,
        beacon_version: 2,
    }
    if $status {
        print ({beacon: $beacon, plan: $plan} | to json)
        return
    }
    # Heartbeat FIRST: a GitHub outage must not look like a Mac outage.
    ping_heartbeat (heartbeat_url) $plan.down $plan.reason
    let tmp: path = (mktemp)
    $beacon | to json | save --force $tmp
    let pushed: record = (^gh gist edit $GIST_ID $tmp --filename $GIST_FILE | complete)
    rm --force $tmp
    if $pushed.exit_code == 0 {
        log_line $"ok epoch=($beacon.epoch) daemon_age_s=($beacon.daemon_log_age_s) poller_silent_s=($beacon.poller_silent_s) down=($plan.down) reason=($plan.reason)"
    } else {
        log_line $"FAIL exit=($pushed.exit_code) stderr=($pushed.stderr | str trim)"
        error make {msg: $"beacon push failed: ($pushed.stderr | str trim)"}
    }
}

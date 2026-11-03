#!/Users/lattenero/.local/bin/nu-nightly
# bot-healer.nu — the daemon's OWN dead-man's switch.
#
# The beacon (beacon.nu → healthchecks.io) watches the MACHINE: it stays
# green while the Mac lives, even if the bot is dead. Measured 2026-11-02:
# the bot was silent 22:02:37→22:06:03 (a /deploy killed it) and nothing
# noticed — the owner did. This script closes that hole: every 60 s it
# reads the daemon's own heartbeat and, when the beat is dead, restarts
# the LaunchAgent.
#
# The three doors that keep it honest:
#
#   1. FRESHNESS, not liveness — `launchctl print` says "loaded" even for a
#      hung process. hb-channel is written by the channel client's own
#      loop, so 5 silent minutes mean the bot is not serving the phone.
#   2. HANDS OFF DURING A DEPLOY — deploy-daemon.nu writes state/deploying
#      for its whole run; the daemon is SUPPOSED to be down then. Acting
#      there would fight the deploy.
#   3. ONE ACTION PER 5 MINUTES — a heal that does not take must not become
#      a restart loop (the flood lesson). The state file is the clock.
#
# It says what it did: a line in logs/healer.log and ONE Telegram message
# (stateless sendMessage — it never touches getUpdates, so it cannot fight
# the daemon's monopoly). No token or chat id here: they come from
# ~/.zcode/telegram-remote.json, and the token travels through curl's
# config on stdin, never argv.

const LABEL = "com.zcode.bot-daemon"
const STALE_AFTER_S = 180
const MIN_INTERVAL_S = 300
const DEPLOY_GRACE_S = 900

def expanded [p: string]: nothing -> string {
    $p | str replace --all "~" $nu.home-dir
}

def cfg_path [name: string, fallback: string]: nothing -> string {
    let raw = ($env | get --optional $name | default $fallback | into string)
    expanded $raw
}

def now_s []: nothing -> int {
    date now | format date "%s" | into int
}

# A missing file is infinitely old: a daemon that never wrote a heartbeat
# is exactly the case this script exists for.
def file_age_s [p: string, now: int]: nothing -> int {
    if not ($p | path exists) { return 999999999 }
    let m = (^/usr/bin/stat -f %m $p | str trim | into int)
    $now - $m
}

def agent_loaded [domain: string, label: string]: nothing -> bool {
    let r = (do { ^/bin/launchctl print $"($domain)/($label)" } | complete)
    $r.exit_code == 0
}

def read_last_heal [state: string]: nothing -> int {
    if not ($state | path exists) { return 0 }
    try { open --raw $state | from json | get ts | into int } catch { 0 }
}

def log_line [log: string, message: string]: nothing -> nothing {
    let stamp = (date now | format date "%Y-%m-%dT%H:%M:%S%:z")
    let parent = ($log | path dirname)
    mkdir $parent
    $"($stamp) ($message)\n" | save --append $log
}

# THE decision, pure: every input is a value, every output is a record.
# (Testable from the REPL without touching launchd.)
def decide [
    hb_age: int,
    deploy_in_flight: bool,
    since_last_heal: int,
    loaded: bool,
    force: bool,
]: nothing -> record {
    if (not $force) and $hb_age < $STALE_AFTER_S {
        return {act: false, why: $"heartbeat fresca: ($hb_age)s", action: "none", quiet: true}
    }
    if (not $force) and $deploy_in_flight {
        return {act: false, why: "deploy en vuelo — manos fuera", action: "none", quiet: false}
    }
    if (not $force) and $since_last_heal < $MIN_INTERVAL_S {
        return {act: false, why: $"rate-limit: ($since_last_heal)s desde el último heal", action: "none", quiet: false}
    }
    let action = if $loaded { "kickstart" } else { "bootstrap" }
    {act: true, why: $"heartbeat mudo hace: ($hb_age)s", action: $action, quiet: false}
}

def notify [text: string]: nothing -> nothing {
    let path = (cfg_path "HEALER_TELEGRAM" "~/.zcode/telegram-remote.json")
    if not ($path | path exists) { return }
    let cfg = (try { open --raw $path | from json } catch { return })
    let token = ($cfg | get --optional token | default "")
    let chat = ($cfg | get --optional chat_id | default "")
    if ($token | is-empty) or ($chat | is-empty) { return }
    # the token goes through curl's config on stdin, never argv
    let curl_cfg = $"url = \"https://api.telegram.org/bot($token)/sendMessage\"\n"
    let body = ({chat_id: ($chat | into string), text: $text} | to json)
    let res = ($curl_cfg | ^curl -fsS --max-time 8 -K - -H "Content-Type: application/json" -d $body | complete)
    if $res.exit_code != 0 {
        log_line (cfg_path "HEALER_LOG" "~/.zcode/bot-kotlin/logs/healer.log") $"notify FAILED: ($res.stderr | str trim)"
    }
}

def main [--dry-run, --force, --status]: nothing -> nothing {
    let now = (now_s)
    let hb = (cfg_path "HEALER_HB" "~/.zcode/bot-kotlin/state/hb-channel")
    let marker = (cfg_path "HEALER_DEPLOY_MARKER" "~/.zcode/bot-kotlin/state/deploying")
    let state = (cfg_path "HEALER_STATE" "~/.zcode/bot-kotlin/state/healer.json")
    let log = (cfg_path "HEALER_LOG" "~/.zcode/bot-kotlin/logs/healer.log")
    let label = ($env | get --optional HEALER_LABEL | default $LABEL | into string)
    let daemon_plist = (cfg_path "HEALER_DAEMON_PLIST" "~/Library/LaunchAgents/com.zcode.bot-daemon.plist")
    let uid = (^/usr/bin/id -u | str trim)
    let domain = $"gui/($uid)"

    let hb_age = (file_age_s $hb $now)
    let marker_age = (file_age_s $marker $now)
    let deploy_in_flight = ($marker_age < $DEPLOY_GRACE_S)
    let since_last = ($now - (read_last_heal $state))
    let loaded = (agent_loaded $domain $label)
    let plan = (decide $hb_age $deploy_in_flight $since_last $loaded $force)

    if $status {
        print ({hb: $hb, hb_age_s: $hb_age, deploy_in_flight: $deploy_in_flight, loaded: $loaded, since_last_heal_s: $since_last, plan: $plan} | to json)
        return
    }
    if not $plan.act {
        if not ($plan | get --optional quiet | default false) {
            log_line $log $"skip: ($plan.why)"
        }
        if $dry_run { print $plan.why }
        return
    }
    if $dry_run {
        print ($plan | to json)
        return
    }

    let result = if $plan.action == "kickstart" {
        do { ^/bin/launchctl kickstart -k $"($domain)/($label)" } | complete
    } else {
        do { ^/bin/launchctl bootstrap $domain $daemon_plist } | complete
    }
    let ok = ($result.exit_code == 0)
    let detail = if $ok { "" } else { ($result.stderr | str trim | str substring 0..200) }

    mkdir ($state | path dirname)
    {ts: $now, action: $plan.action, hb_age_s: $hb_age, ok: $ok, detail: $detail}
        | to json | save -f $state
    log_line $log $"HEAL ok=($ok) action=($plan.action) hb_age=($hb_age)s detail=($detail)"

    if $ok {
        notify $"🔧 Healer: el daemon estaba mudo hace ($hb_age / 60 | math floor) min — lo levanté con ($plan.action)."
    } else {
        notify $"☠️ Healer: el daemon estaba mudo hace ($hb_age / 60 | math floor) min y NO pude levantarlo con ($plan.action): ($detail). Necesita manos."
    }
}

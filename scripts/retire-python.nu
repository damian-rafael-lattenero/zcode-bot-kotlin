#!/Users/lattenero/.local/bin/nu-nightly
# retire-python.nu — B3b's LAST step: the abrupt Python retirement (owner's
# directive 30/10, plan-brujula U2.5). PREPARED, NOT EXECUTED BY THE AGENT —
# the main runs it only AFTER Damian restarts the app with the fork's
# daemon-local channel deployed (⌘Q + build-install.nu + verify-deploy.nu),
# because retiring Python's poller before the channel exists leaves the
# phone with no way to start turns.
#
# The sequence (every step verifies before the next):
#   (a) PRE-CHECK: the fork's channel is alive (socket answers a hello) —
#       refuse to retire onto a dead phone;
#   (b) UNLOAD Python's LaunchAgents (cmds = the poller, watch = the
#       spectator/zconn watchers, healer = the dumb outer ring) — unloaded
#       FIRST so the single getUpdates reader is free the moment the daemon
#       takes it (two pollers on one token = Telegram's 409);
#   (c) FLIP the daemon's `poll` knob on + kickstart (the file is the
#       deploy, the restart is the apply);
#   (d) VERIFY: the daemon's channel is UP and the heartbeats beat.
#
# Rollback (the owner's overlap rule, inverted): relaunch the Python agents
# and flip the knob back, then kickstart the daemon.
#
# Migrated from retire-python.sh on 2026-11-02. The knob edit is pure nu
# (`upsert` + `to json`), not an embedded Python snippet.

use lib/deploy.nu *

const DAEMON_LABEL = "com.zcode.bot-daemon"
const PY_AGENTS = ["com.zcode.telegram-cmds" "com.zcode.telegram-watch" "com.zcode.bot-healer"]

def main []: nothing -> nothing {
    let home = $nu.home-dir
    let data = ($home | path join ".zcode/bot-kotlin")
    let knob = ($data | path join "daemon.json")
    let socket = ($home | path join ".zcode/daemon-local.sock")
    let token_file = ($home | path join ".zcode/daemon-local.token")
    let daemon_log = ($data | path join "logs/daemon.jsonl")

    step "(a) the fork's daemon-local channel must be ALIVE (the phone's turns depend on it)"
    if not (($socket | path type) == "socket") {
        die $"no socket at ($socket) — the fork is NOT deployed yet \(Damian: ⌘Q, then scripts/build-install.nu --yes in ~/Projects/ZCode\). Refusing to retire Python onto a dead phone."
    }
    if not (($token_file | path type) == "file") {
        die $"no token file at ($token_file) — the fork's host never booted the channel."
    }
    let token = (open --raw $token_file | str trim)
    let hello = ({type: "hello", protocolVersion: 1, token: $token, clientId: "retire-check"} | to json -r)
    let reply_raw = (($hello + "\n") | ^nc -U -w 2 $socket | complete)
    let reply = if ($reply_raw.stdout | is-empty) { "" } else { $reply_raw.stdout | lines | first }
    if not ($reply | str contains '"type":"hello_ok"') {
        let got = if ($reply | is-empty) { "nothing" } else { $reply }
        die $"the socket did not answer hello_ok \(got: ($got)\). Refusing."
    }
    log $"hello_ok — the channel is alive: ($reply)"

    step "(b) unloading Python's LaunchAgents (the single reader must be free FIRST)"
    for label in $PY_AGENTS {
        if (agent-loaded $label) {
            bootout-agent $label
            log $"bootout ($label)"
        } else {
            print $"· ($label) was not loaded \(already retired?\)"
        }
    }
    sleep 2sec

    step "(c) poll knob ON + kickstart (the file is the deploy, the restart is the apply)"
    let cfg = if (($knob | path type) == "file") { open $knob } else { {} }
    let updated = ($cfg | upsert poll true)
    $updated | to json -i 2 | save -f $knob
    log $"knob: ($updated | to json -r)"
    ^launchctl kickstart -k $"gui/(user-id)/($DAEMON_LABEL)"
    log $"kickstarted ($DAEMON_LABEL)"

    step "(d) verification (deadline 30s)"
    mut boot_line = ""
    mut waited = 0
    while ($waited < 30000) {
        let hits = (tail-lines $daemon_log 400 | where {|l| $l | str contains '"message":"poll knob: on=true' })
        if ($hits | length) > 0 {
            $boot_line = ($hits | last)
            break
        }
        sleep 100ms
        $waited += 100
    }
    if ($boot_line | is-empty) {
        die $"the daemon did not boot with poll=true — check ($daemon_log) and launchctl print gui/(user-id)/($DAEMON_LABEL)"
    }
    log $"daemon boot: ($boot_line)"

    sleep 3sec
    let channel_up = (tail-lines $daemon_log 600 | any {|l| $l | str contains '"message":"channel UP' })
    if $channel_up {
        log "channel UP in the fresh daemon's log"
    } else {
        print -e "⚠ no 'channel UP' line yet — the degraded warning below would explain it:"
        for line in (tail-lines $daemon_log 600 | where {|l| $l | str contains '"channel DEGRADED' } | last 2) {
            print -e $line
        }
    }

    for hb in ["hb-channel" "hb-poller"] {
        let path = ($data | path join "state" $hb)
        if (($path | path type) != "") {
            log $"($hb) beating (^stat -f '%Sm' $path | str trim)"
        } else {
            print $"· ($hb) not present yet \(the poller writes it on its first beat\)"
        }
    }

    print ""
    print "FINAL MANUAL CHECKS (from the phone, by Damian):"
    print "  1. /status answers (the eight commands are the daemon's now)"
    print "  2. free text starts a turn in the REAL conversation and the live view speaks"
    print "  3. a zconn notice or spectator message still arrives (sends stay LIVE)"
    print ""
    print "Python is retired. The bot is 100% Kotlin. Hot-fix loop: fix, commit, push,"
    print "scripts/deploy-daemon.nu (never build-install.nu from here — Damian owns ⌘Q)."
}

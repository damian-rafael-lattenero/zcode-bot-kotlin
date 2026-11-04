#!/Users/lattenero/.local/bin/nu-nightly
# deploy-daemon.nu — build + install the Kotlin daemon (zconn/spectator +
# channel/live view + getUpdates poller) and its LaunchAgent. The daemon is
# the LIVE bot since the 30/10 cutover; Python is retired. It reads the
# fork's CLI logs and writes under ~/.zcode/bot-kotlin (ADR-023).
#
# Migrated from deploy-daemon.sh on 2026-11-02. Typed end to end; the
# interpreter is pinned by the shebang (nu-nightly 0.116.2-nightly.28).

use lib/deploy.nu *

const LABEL = "com.zcode.bot-daemon"

def main []: nothing -> nothing {
    let repo = ($env.FILE_PWD | path dirname)
    let home = $nu.home-dir
    let dest = ($home | path join ".zcode/bot-daemon")
    let data = ($home | path join ".zcode/bot-kotlin")

    # THE HEALER'S DOOR (bot-healer.nu): a deploy is SUPPOSED to leave the
    # daemon down for a minute or two. The marker tells the healer to stand
    # down; it ages out on its own (15 min) if this script never returns.
    mkdir ($data | path join "state")
    (date now | format date "%s") | save -f ($data | path join "state/deploying")

    step "building (Gradle daemon on 25)"
    announce "🚀 deploy bot 1/4 — building (Gradle + detekt + tests; el build es el tramo largo)"
    cd $repo
    ^./gradlew -q :modules:daemon:installDist detekt test

    step $"installing to ($dest)"
    announce "🚀 deploy bot 2/4 — installing"
    mkdir $dest
    mkdir ($data | path join "state")
    mkdir ($data | path join "shadow")
    mkdir ($data | path join "logs")
    rm -rf ($dest | path join "bin") ($dest | path join "lib")
    let built = ($repo | path join "modules/daemon/build/install/daemon")
    ^/bin/cp -R $"($built)/." $"($dest)/"

    # The healer is installed BEFORE the daemon's own restart: if the
    # bootstrap below ever fails, this is the job that brings it back.
    step "LaunchAgent com.zcode.bot-healer (the bot's own dead-man's switch)"
    announce "🚀 deploy bot 3/4 — LaunchAgents (healer + daemon)"
    bootstrap-agent $repo "com.zcode.bot-healer"

    step $"LaunchAgent ($LABEL) \(KeepAlive: launchd is the dumb outer ring\)"
    let started = (date now | format date "%s" | into int)
    bootstrap-agent $repo $LABEL

    step "verification: first heartbeat + armed state (deadline 15s)"
    announce "🚀 deploy bot 4/4 — verification (heartbeat + estado armado, deadline 15 s)"
    let hb = ($data | path join "state/hb-zconn-shadow")
    let zconn = ($data | path join "state/zconn.json")
    mut ok = false
    mut waited = 0
    while ($waited < 15000) {
        # The heartbeat must be NEWER than this deploy: the files survive
        # restarts, so mere existence false-passed a daemon that never
        # booted (measured 2026-11-02). zconn.json is state, not a clock:
        # it only has to exist.
        if (($hb | path type) == "file") and (($zconn | path type) == "file") {
            let hb_mtime = (^stat -f %m $hb | str trim | into int)
            if $hb_mtime >= $started {
                $ok = true
                break
            }
        }
        sleep 100ms
        $waited += 100
    }
    if not $ok {
        print -e "✗ daemon did not arm — last words:"
        let state = (do { ^launchctl print $"gui/(user-id)/($LABEL)" } | complete)
        print $state.stdout
        let err = ($data | path join "logs/launchd.err.log")
        for line in (tail-lines $err 5) {
            print -e $line
        }
        exit 1
    }
    log $"heartbeat: (^stat -f '%Sm' $hb | str trim)"
    log $"state: (open --raw $zconn | str trim)"
    let daemon_log = ($data | path join "logs/daemon.jsonl")
    let last = (tail-lines $daemon_log 1)
    if ($last | length) > 0 {
        log $"log tail: ($last | first)"
    }
    # the deploy is over: the healer owns the daemon again
    rm -f ($data | path join "state/deploying")
    print 'done. Live daemon armed: channel + getUpdates poller + send (daemon.json is the knob).'
    print 'Health: ~/.zcode/bot-kotlin/state/hb-channel · hb-poller · hb-zconn-shadow; log: logs/daemon.jsonl.'
}

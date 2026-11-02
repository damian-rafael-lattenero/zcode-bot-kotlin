#!/Users/lattenero/.local/bin/nu-nightly
# deploy-daemon.nu — B1a: build + install the shadow daemon (zconn under a
# KAP Process). ADDITIVE ONLY: it reads the CLI logs the Python bot also
# reads and writes ONLY under ~/.zcode/bot-kotlin (its own state, shadow
# decisions, logs). The Python bot keeps sending — shadow mode: Kotlin
# computes, Python still speaks (ADR-023).
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

    step "building (Gradle daemon on 25)"
    cd $repo
    ^./gradlew -q :modules:daemon:installDist detekt test

    step $"installing to ($dest)"
    mkdir $dest
    mkdir ($data | path join "state")
    mkdir ($data | path join "shadow")
    mkdir ($data | path join "logs")
    rm -rf ($dest | path join "bin") ($dest | path join "lib")
    let built = ($repo | path join "modules/daemon/build/install/daemon")
    ^/bin/cp -R $"($built)/." $"($dest)/"

    step $"LaunchAgent ($LABEL) \(KeepAlive: launchd is the dumb outer ring\)"
    bootstrap-agent $repo $LABEL

    step "verification: first heartbeat + armed state (deadline 15s)"
    let hb = ($data | path join "state/hb-zconn-shadow")
    let zconn = ($data | path join "state/zconn.json")
    mut ok = false
    mut waited = 0
    while ($waited < 15000) {
        if (($hb | path type) != "" and ($zconn | path type) != "") {
            $ok = true
            break
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
    print 'done. Shadow decisions land in ~/.zcode/bot-kotlin/shadow/zconn.jsonl as cuts happen;'
    print 'the parity gate compares them with what the Python bot sends (ADR-023).'
}

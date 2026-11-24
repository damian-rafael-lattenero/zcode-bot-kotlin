#!/Users/lattenero/.local/bin/nu-nightly
# deploy-recorder.nu — B0: build + install the golden replay recorder.
#
# ADDITIVE ONLY: this touches nothing of the running Python bot (zero
# behavior change, the B0 contract). It installs a NEW LaunchAgent
# (com.zcode.bot-recorder) that snapshots ~/.zcode/hooks/state every 60 s
# into ~/.zcode/bot-recordings/ (never inside any repo).
#
# Migrated from deploy-recorder.sh on 2026-11-02.

use /Users/lattenero/Scripts/lib/deploy.nu *

const LABEL: string = "com.zcode.bot-recorder"

def main []: nothing -> nothing {
    let repo: path = ($env.FILE_PWD | path dirname)
    let home: path = $nu.home-dir
    let dest: path = ($home | path join ".zcode/bot-recorder")
    let recordings: path = ($home | path join ".zcode/bot-recordings")

    step "building (Gradle daemon on 25)"
    cd $repo
    ^./gradlew -q :modules:recorder:installDist detekt test

    step $"installing to ($dest)"
    mkdir $dest
    mkdir $recordings
    rm -rf ($dest | path join "bin") ($dest | path join "lib")
    let built: path = ($repo | path join "modules/recorder/build/install/recorder")
    ^/bin/cp -R $"($built)/." $"($dest)/"

    step $"LaunchAgent ($LABEL)"
    bootstrap-agent $repo $LABEL

    step "first pass (manual)"
    let recorder: path = ($dest | path join "bin/recorder")
    ^$recorder --state-dir ($home | path join ".zcode/hooks/state") --out-dir $recordings

    let traces: table = (ls $recordings | where name =~ 'replay-.*\.jsonl$' | sort-by modified -r)
    if ($traces | length) == 0 {
        die $"no replay trace written under ($recordings)"
    }
    step $"trace: (($traces | first).name)"
    print 'done. Verify tomorrow that the day file keeps growing; two weeks of traces gate B1.'
}

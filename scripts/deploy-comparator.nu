#!/Users/lattenero/.local/bin/nu-nightly
# deploy-comparator.nu — B1c+: build + install the DAILY parity comparator.
#
# The parity week (bot ADR-025) now accumulates on its own: this
# LaunchAgent runs the comparator's DAILY report once a day
# (StartInterval 86400), so the seven-consecutive-PARITY cutover gate is
# earned by the calendar, not by anybody remembering to run it. Reports
# land in ~/.zcode/bot-kotlin/parity/ (report-YYYY-MM-DD.json, one per
# day — exactly what `comparator --week` reads).
#
# Migrated from deploy-comparator.sh on 2026-11-02.

use lib/deploy.nu *

const LABEL = "com.zcode.bot-comparator"

def main []: nothing -> nothing {
    let repo = ($env.FILE_PWD | path dirname)
    let home = $nu.home-dir
    let dest = ($home | path join ".zcode/bot-comparator")
    let parity = ($home | path join ".zcode/bot-kotlin/parity")

    step "building (Gradle daemon on 25)"
    cd $repo
    ^./gradlew -q :modules:comparator:installDist detekt test

    step $"installing to ($dest)"
    mkdir $dest
    mkdir $parity
    rm -rf ($dest | path join "bin") ($dest | path join "lib")
    let built = ($repo | path join "modules/comparator/build/install/comparator")
    ^/bin/cp -R $"($built)/." $"($dest)/"

    step $"LaunchAgent ($LABEL) \(daily, 86400 s\)"
    bootstrap-agent $repo $LABEL

    step "first daily report (manual)"
    let comparator = ($dest | path join "bin/comparator")
    ^$comparator --shadow-dir ($home | path join ".zcode/bot-kotlin/shadow") --logs-dir ($home | path join ".zcode/hooks/logs") --out $parity

    print "done. The verdict above is today's; from tomorrow launchd writes it to"
    print '~/.zcode/bot-kotlin/parity/comparator-launchd.log. INSUFFICIENT is the honest'
    print 'verdict of a fresh window.'
}

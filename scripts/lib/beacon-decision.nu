# beacon-decision.nu — THE dead man's /fail decision, as a pure value.
#
# The beacon pings healthchecks.io once a minute. A normal ping says "the Mac
# and its reader are alive"; a /fail ping says "the Mac is alive but the bot
# cannot hear Telegram" — and healthchecks.io alerts on /fail IMMEDIATELY,
# whatever the check's period is. That asymmetry is why the decision must be
# about a SUSTAINED silence and never about a single failed poll.
#
# Measured 2026-11-25: the long poll's keep-alive connections get reset
# ~200x/day (~1.6% of ~13k polls) and every one of those blips became a
# DOWN + UP pair on the phone — 180 /fail pings and 180 DOWN transitions by
# 11:11, 383 the day before, 1218 in the log. The next cycle recovers in ~3 s,
# so a blip is not an outage, and raising the healthchecks.io period changed
# nothing: /fail is an explicit signal, not a missing ping.
#
# The input is ONE positive number: the age of the poller's cursor file.
# TgOffset.save rewrites it (atomic tmp+rename) at the end of EVERY successful
# getUpdates cycle — updates or not — so its mtime IS "the last time the bot
# heard Telegram". That single number covers a dead daemon, a hung one, a 409
# from a second poller, a 401 and a real network cut; no log parsing and no
# extra network probe are needed.
#
# A deploy is SUPPOSED to leave the daemon down (deploy-daemon.nu writes
# state/deploying for its whole run), so a fresh marker keeps the check green —
# the same hands-off rule the healer obeys.

# Three silent minutes. The poller cycles every ~3 s and the bot's own apology
# threshold is 60 s of mute; 180 s is 60 consecutive failed cycles, well past
# any transient blip and short enough to still be a useful phone alert.
const POLLER_SILENT_S: int = 180

# The deploy window: a deploy longer than this with the bot still deaf IS an
# outage worth ringing (the healer uses the same 900 s).
const DEPLOY_GRACE_S: int = 900

# True while a deploy owns the daemon: the marker's age is inside the window.
export def deploy_in_flight [marker_age_s: int]: nothing -> bool {
    $marker_age_s < $DEPLOY_GRACE_S
}

# THE decision, pure: every input is a value, every output is a record.
# Guard clauses by law (anti-pattern #4 — the happy path stays flat; these are
# ordered preconditions, never a dispatch chain). Testable from the REPL.
export def decide_down [poller_silent_s: int, deploying: bool]: nothing -> record<down: bool, reason: string> {
    if $deploying {
        return {down: false, reason: $"deploy in flight — poller silent ($poller_silent_s)s"}
    }
    if $poller_silent_s > $POLLER_SILENT_S {
        return {down: true, reason: $"poller silent ($poller_silent_s)s"}
    }
    {down: false, reason: $"poller heard Telegram ($poller_silent_s)s ago"}
}

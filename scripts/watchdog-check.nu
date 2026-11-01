#!/usr/bin/env nu
#
# SECONDARY / MANUAL diagnostic. The primary dead man's switch is the
# hosted heartbeat (healthchecks.io): the Mac's beacon pings it every
# 60 s, and the service sends the Telegram alert when the pings stop.
# This script is kept for circuit tests and for reading the diagnostic
# gist on demand. It is no longer scheduled: GitHub's cron degraded to
# one run every 3-6 h, and the workflow was retired with the old
# zcode-watchdog repo. Runbook: docs/beacon-runbook.md.
#
# Usage (manual):
#   export WATCHDOG_BOT_TOKEN=... WATCHDOG_CHAT_ID=...
#   nu scripts/watchdog-check.nu --simulate-stale-minutes 20   # circuit test
#   nu scripts/watchdog-check.nu --loop-seconds 240            # gist watch
#
# It talks to Telegram DIRECTLY via sendMessage — stateless, it never
# fights the Mac's getUpdates monopoly (bot ADR-001). Stable-Nushell
# compatible on purpose: it does not need the nightly build.

const GIST_ID = "d61ac1dd354d8d69579a2df683db1688"
const GIST_RAW = "https://gist.githubusercontent.com/damian-rafael-lattenero/d61ac1dd354d8d69579a2df683db1688/raw/beacon.json"
const STALE_AFTER_S = 150
const POLL_EVERY_S = 30

def now_epoch [] {
    date now | format date "%s" | into int
}

def send [text: string] {
    # Missing config is a broken setup, not a transient failure: say it
    # clearly and return false so the caller can decide what to do.
    if ($env.WATCHDOG_BOT_TOKEN? | is-empty) {
        print "send ERROR: WATCHDOG_BOT_TOKEN is not set"
        return false
    }
    if ($env.WATCHDOG_CHAT_ID? | is-empty) {
        print "send ERROR: WATCHDOG_CHAT_ID is not set"
        return false
    }

    let url = $"https://api.telegram.org/bot($env.WATCHDOG_BOT_TOKEN)/sendMessage"
    # Explicit JSON: the runner's stable nu does not auto-serialize
    # records in http post (accepted types there: binary, string).
    let body = ({chat_id: ($env.WATCHDOG_CHAT_ID | into string), text: $text} | to json)

    let max_retries = 3
    let backoff_s = 5
    mut attempt = 0
    mut delivered = false
    mut last_error = ''

    loop {
        $attempt = ($attempt + 1)
        # The catch closure may not capture mutable vars, so it only
        # builds a value; all mutation happens outside it.
        let outcome = (try {
            {kind: 'response', resp: (http post --content-type "application/json" $url $body)}
        } catch {|err|
            {kind: 'network', error: ($err.msg | str substring 0..200)}
        })

        if ($outcome.kind == 'response') {
            let resp = $outcome.resp
            if ($resp.ok? == true) {
                print $"sent: ($text | str substring 0..60)..."
                $delivered = true
                break
            }
            $last_error = $"Telegram API code=($resp.error_code? | default '?') desc=($resp.description? | default 'unknown')"
            if ($resp.error_code? == 429) {
                print "send: flood control (429), waiting 10s before retry"
                sleep (10 * 1sec)
            } else {
                sleep ($backoff_s * 1sec)
            }
        } else {
            $last_error = $"network error: ($outcome.error)"
            sleep ($backoff_s * 1sec)
        }

        if ($attempt >= $max_retries) {
            break
        }
    }

    if not $delivered {
        print $"send ERROR: FAILED after ($max_retries) attempts — alert NOT delivered to Telegram. Last error: $last_error"
        print $"send ERROR: message was: ($text | str substring 0..160)"
    }
    return $delivered
}

def main [--loop-seconds: int = 0, --simulate-stale-minutes: int = 0] {
    # The forced path proves the whole circuit (runner → gist read →
    # Telegram send) without needing a real outage.
    if $simulate_stale_minutes > 0 {
        if not (send $"[TEST] 💀 watchdog: simulación — la Mac llevaría ($simulate_stale_minutes) min sin latir. Si ves esto, el circuito Mac→gist→GitHub→Telegram está vivo.") {
            error make {msg: "CIRCUIT TEST FAILED — could not deliver test message to Telegram"}
        }
        return
    }
    mut slept = 0
    mut first = true
    mut alerted_dead = false
    mut warned_poll = false
    mut undelivered = false
    loop {
        # Via the API, NOT the raw URL: gist.githubusercontent.com is CDN-
        # cached for minutes (measured live 30/10: a healthy beacon read as
        # 2 min stale → a FALSE 💀). The API answer is always fresh; the
        # gist is UNLISTED (secret-by-URL), so anonymous reads work and the
        # Actions token does not cover gists anyway (measured: 403).
        let api = $"https://api.github.com/gists/($GIST_ID)"
        let beacon = (http get $api | get files."beacon.json".content | from json)
        let stale_s = ((now_epoch) - ($beacon.epoch | into int))
        let mins = ($stale_s // 60)
        if $stale_s > $STALE_AFTER_S {
            # One 💀 per job (~every 5 min while dead — never radio
            # silence, bounded to 12/hour); the next job re-alerts.
            if not $alerted_dead {
                if (send $"💀 watchdog: la Mac no late hace ($mins) min — último beacon ($beacon.iso). Internet cortado, Mac dormida o muerta: el bot de Telegram cayó con ella.") {
                    $alerted_dead = true
                    $undelivered = false
                } else {
                    $undelivered = true
                }
            }
            $warned_poll = false
        } else if ($beacon.poll_fail_recent? == true) {
            # The Mac reaches GitHub but not Telegram: different disease,
            # different message.
            if not $warned_poll {
                if (send $"⚠️ watchdog: Mac viva — beacon hace ($mins) min — pero su poller a Telegram falla. La Mac llega a GitHub pero no a Telegram.") {
                    $warned_poll = true
                    $undelivered = false
                } else {
                    $undelivered = true
                }
            }
        } else {
            if $alerted_dead {
                if (send $"🔌 watchdog: la Mac volvió a latir — beacon hace ($mins) min.") {
                    $alerted_dead = false
                    $undelivered = false
                } else {
                    $undelivered = true
                }
            }
            $warned_poll = false
            # Once-a-day proof the watchdog itself is alive (12:00 UTC =
            # 09:00 ART; a delayed cron just skips the day).
            let hm = (date now | format date "%H%M" | into int)
            if ($first and ($hm >= 1200 and $hm < 1210)) {
                if (send $"🟡 watchdog vivo — Mac latiendo, beacon hace ($mins) min, poller ok.") {
                    $undelivered = false
                } else {
                    $undelivered = true
                }
            }
        }
        $first = false
        $slept = ($slept + $POLL_EVERY_S)
        if ($loop_seconds > 0 and $slept >= $loop_seconds) {
            break
        }
        sleep ($POLL_EVERY_S * 1sec)
    }
    if $undelivered {
        error make {msg: "WATCHDOG MUTE — one or more alerts could not be delivered to Telegram (see send ERROR lines above)"}
    }
}

#!/opt/homebrew/bin/nu
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
#   nu scripts/watchdog-check.nu --simulate-stale-minutes 20   # circuit test
#   nu scripts/watchdog-check.nu --loop-seconds 240            # gist watch
# (token/chat read from ~/.zcode/telegram-remote.json — never env, never argv)
#
# It talks to Telegram DIRECTLY via sendMessage — stateless, it never
# fights the Mac's getUpdates monopoly (bot ADR-001). Stable-Nushell
# compatible on purpose: it does not need the nightly build.

const GIST_ID: string = "d61ac1dd354d8d69579a2df683db1688"
const GIST_RAW: string = "https://gist.githubusercontent.com/damian-rafael-lattenero/d61ac1dd354d8d69579a2df683db1688/raw/beacon.json"
const TELEGRAM_CFG: string = "~/.zcode/telegram-remote.json"
const STALE_AFTER_S: int = 150
const POLL_EVERY_S: int = 30

def now_epoch []: nothing -> int {
    date now | format date "%s" | into int
}

def send [text: string]: nothing -> bool {
    # Missing config is a broken setup, not a transient failure: say it
    # clearly and return false so the caller can decide what to do.
    # Token/chat come from the shared config file; the token NEVER rides
    # argv or env — curl reads the URL from its config on stdin (the
    # beacon pattern; fleet audit 17/11: credentials off argv).
    let cfgPath: string = ($TELEGRAM_CFG | path expand)
    if not ($cfgPath | path exists) {
        print $"send ERROR: no telegram config at ($cfgPath)"
        return false
    }
    let cfg: any = (try { open --raw $cfgPath | from json } catch { null })
    if $cfg == null {
        print "send ERROR: telegram config is not valid JSON"
        return false
    }
    let chat: string = ($cfg | get --optional chat_id | default "")
    let token: string = ($cfg | get --optional token | default "")
    if ($token | is-empty) or ($chat | is-empty) {
        print "send ERROR: telegram config is missing token/chat_id"
        return false
    }

    let payload: string = ({chat_id: $chat, text: $text} | to json)
    let curlCfg: string = $"url = \"https://api.telegram.org/bot($token)/sendMessage\"\n"

    let max_retries: int = 3
    let backoff_s: int = 5
    mut attempt: int = 0
    mut delivered: bool = false
    mut last_error: string = ''

    loop {
        $attempt = ($attempt + 1)
        # The catch closure may not capture mutable vars, so it only
        # builds a value; all mutation happens outside it.
        let outcome: record = (try {
            {kind: 'response', resp: ($curlCfg | ^curl -sS --config - --max-time 8 --header "Content-Type: application/json" --data $payload | complete)}
        } catch {|err|
            {kind: 'network', error: ($err.msg | str substring 0..200)}
        })

        if ($outcome.kind == 'response') {
            let res: record = $outcome.resp
            let resp: any = (try { $res.stdout | from json } catch { {} })
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

def main [--loop-seconds: int = 0, --simulate-stale-minutes: int = 0]: nothing -> nothing {
    # The forced path proves the whole circuit (runner → gist read →
    # Telegram send) without needing a real outage.
    if $simulate_stale_minutes > 0 {
        if not (send $"[TEST] 💀 watchdog: simulation — the Mac would go ($simulate_stale_minutes) min without a beat. If you see this, the Mac→gist→GitHub→Telegram circuit is alive.") {
            error make {msg: "CIRCUIT TEST FAILED — could not deliver test message to Telegram"}
        }
        return
    }
    mut slept: int = 0
    mut first: bool = true
    mut alerted_dead: bool = false
    mut warned_poll: bool = false
    mut undelivered: bool = false
    loop {
        # Via the API, NOT the raw URL: gist.githubusercontent.com is CDN-
        # cached for minutes (measured live 30/10: a healthy beacon read as
        # 2 min stale → a FALSE 💀). The API answer is always fresh; the
        # gist is UNLISTED (secret-by-URL), so anonymous reads work and the
        # Actions token does not cover gists anyway (measured: 403).
        let api: string = $"https://api.github.com/gists/($GIST_ID)"
        let beacon: record = (http get $api | get files."beacon.json".content | from json)
        let stale_s: int = ((now_epoch) - ($beacon.epoch | into int))
        let mins: int = ($stale_s // 60)
        if $stale_s > $STALE_AFTER_S {
            # One 💀 per job (~every 5 min while dead — never radio
            # silence, bounded to 12/hour); the next job re-alerts.
            if not $alerted_dead {
                if (send $"💀 watchdog: the Mac has not beaten for ($mins) min — last beacon ($beacon.iso). Internet cut, Mac asleep or dead: the Telegram bot fell with it.") {
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
                if (send $"⚠️ watchdog: Mac alive — beacon ($mins) min old — but its Telegram poller fails. The Mac reaches GitHub but not Telegram.") {
                    $warned_poll = true
                    $undelivered = false
                } else {
                    $undelivered = true
                }
            }
        } else {
            if $alerted_dead {
                if (send $"🔌 watchdog: the Mac beats again — beacon ($mins) min old.") {
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

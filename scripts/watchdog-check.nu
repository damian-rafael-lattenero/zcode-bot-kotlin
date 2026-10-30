#!/usr/bin/env nu
#
# The other half of the dead man's switch (docs/watchdog.md) — runs on
# GitHub Actions every 15 min. Reads the Mac's beacon gist and talks to
# Telegram DIRECTLY: sendMessage is stateless and never fights the Mac's
# getUpdates monopoly (ADR-001). Stable-Nushell compatible on purpose —
# the CI runner installs the latest release, not the nightly with types.

const GIST_RAW = "https://gist.githubusercontent.com/damian-rafael-lattenero/d61ac1dd354d8d69579a2df683db1688/raw/beacon.json"
const STALE_AFTER_S = 900

def now_epoch [] {
    date now | format date "%s" | into int
}

def send [text: string] {
    if ($env.WATCHDOG_BOT_TOKEN? | is-empty) {
        error make {msg: "WATCHDOG_BOT_TOKEN is not set"}
    }
    let url = $"https://api.telegram.org/bot($env.WATCHDOG_BOT_TOKEN)/sendMessage"
    let resp = (http post $url {chat_id: $env.WATCHDOG_CHAT_ID, text: $text})
    if ($resp.ok? != true) {
        error make {msg: $"telegram send failed: ($resp.description? | default 'unknown')"}
    }
    print $"sent: ($text | str substring 0..60)..."
}

def main [--simulate-stale-minutes: int = 0] {
    let beacon = (http get $GIST_RAW | from json)
    let real_stale_s = ((now_epoch) - ($beacon.epoch | into int))
    # A test run forces the alert path end-to-end; 0 = the real check.
    let stale_s = (if $simulate_stale_minutes > 0 { $simulate_stale_minutes * 60 } else { $real_stale_s })
    let mins = ($stale_s // 60)
    if $stale_s > $STALE_AFTER_S {
        let tag = (if $simulate_stale_minutes > 0 { "[TEST] " } else { "" })
        send $"($tag)💀 watchdog: la Mac no late hace ($mins) min — último beacon ($beacon.iso). Internet cortado, Mac dormida o muerta: el bot de Telegram cayó con ella."
    } else if ($beacon.poll_fail_recent? == true) {
        send $"⚠️ watchdog: Mac viva (beacon hace ($mins) min) pero su poller a Telegram falla — la Mac llega a GitHub pero no a Telegram."
    } else {
        # Once-a-day proof that the watchdog itself is alive (12:00 UTC =
        # 09:00 ART; if GitHub delays past :15 the day just skips it).
        let hm = (date now | format date "%H%M" | into int)
        if ($hm >= 1200 and $hm < 1215) {
            send $"🟡 watchdog vivo — Mac latiendo (beacon hace ($mins) min), poller ok."
        } else {
            print $"healthy: beacon ($mins) min old, no alert"
        }
    }
}

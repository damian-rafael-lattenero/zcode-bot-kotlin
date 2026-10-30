# The dead man's switch — cable-cut alert

## The problem (chicken and egg)

The bot runs 100% on the Mac. If the Mac loses internet, the thing that
would tell the phone is dead with it — no component ON the Mac can report
the Mac's own dead cable. Nothing "lives" in Telegram either: bots are
pull-driven, Telegram executes no code for us.

## The design

```
Mac (every 5 min)                     GitHub Actions (every 15 min)
┌─────────────────────┐   gist push   ┌──────────────────────────┐   sendMessage
│ beacon.nu ──────────┼──────────────►│ watchdog-check.nu ───────┼──────────────► 📱
│ LaunchAgent         │  (unlisted)   │ reads gist, decides      │  (bot token,
│ com.zcode.beacon    │               │ staleness > 15 min = 💀  │   send-only)
└─────────────────────┘               └──────────────────────────┘
```

- **Beacon** (`scripts/beacon.nu` + `com.zcode.beacon.plist`): stamps an
  unlisted gist — https://gist.github.com/damian-rafael-lattenero/d61ac1dd354d8d69579a2df683db1688 —
  with `{epoch, iso, daemon_log_age_s, poll_fail_recent, beacon_version}`.
  No secrets in the gist, ever. Logs to `~/.zcode/bot-kotlin/logs/beacon.log`.
- **Watcher** (`scripts/watchdog-check.nu` + `.github/workflows/watchdog.yml`):
  reads the gist raw URL, and:
  - stale > 15 min → `💀 la Mac no late hace X min` (internet cut, Mac
    asleep, or Mac dead — from outside they are indistinguishable, and all
    three mean the bot is down)
  - fresh BUT `poll_fail_recent` → `⚠️ Mac viva pero su poller a Telegram
    falla` (the Mac reaches GitHub but not Telegram)
  - healthy → silent; one daily `🟡 watchdog vivo` at ~09:00 ART proves
    the watchdog itself is alive (a dead watchdog must also be visible).

Sending uses the SAME bot token via `sendMessage` — stateless, it never
conflicts with the Mac's `getUpdates` monopoly (ADR-001). Token and chat
id live in the repo's `WATCHDOG_BOT_TOKEN` / `WATCHDOG_CHAT_ID` secrets.

## Knobs

| What | Where | Value |
|------|-------|-------|
| Beacon cadence | `com.zcode.beacon.plist` `StartInterval` | 60 s |
| Stale threshold | `watchdog-check.nu` `STALE_AFTER_S` | 900 s (3 missed beacons) |
| Poll-fail window | `beacon.nu` `POLL_FAIL_WINDOW` | 10 min |
| Watch cadence | `watchdog.yml` cron | every 15 min |

## Testing

- Alert path end-to-end: Actions → watchdog → Run workflow →
  `simulate_stale_minutes=20` → a `[TEST] 💀` message must arrive.
- Beacon path: `nu-nightly scripts/beacon.nu` then check the gist raw
  content and `~/.zcode/bot-kotlin/logs/beacon.log`.
- Real outage: unplug / turn Wi-Fi off > 15 min → the 💀 arrives on the
  phone; when the Mac returns, the next beacon silences the alarm.

## Caveats (known, accepted)

- GitHub cron can delay runs minutes; the 15-min threshold absorbs it.
- GitHub disables scheduled workflows after 60 days of repo inactivity —
  this repo is active daily; if it ever goes quiet, re-enable manually.
- Mac asleep stops the beacon → 💀 says "dormida o muerta" honestly; the
  watchdog cannot tell those apart from outside. If that distinction
  ever matters, the fix is a second beacon from the LAN, not on this Mac.
- Persistent alarm: while dead, the watcher re-alerts every 15 min —
  by design (never radio silence), bounded to 4 messages/hour.

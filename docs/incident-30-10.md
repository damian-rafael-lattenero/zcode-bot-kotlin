# Incident 10/30 — the token war and the thinking corpses

## What happened (a summary for the owner)

Between 09:44 and 13:40 on 10/30 the bot was losing messages and the
"thinking" signs were lying. Root cause: **the «retired» Python bot was still alive** —
not through launchd (that died in the retirement) but through the **CLI hooks**
(`~/.zcode/cli/config.json`): every prompt/question spawned new Python
processes (`watch-commands` = its poller). That poller read the token from the shared
config (the NEW token after last night's swap) and fought `getUpdates`
against our daemon → 409 Conflict × 99 → lost messages.

## Fixes applied (all committed and deployed)

1. **Python hooks removed**: the whole `hooks` key out of
   `~/.zcode/cli/config.json` (the `enabled:false` flag was not honored by the
   CLI — measured: the processes were reborn anyway). The live processes die with
   the app restart at 13:41.
2. **The app's legacy Telegram bot disabled**: `~/.zcode/v2/bot-config.v3.json`
   → bot "z.ai" (telegram provider) `enabled:false`. It was a second potential
   poller of the same token. The `daemon-local` bot (our channel) stays ON.
3. **Corpse seal** (commit 06bb271): when the hub replaces a turn,
   the old sign is edited ONCE with «⛓️ turn replaced…» instead of
   staying frozen saying "thinking" (the owner saw it tick with the app
   DOWN — the lie that triggered this). The boot seal (dcd2b1a) covers
   the restart orphans.
4. **The "closed and did not open"**: `open -a ZCode` died with the session
   (the app's grandchild). Now a launchd one-shot relaunches it (a child of
   launchd, immune).

## State of the rest

- GitHub watchdog: the fixes are in (f1f3421, 9d2cc95 — cron re-registered in
  slots :02-:57); keep an eye on the next `schedule` runs.
- /quota: answers honestly but the fork's usage provider says
  `not_configured` (the app has no usage service configured) —
  configuring it in the app or reading Python's cache is pending.
- Firehose: a single throat (the new token) until further notice; the old bot
  (8892…) is out of all config.

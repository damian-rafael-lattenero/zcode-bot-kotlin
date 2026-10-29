#!/bin/bash
# retire-python.sh — B3b's LAST step: the abrupt Python retirement (owner's
# directive 30/10, plan-brujula U2.5). PREPARED, NOT EXECUTED BY THE AGENT —
# the main runs it only AFTER Damian restarts the app with the fork's
# daemon-local channel deployed (⌘Q + build-install.sh + verify-deploy.sh),
# because retiring Python's poller before the channel exists leaves the
# phone with no way to start turns.
#
# The sequence (every step verifies before the next):
#   (a) PRE-CHECK: the fork's channel is alive (socket answers a hello) —
#       refuse to retire onto a dead phone;
#   (b) UNLOAD Python's LaunchAgents (cmds = the poller, watch = the
#       spectator/zconn watchers, healer = the dumb outer ring) — unloaded
#       FIRST so the single getUpdates reader is free the moment the daemon
#       takes it (two pollers on one token = Telegram's 409);
#   (c) FLIP the daemon's `poll` knob on + kickstart (the file is the
#       deploy, the restart is the apply);
#   (d) VERIFY: getUpdates answers without a 409, the daemon's channel is
#       UP, a /status from the phone answers, sends still flow LIVE.
#
# Rollback (the owner's overlap rule, inverted): bootout nothing — relaunch
# the Python agents and flip the knob back:
#   launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.zcode.telegram-cmds.plist (and watch/healer)
#   python3 - <<'EOF'
#   import json,os; p=os.path.expanduser('~/.zcode/bot-kotlin/daemon.json')
#   c=json.load(open(p)); c['poll']=False; json.dump(c,open(p,'w'))
#   EOF
#   launchctl kickstart -k gui/$(id -u)/com.zcode.bot-daemon
set -euo pipefail

UID_N="$(id -u)"
DATA="$HOME/.zcode/bot-kotlin"
KNOB="$DATA/daemon.json"
DAEMON_LABEL="com.zcode.bot-daemon"
# The Python world's three agents (verified names in ~/Library/LaunchAgents):
PY_AGENTS=(com.zcode.telegram-cmds com.zcode.telegram-watch com.zcode.bot-healer)

say() { printf '\n==> %s\n' "$*"; }
die() { printf '✗ %s\n' "$*" >&2; exit 1; }

# ---- (a) pre-check: the fork's channel answers a hello ----------------------
say "step (a): the fork's daemon-local channel must be ALIVE (the phone's turns depend on it)"
SOCKET="$HOME/.zcode/daemon-local.sock"
TOKEN_FILE="$HOME/.zcode/daemon-local.token"
[ -S "$SOCKET" ] || die "no socket at $SOCKET — the fork is NOT deployed yet (Damian: ⌘Q, then scripts/build-install.sh --yes in ~/Projects/ZCode). Refusing to retire Python onto a dead phone."
[ -f "$TOKEN_FILE" ] || die "no token file at $TOKEN_FILE — the fork's host never booted the channel."
TOKEN="$(tr -d '\n' < "$TOKEN_FILE")"
HELLO="$(printf '{"type":"hello","protocolVersion":1,"token":"%s","clientId":"retire-check"}\n' "$TOKEN" \
    | nc -U "$SOCKET" | head -1 || true)"
echo "$HELLO" | grep -q '"type":"hello_ok"' \
    || die "the socket did not answer hello_ok (got: ${HELLO:-nothing}). Refusing."
echo "✓ hello_ok — the channel is alive: $HELLO"

# ---- (b) unload Python's LaunchAgents ---------------------------------------
say "step (b): unloading Python's LaunchAgents (the single reader must be free FIRST)"
for label in "${PY_AGENTS[@]}"; do
    if launchctl print "gui/$UID_N/$label" >/dev/null 2>&1; then
        launchctl bootout "gui/$UID_N/$label"
        echo "✓ bootout $label"
    else
        echo "· $label was not loaded (already retired?)"
    fi
done
sleep 2 # the in-flight long-poll drains; 409s need BOTH pollers live

# ---- (c) the daemon's poll knob ON + kickstart -------------------------------
say "step (c): poll knob ON + kickstart (the file is the deploy, the restart is the apply)"
python3 - <<'EOF'
import json, os
p = os.path.expanduser('~/.zcode/bot-kotlin/daemon.json')
c = {}
if os.path.exists(p):
    c = json.load(open(p))
c['poll'] = True
os.makedirs(os.path.dirname(p), exist_ok=True)
json.dump(c, open(p, 'w'), indent=2)
print('✓ knob:', json.dumps(c))
EOF
launchctl kickstart -k "gui/$UID_N/$DAEMON_LABEL"
echo "✓ kickstarted $DAEMON_LABEL"

# ---- (d) verification --------------------------------------------------------
say "step (d): verification (deadline 30s)"
ok=""
for _ in $(seq 1 300); do
    line="$(grep -o '"message":"poll knob: on=true[^"]*"' "$DATA/logs/daemon.jsonl" 2>/dev/null | tail -1 || true)"
    [ -n "$line" ] && ok=1 && break
    sleep 0.1
done
[ -n "$ok" ] || die "the daemon did not boot with poll=true — check $DATA/logs/daemon.jsonl and launchctl print gui/$UID_N/$DAEMON_LABEL"
echo "✓ daemon boot: $line"

# the channel leg of the fresh daemon (the fork is deployed — this must be UP, not degraded)
sleep 3
if grep -q '"message":"channel UP' "$DATA/logs/daemon.jsonl" 2>/dev/null; then
    echo "✓ channel UP in the fresh daemon's log"
else
    echo "⚠ no 'channel UP' line yet — the degraded warning below would explain it:" >&2
    grep '"channel DEGRADED' "$DATA/logs/daemon.jsonl" 2>/dev/null | tail -2 >&2 || true
fi

# the heartbeats of the new processes
for hb in hb-channel hb-poller; do
    [ -e "$DATA/state/$hb" ] && echo "✓ $hb beating ($(stat -f '%Sm' "$DATA/state/$hb"))" \
        || echo "· $hb not present yet (the poller writes it on its first beat)"
done

# getUpdates answers WITHOUT the 409 (the one-reader proof)
echo
echo "FINAL MANUAL CHECKS (from the phone, by Damian):"
echo "  1. /status answers (the eight commands are the daemon's now)"
echo "  2. free text starts a turn in the REAL conversation and the live view speaks"
echo "  3. a zconn notice or spectator message still arrives (sends stay LIVE)"
echo
echo "Python is retired. The bot is 100% Kotlin. Hot-fix loop: fix, commit, push,"
echo "scripts/deploy-daemon.sh (never build-install.sh from here — Damian owns ⌘Q)."

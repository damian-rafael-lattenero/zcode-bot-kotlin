#!/bin/bash
# deploy-daemon.sh — B1a: build + install the shadow daemon (zconn under a
# KAP Process). ADDITIVE ONLY: it reads the CLI logs the Python bot also
# reads and writes ONLY under ~/.zcode/bot-kotlin (its own state, shadow
# decisions, logs). The Python bot keeps sending — shadow mode: Kotlin
# computes, Python still speaks (ADR-023).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$HOME/.zcode/bot-daemon"
DATA="$HOME/.zcode/bot-kotlin"
PLIST_LABEL="com.zcode.bot-daemon"
PLIST_DST="$HOME/Library/LaunchAgents/$PLIST_LABEL.plist"
UID_N="$(id -u)"

# Java 25 across the build (toolchain 25, detekt 2.0 on isolated workers):
# the Mac's global ~/.gradle/gradle.properties daemon pin to Corretto 25 is
# now the intent, not a fight — no -Dorg.gradle.java.home override anymore.
echo "==> building (Gradle daemon on 25)"
(cd "$REPO" && ./gradlew -q :modules:daemon:installDist detekt test)

echo "==> installing to $DEST"
mkdir -p "$DEST" "$DATA/state" "$DATA/shadow" "$DATA/logs"
rm -rf "$DEST/bin" "$DEST/lib"
cp -R "$REPO/modules/daemon/build/install/daemon/"* "$DEST/"

echo "==> LaunchAgent $PLIST_LABEL (KeepAlive: launchd is the dumb outer ring)"
sed -e "s|__HOME__|$HOME|g" "$REPO/scripts/$PLIST_LABEL.plist" > "$PLIST_DST"
if launchctl print "gui/$UID_N/$PLIST_LABEL" >/dev/null 2>&1; then
    launchctl bootout "gui/$UID_N/$PLIST_LABEL" || true
fi
# bootout's teardown races the bootstrap (measured twice tonight: error 5,
# "Input/output error") — give launchd a beat and retry once before giving up
if ! launchctl bootstrap "gui/$UID_N" "$PLIST_DST"; then
    sleep 2
    launchctl bootstrap "gui/$UID_N" "$PLIST_DST"
fi

echo "==> verification: first heartbeat + armed state (deadline 15s)"
ok=0
for _ in $(seq 1 150); do
    if [ -e "$DATA/state/hb-zconn-shadow" ] && [ -e "$DATA/state/zconn.json" ]; then ok=1; break; fi
    sleep 0.1
done
if [ "$ok" != 1 ]; then
    echo "✗ daemon did not arm — last words:"
    launchctl print "gui/$UID_N/$PLIST_LABEL" || true
    tail -5 "$DATA/logs/launchd.err.log" 2>/dev/null || true
    exit 1
fi
echo "✓ heartbeat: $(stat -f '%Sm' "$DATA/state/hb-zconn-shadow")"
echo "✓ state: $(cat "$DATA/state/zconn.json")"
echo "✓ log tail: $(tail -1 "$DATA/logs/daemon.jsonl" 2>/dev/null)"
echo "done. Shadow decisions land in $DATA/shadow/zconn.jsonl as cuts happen;"
echo "the parity gate compares them with what the Python bot sends (ADR-023)."

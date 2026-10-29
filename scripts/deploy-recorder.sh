#!/bin/bash
# deploy-recorder.sh — B0: build + install the golden replay recorder.
#
# ADDITIVE ONLY: this touches nothing of the running Python bot (zero
# behavior change, the B0 contract). It installs a NEW LaunchAgent
# (com.zcode.bot-recorder) that snapshots ~/.zcode/hooks/state every 60 s
# into ~/.zcode/bot-recordings/ (never inside any repo).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$HOME/.zcode/bot-recorder"
PLIST_LABEL="com.zcode.bot-recorder"
PLIST_DST="$HOME/Library/LaunchAgents/$PLIST_LABEL.plist"
UID_N="$(id -u)"

# detekt 1.23 cannot run on a Gradle daemon >= 24. The Mac's global
# ~/.gradle/gradle.properties pins the daemon JVM to Corretto 25, so the
# command-line override (not JAVA_HOME) is what actually enforces 21 here.
JDK21="$(/usr/libexec/java_home -v 21)"

echo "==> building (Gradle daemon on 21)"
(cd "$REPO" && ./gradlew -q -Dorg.gradle.java.home="$JDK21" :modules:recorder:installDist detekt test)

echo "==> installing to $DEST"
mkdir -p "$DEST" "$HOME/.zcode/bot-recordings"
rm -rf "$DEST/bin" "$DEST/lib"
cp -R "$REPO/modules/recorder/build/install/recorder/"* "$DEST/"

echo "==> LaunchAgent $PLIST_LABEL"
sed -e "s|__HOME__|$HOME|g" "$REPO/scripts/$PLIST_LABEL.plist" > "$PLIST_DST"
if launchctl print "gui/$UID_N/$PLIST_LABEL" >/dev/null 2>&1; then
    launchctl bootout "gui/$UID_N/$PLIST_LABEL" || true
fi
launchctl bootstrap "gui/$UID_N" "$PLIST_DST"

echo "==> first pass (manual)"
"$DEST/bin/recorder" --state-dir "$HOME/.zcode/hooks/state" --out-dir "$HOME/.zcode/bot-recordings"

echo "==> trace: $(ls -t "$HOME/.zcode/bot-recordings"/replay-*.jsonl | head -1)"
echo "done. Verify tomorrow that the day file keeps growing; two weeks of traces gate B1."

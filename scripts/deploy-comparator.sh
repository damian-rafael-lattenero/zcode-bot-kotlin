#!/bin/bash
# deploy-comparator.sh — B1c+: build + install the DAILY parity comparator.
#
# The parity week (bot ADR-025) now accumulates on its own: this
# LaunchAgent runs the comparator's DAILY report once a day
# (StartInterval 86400), so the seven-consecutive-PARITY cutover gate is
# earned by the calendar, not by anybody remembering to run it. Reports
# land in ~/.zcode/bot-kotlin/parity/ (report-YYYY-MM-DD.json, one per
# day — exactly what `comparator --week` reads).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$HOME/.zcode/bot-comparator"
PLIST_LABEL="com.zcode.bot-comparator"
PLIST_DST="$HOME/Library/LaunchAgents/$PLIST_LABEL.plist"
UID_N="$(id -u)"

# Java 25 across the build (toolchain 25, detekt 2.0 on isolated workers):
# the Mac's global ~/.gradle/gradle.properties daemon pin to Corretto 25 is
# now the intent, not a fight — no -Dorg.gradle.java.home override anymore.
echo "==> building (Gradle daemon on 25)"
(cd "$REPO" && ./gradlew -q :modules:comparator:installDist detekt test)

echo "==> installing to $DEST"
mkdir -p "$DEST" "$HOME/.zcode/bot-kotlin/parity"
rm -rf "$DEST/bin" "$DEST/lib"
cp -R "$REPO/modules/comparator/build/install/comparator/"* "$DEST/"

echo "==> LaunchAgent $PLIST_LABEL (daily, 86400 s)"
sed -e "s|__HOME__|$HOME|g" "$REPO/scripts/$PLIST_LABEL.plist" > "$PLIST_DST"
if launchctl print "gui/$UID_N/$PLIST_LABEL" >/dev/null 2>&1; then
    launchctl bootout "gui/$UID_N/$PLIST_LABEL" || true
fi
launchctl bootstrap "gui/$UID_N" "$PLIST_DST"

echo "==> first daily report (manual)"
"$DEST/bin/comparator" \
    --shadow-dir "$HOME/.zcode/bot-kotlin/shadow" \
    --logs-dir "$HOME/.zcode/hooks/logs" \
    --out "$HOME/.zcode/bot-kotlin/parity"

echo "done. The verdict above is today's; from tomorrow launchd writes it to ~/.zcode/bot-kotlin/parity/comparator-launchd.log. INSUFFICIENT is the honest verdict of a fresh window."

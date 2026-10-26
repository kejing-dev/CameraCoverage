#!/usr/bin/env bash
# One design state -> one screenshot: tools/qa_shot.sh S10 [NAME] ["cmd extras..."...]
#   tools/qa_shot.sh S10                       -> .scratch/shots/S10.png
#   tools/qa_shot.sh - S13-b "head --ef x 0 --ef y 1.6 --ef z -1"   (no state jump, extra broadcasts)
# Sends `state <id>`, un-throttles the emulator to 60 fps (harness `fast on`), lets the panels render,
# captures a full frame with the emulator console, throttles again. Pinned to emulator-5564 / Pico_Emulator_6_1_b.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export DEV=${DEV:-emulator-5564} AVD=${AVD:-Pico_Emulator_6_1_b} PKG=io.embodit.camcoverage TAG=CamCoverage
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
H="bash $ROOT/tools/harness/emu.sh"
SH="$ROOT/.scratch/shots"; mkdir -p "$SH/emu"
id="$1"; name="${2:-$1}"; shift; [ $# -gt 0 ] && shift
bc() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd $*" >/dev/null 2>&1; }
cap() { before=$(ls -1 "$SH/emu" 2>/dev/null | wc -l); adb -s $DEV emu screenrecord screenshot "$(cygpath -w "$SH/emu")" >/dev/null 2>&1
  for i in $(seq 1 12); do sleep 0.5; after=$(ls -1 "$SH/emu" 2>/dev/null | wc -l); [ "$after" -gt "$before" ] && break; done; sleep 0.5
  newest=$(ls -1t "$SH/emu"/*.png 2>/dev/null | head -1); [ -n "$newest" ] && mv -f "$newest" "$SH/$1.png" && echo "OK $SH/$1.png" || echo "capture FAILED"; }
adb -s $DEV logcat -c
[ "$id" != "-" ] && bc "state --es s $id"
for c in "$@"; do bc "$c"; done
sleep "${PRE:-1.5}"
$H fast on >/dev/null 2>&1
sleep "${SETTLE:-6}"
cap "$name"
$H fast off >/dev/null 2>&1
adb -s $DEV logcat -d -s CamCoverage:V 2>/dev/null | grep -E "STATE S|FATAL|CMD" | tail -2 | cut -c1-300

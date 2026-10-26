#!/usr/bin/env bash
# All design states in a row -> .scratch/shots/Sxx.png (tools/qa_all.sh [S01 S02 ...])
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ids="${@:-S01 S02 S03 S04 S05 S06 S07 S08 S09 S10 S11 S12 S13 S14 S15 S16 S17 S18 S19 S20 S21 S22 S23 S24}"
for s in $ids; do
  extra=""
  case "$s" in
    S03|S18|S22|S19|S23|S21) post="state --es s release" ;;
    *) post="" ;;
  esac
  echo "== $s"; bash "$ROOT/tools/qa_shot.sh" "$s" 2>&1 | tail -2 | cut -c1-200
  [ -n "$post" ] && MSYS_NO_PATHCONV=1 adb -s ${DEV:-emulator-5564} shell "am broadcast -a io.embodit.camcoverage.DEBUG -p io.embodit.camcoverage --es cmd $post" >/dev/null 2>&1
done

#!/usr/bin/env bash
# Full emulator pass: clean relaunch (stale ANR dialogs dismissed), all 24 states, launcher evidence, back to the first screen.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools" DEV=${DEV:-emulator-5564} AVD=${AVD:-Pico_Emulator_6_1_b} PKG=io.embodit.camcoverage TAG=CamCoverage
D=$DEV; H="bash $ROOT/tools/harness/emu.sh"
bc() { adb -s $D shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd $*" >/dev/null 2>&1; }
anrwin() { adb -s $D shell dumpsys window windows 2>/dev/null | grep -c 'Application Not Responding'; }
anrs() { adb -s $D logcat -d -b all 2>/dev/null | grep -c am_anr; }
dismiss() { local i; for i in 1 2 3; do [ "$(anrwin)" = "0" ] && return 0; adb -s $D shell input keyevent 4; sleep 2; done; }
adb -s $D shell am force-stop $PKG; sleep 2; dismiss; echo "dialogs before launch: $(anrwin)  anr events: $(anrs)"
$H fast on >/dev/null 2>&1; adb -s $D shell am start -n $PKG/.platform.LaunchActivity >/dev/null 2>&1
for i in 1 2 3 4; do sleep 5; $H fast on >/dev/null 2>&1; done
echo "after launch: anr events: $(anrs)  dialogs: $(anrwin)  pid: $(adb -s $D shell pidof $PKG)"
dismiss; echo "dialogs after dismiss: $(anrwin)"
bc "clear --es v both"; sleep 1
bash "$ROOT/tools/qa_all.sh" 2>&1 | grep -E "^==|FATAL" | tr '\n' ' '; echo
echo "after states: fatal: $(adb -s $D logcat -d -s CamCoverage:V AndroidRuntime:E | grep -cE 'FATAL|Exception')  anr events: $(anrs)  dialogs: $(anrwin)  pid: $(adb -s $D shell pidof $PKG)"
# launcher evidence: HOME opens the system "Back to Home" sheet, which draws the app's own icon
adb -s $D shell input keyevent 3; sleep 4
adb -s $D exec-out screencap -p > "$ROOT/.scratch/shots/launcher.png"
adb -s $D shell input keyevent 4; sleep 2
bc "clear --es v both"; sleep 1
echo "end: focus=$(adb -s $D shell dumpsys window | grep -m1 mCurrentFocus | cut -c1-100) state=$(adb -s $D logcat -d -s CamCoverage:V | grep STATE | tail -1 | cut -c40-80)"

#!/bin/bash
# The phone app, checked end to end on a headless emulator: boots one unless a device is attached, builds and installs the
# debug APK, then PhoneCheck walks every state against a scripted laptop (a real PhoneServer on port 47475, so a laptop
# app running here is not disturbed) and saves the screenshots, sheet.png and report.txt.
#   androidApp/check.sh [out dir]      default .stage/phone;  AVD=Pixel_8_API34 by default
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
export JAVA_HOME=${JAVA_HOME:-/Users/admin/Library/Java/JavaVirtualMachines/temurin-21.0.7/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/Users/admin/Library/Android/sdk}
export ADB=$ANDROID_HOME/platform-tools/adb
export PHONE_CHECK=$(mkdir -p "${1:-.stage/phone}" && cd "${1:-.stage/phone}" && pwd)
GRADLE=(./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process)
slot() { while [ "$(pgrep -f GradleWrapperMain | wc -l)" -ge 2 ]; do sleep 20; done; } # at most two builds on this machine

booted=""
if ! "$ADB" devices | grep -q "device$"; then
  echo "booting ${AVD:=Pixel_8_API34} without a window"
  "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot-save -memory 2048 > "$PHONE_CHECK/emulator.log" 2>&1 &
  booted=$!
  trap '"$ADB" emu kill >/dev/null 2>&1; wait $booted 2>/dev/null' EXIT
fi
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done

slot
"${GRADLE[@]}" :androidApp:assembleDebug -q || exit 1
"$ADB" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk > /dev/null || exit 1
echo "APK $(stat -f %z androidApp/build/outputs/apk/debug/androidApp-debug.apk) bytes"

slot
"${GRADLE[@]}" :composeApp:desktopTest --tests ch.lauzhack.app.link.PhoneCheck --rerun -q > "$PHONE_CHECK/gradle.log" 2>&1
status=$?
cat "$PHONE_CHECK/report.txt" 2>/dev/null || tail -30 "$PHONE_CHECK/gradle.log"
echo "screenshots and sheet.png in $PHONE_CHECK"
exit $status

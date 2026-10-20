#!/usr/bin/env bash
# Runs the androidTest suite on the already-booted CI emulator and collects what
# QA needs to check a PR without a phone (BUR-25):
#   <out>/screenshots/*.png  one per scripted flow (plus *-FAILED.png on failure)
#   <out>/logcat.txt         full device log, only when the tests failed
#
# Called from the `instrumented` job in .github/workflows/android.yml, inside
# reactivecircus/android-emulator-runner with ft8af/ as the working directory.
# Usage: run-emulator-flows.sh <out-dir>
set -uo pipefail

out="${1:?usage: run-emulator-flows.sh <out-dir>}"
# Must match Flows.SCREENSHOT_DIR in androidTest/.../flows/FlowTestSupport.kt.
device_dir=/data/local/tmp/ft8af-flows

mkdir -p "$out/screenshots"
adb shell rm -rf "$device_dir" || true
adb logcat -G 16M || true
adb logcat -c || true

# Stream test progress into the job log; connectedDebugAndroidTest otherwise
# sits silently on one "> Task" line for minutes. TestRunner:I gives each
# test's started:/finished:, AndroidRuntime:E and *:F real crashes. Not '*:E':
# a fresh google_apis image floods that with unrelated Play-services noise.
adb logcat -s TestRunner:I AndroidRuntime:E '*:F' &
logcat_pid=$!

./gradlew connectedDebugAndroidTest --stacktrace
status=$?

kill "$logcat_pid" 2>/dev/null || true

adb pull "$device_dir/." "$out/screenshots/" || echo "::warning::No flow screenshots on the device"
echo "Flow screenshots:"
ls -l "$out/screenshots" || true

if [[ $status -ne 0 ]]; then
  adb logcat -d -v threadtime > "$out/logcat.txt" || true
  echo "::error::Instrumented tests failed; logcat.txt and screenshots are in the emulator-flows artifact"
fi
exit "$status"

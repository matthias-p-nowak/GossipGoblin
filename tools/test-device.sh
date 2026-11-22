#!/usr/bin/env bash
# Build and run short real-microphone integration tests on one explicitly selected tablet.
set -euo pipefail

# Explicit serial prevents installing to an unintended connected device.
device_serial="${1:?Usage: bash tools/test-device.sh DEVICE_SERIAL}"
workspace_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$workspace_dir"
mkdir -p tmp
if adb -H 127.0.0.1 -s "$device_serial" shell dumpsys activity services org.gossipgoblin \
        | rg -q 'ServiceRecord.*org.gossipgoblin/\.RecordingService'; then
    printf 'Disarm and leave GossipGoblin before running device tests so its microphone service stops.\n' >&2
    exit 1
fi
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --console=plain
adb -H 127.0.0.1 -s "$device_serial" shell input keyevent KEYCODE_WAKEUP
adb -H 127.0.0.1 -s "$device_serial" install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -H 127.0.0.1 -s "$device_serial" install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -H 127.0.0.1 -s "$device_serial" shell am instrument -w \
    org.gossipgoblin.test/androidx.test.runner.AndroidJUnitRunner | tee tmp/device-tests.log
# Android's shell command can exit successfully even when an assertion fails.
rg -q '^OK \([0-9]+ tests?\)' tmp/device-tests.log

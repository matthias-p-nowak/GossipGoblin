# GossipGoblin

Native Android 12+ sound journal with a live graph and threshold-gated audio saving. Accepted behavior and the change from the original vision are in `docs/design.md`.

## Build

Requires JDK 17 and an Android SDK containing platform 35 and build-tools 35.0.0. Set `ANDROID_HOME` to the SDK directory, or create ignored `local.properties` with `sdk.dir=/absolute/path/to/sdk`.

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0'
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The Gradle wrapper pins Gradle 8.11.1 with its distribution checksum; AGP is pinned in `build.gradle`. The APK is `app/build/outputs/apk/debug/app-debug.apk`. Release signing and Play distribution are outside this milestone.

## Install and use

The Green Monster connection and ADB setup are described in `docs/deployment.md`.

```sh
adb -H 127.0.0.1 -s 192.168.0.101:5555 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb -H 127.0.0.1 -s 192.168.0.101:5555 shell am start -n org.gossipgoblin/.MainActivity
```

Grant microphone access to see the live graph. **Off** shows levels without saving audio. Tap the graph to arm: **Waiting** saves nothing below the threshold; **Recording** saves qualifying sound. Tap again or use notification Stop to disarm and finalize one file containing the saved portions. Quiet gaps are omitted. Adjust the threshold with the slider. Files use the shared journal layout in `docs/design.md`.

## Verify

Disarm and leave the app first. The suite checks RMS/gating, graph controls/rendering, compacted AAC output, timeline mappings, migration, and failure recovery:

```sh
bash tools/test-device.sh 192.168.0.101:5555
```

See `docs/testing.md` for manual interruption and screen-off checks. Product behavior is defined in `docs/design.md`; implemented components and current validation results are in `docs/architecture.md`.

# Validation

Build, unit tests, lint, and device integration: `bash tools/test-device.sh DEVICE_SERIAL`. Disarm and leave the app first so its live microphone service stops. The script requires authorized ADB, wakes the display without unlocking it, installs with push mode, and checks the instrumentation summary rather than trusting the shell exit code alone.

`./gradlew testDebugUnitTest` runs deterministic RMS, strict-threshold/hysteresis, and history tests. Device tests use synthetic PCM for saved audio so results do not depend on room noise. The live graph test uses the real microphone at a 0 dBFS threshold to verify off/waiting behavior without saving ambient samples. Test-only activity flags allow their own UI above keyguard; no device lock settings are changed.

## Manual checks

1. Open the app and grant microphone permission. Confirm Off, a live scrolling last-30-seconds trace, current dBFS, and an adjustable horizontal threshold. Off must create no recording file.
2. Tap the graph to arm. Confirm Waiting below threshold and Recording above it. Make sound, pause below threshold, and make sound again. Tap off: exactly one M4A should finalize, joining the loud portions with quiet gaps omitted.
3. Confirm the file uses the arming date/time and the journal layout in `docs/design.md`. A wholly quiet armed period should create no file. Existing names must never be overwritten.
4. While armed, leave the app or turn the screen off. Monitoring should continue and save only qualifying sound. Notification Stop must disarm; when the app is hidden this also stops microphone use. Returning to an already-armed service should reflect its state; reopening after Stop/process death must remain off.
5. Rotate/recreate the activity: retain the armed session, graph, and threshold. Leaving while off must release microphone access; reopening resumes live analysis off.
6. Interrupt microphone access or force-stop during saving. Confirm no automatic rearming. Handled interruptions should finalize audio when possible; process-death recovery removes unpublished output. Published files must survive catalog errors.

The automated native graph preview and synthetic compacted M4A are copied to ignored `tmp/` for inspection. Measured results and remaining limits are recorded in `docs/architecture.md`; the graph history itself is not persisted.

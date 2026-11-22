# Implemented architecture

## Platform and tooling

GossipGoblin 0.2 is a Java Android app, minimum API 31 and target/compile API 35. Builds use JDK 17, AGP 8.9.2, and the checksum-pinned Gradle 8.11.1 wrapper. The local SDK is in ignored `tmp/android-sdk/`; portable setup is in `README.md`. ADB clients use explicit IPv4 to the local server; the authorized Green Monster is K-M10P, serial `202412250354`, Android 12, currently `192.168.0.101:5555`.

The earlier permission-free deployment probe remains in `tools/deployment-check/`. Product behavior follows `docs/design.md`, including the explicitly accepted departure from the original continuous-recording rule in `task/vision.md`.

## Live analysis and graph

- `MainActivity` opens live analysis after microphone permission, without arming. An activity visibility count and a 500 ms grace period preserve analysis across recreation while stopping disarmed microphone use after the app leaves view.
- `RecordingService` owns the microphone foreground notification and actual off/armed/recording/finalizing state. Armed analysis continues in the background. Notification Stop disarms; returning to an existing armed service reflects its state. Process/service restart never restores arming.
- `AudioCapture` runs on a dedicated capture worker under a partial wake lock. It assembles 100 ms PCM16 windows, preserving read boundaries through `RmsAnalyzer`, and tags only windows wholly belonging to one arming token as eligible for saving.
- `SoundHistory` holds at most 320 level/status samples and expires data beyond 30 seconds. `SoundGraphView` draws relative time, RMS dBFS, saved-window highlights, and the current threshold at 10 Hz while visible. Standard View click/accessibility handling toggles arming. `SoundSettings` persists sensitivity with a volatile capture-readable value.

## One compacted file per armed period

`ArmedSession` owns a 64-window bounded queue; the capture worker never performs file or database I/O. `ThresholdGate` selects only RMS windows at/above the line, with a separate 6 dB hysteresis latch for detection grouping. Disarming closes admission immediately; already-selected on-period audio is finalized asynchronously. Rearming is disabled during normal finalization.

The serial writer lazily creates one pending MediaStore entry and `AacFile` encoder/muxer after the first selected window. The filename uses the arming-time snapshot from `docs/design.md`. `AacFile` accepts selected PCM with timestamps based on compacted saved-frame counts, keeping one encoder open across quiet gaps. Finalization drains EOS with bounded encoder waits and publishes playable M4A. An entirely quiet armed period creates no file or session row.

Errors end that armed period. Failed/unfinalized media is removed; a finalized file is preserved if catalog completion fails. Startup marks abandoned sessions interrupted, removes owned pending media, and reconciles published files without rearming. Microphone-silencing callbacks end live capture/saving; reopening or explicit interaction can restart analysis disarmed.

## SQLite version 2

`sessions` retains the URI, intended path, arming UTC/monotonic clock pair, timezone, saved PCM-frame count, status/reason, and mode. The non-destructive version-1 upgrade marks legacy records `continuous`; new sessions are `threshold`.

`segments` maps each contiguous saved span from its live source-frame start and estimated UTC/monotonic time to its compacted file-frame start/count, threshold, and detection group. Quiet gaps and threshold changes split spans. Foreign keys cascade segment deletion when a session is removed. Recovery can restore a published threshold file's frame count from committed segments.

## Validation — 2026-11-22

- Build and Android lint pass with no issues; all **10 unit tests** and **10 device tests** pass on the Green Monster.
- Known PCM signals verify RMS scale, chunk-independent windows, and buffer preservation; gate tests verify that below-line audio is never selected, including inside the hysteresis band. History tests verify expiration and bounded memory; a 72-hour frame-position regression verifies overflow-safe monotonic/UTC projection.
- Real AAC/SQLite tests turn two separated loud intervals in a 3.5-second synthetic source into one file with 96,000 saved PCM frames and two segment mappings. Independent FFprobe reports AAC-LC, mono, 48 kHz, 2.048 seconds including codec padding, approximately 64.4 kbit/s; full FFmpeg decoding succeeds.
- Device tests verify off/quiet operation creates no file, collision preservation, legacy schema migration, abandoned-file cleanup, and preservation of finalized audio after injected catalog/timeline failures.
- The live UI test verifies off-mode RMS, graph tap toggles, persisted threshold, recreation retaining the armed session, armed background analysis, the actual notification Stop PendingIntent, and reopening disarmed after Stop. Rendering tests verify threshold-line movement and render the native graph with controlled preview data.
- Test activities may temporarily show their own window above keyguard using public activity flags; tests do not unlock the device or change its PIN/settings. No debugger forward or microphone service remains after testing; version 0.2 is installed and saving is off.

## Current limits

The recording list, Media3 playback/sharing UI, and precise wall-clock correlation remain future work. Segment mappings preserve omitted gaps, but capture timing is still an estimate from the microphone-start clock pair: hardware anchors, dropped-frame accounting, and AAC priming/padding compensation are not implemented. Only Android 12 has device coverage; newer Android service/permission handling and insets are compiled but not yet device-tested.

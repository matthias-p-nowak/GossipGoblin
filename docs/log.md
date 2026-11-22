# Implementation decisions

- Use Debian-packaged ADB and build utilities for the deployment probe. A single Smali activity avoids introducing a product build system solely for this check; choose the product toolchain when starting GossipGoblin.
- Keep the probe in a separate package, `org.gossipgoblin.deploymentcheck`, with generated artifacts and its reusable debug key in ignored `tmp/deployment-check/`.
- Implement the product as a Java Android app with framework views, minimum API 31, and Gradle/AGP tooling. Use AudioRecord plus MediaCodec/MediaMuxer rather than a capture wrapper so future analysis can consume the same PCM stream.
- Use 48 kHz PCM16 capture, a dedicated live-analysis worker, and a serial file/catalog worker. Include pending entries explicitly in MediaStore collision/recovery queries; Android's default collection query omitted them in device testing. Keep finalized audio if catalog completion fails, allowing later reconciliation.
- Use fixed 100 ms RMS windows, a -120 dBFS silence display floor, and an initial configurable threshold of -35 dBFS (-90 to 0). These are initial tuning values; the threshold is retained between sessions.
- Use ADB push installation (`--no-streaming`) in the device-test script after a streaming install stalled over Wi-Fi and push installation succeeded.
- Select complete 100 ms windows strictly at/above the threshold; use 6 dB hysteresis only for logical detection grouping. Discard windows crossing arming transitions. Keep a 64-window bounded queue between live capture and the serial encoder/catalog worker.
- Create a file only after the first selected window, but name it from the arming instant. Store each contiguous saved span as a segment mapping. Preserve version-1 records as `continuous` when upgrading the SQLite catalog to version 2.

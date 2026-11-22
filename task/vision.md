# **GossipGoblin — Requirements Summary**

**Tagline:** You talk. The goblin remembers.

## **1. Platform**

- Native Android application.
- Android 12 or later (API 31+).

## **2. Audio recording**

- Continuously record audio while monitoring is enabled.
- Use M4A with AAC-LC encoding.
- Mono, approximately 64 kbit/s.
- Do not create separate audio files for detected sounds.
- Rotate recordings into longer files when appropriate, without gaps.
- Run recording in an Android microphone foreground service.
- Show a persistent notification while recording.

## **3. Sound detection**

- Continuously calculate RMS sound levels.
- Display levels in dBFS.
- Configurable detection threshold.
- Use hysteresis to prevent repeated triggers around the threshold.
- Store threshold crossings as event markers in metadata.
- Do not interrupt recording when events are detected.

## **4. Time correlation**

A key requirement is to know the **actual local date and time of any position in a recording**.

- Maintain a mapping between audio frame positions and UTC timestamps.
- Use monotonic clock timestamps for reliable elapsed-time correlation.
- Store periodic timing anchors.
- Store timezone information.
- Account for encoder buffering, delay, and dropped frames.
- Display the corresponding wall-clock time during playback.

## **5. Storage**

Intended directory structure:

```
<Audio>/Gossip/yyyy/mm/dd/
```

Use SQLite for metadata:

- Recording information.
- Event markers.
- Audio frame positions.
- Timing anchors.
- Timezone and boot-session information.

Audio files must remain independently playable and shareable.

## **6. User interface**

Keep the UI simple, with two vertically stacked panels.

**Upper panel — Recordings**

- Recordings listed newest first.
- Date, start time, and duration.
- Playback and sharing actions.
- Access to event markers.

**Lower panel — Sound history**

- Continuously scrolling sound-level graph.
- Initially show the last 30 seconds.
- Current RMS level in dBFS.
- Configured threshold line.
- Visible threshold crossings.

During playback, the graph shows the recording's sound history and playback position.

**Monitoring control**

- Simple start/stop toggle.
- Reflect actual foreground-service state.
- Green indication when monitoring is active.

## **7. Playback**

Use **AndroidX Media3 ExoPlayer**, integrated into GossipGoblin.

Required features:

- Play/pause.
- Seek anywhere in a recording.
- Skip backward/forward, e.g. 10 seconds.
- Adjust playback speed.
- Jump to previous/next detected event.
- Seek to a specific wall-clock time.
- Display actual local date and time at the current playback position.

Playback should be independent of the recording service, allowing monitoring and playback to coexist where the device supports it.

## **8. Sharing**

- Use the native Android Sharesheet.
- Share complete recording files.
- Use content URIs with temporary read permissions.
- Do not expose filesystem paths.

## **9. Initial development priorities**

1. Reliable continuous audio recording.
2. Foreground service and monitoring controls.
3. RMS analysis and threshold detection.
4. Sound-level graph.
5. SQLite metadata and event markers.
6. Accurate audio-to-wall-clock correlation.
7. Recording list.
8. Integrated ExoPlayer playback and event navigation.
9. Sharing recordings.

## **10. Open design decisions**

- App-specific storage or user-selected audio directory?
- Recording rotation policy?
- Exact event start/end and merging rules?
- How frequently should timing anchors be stored?
- Should sound-level history be persisted or reconstructed?
- Recovery after process termination, reboot, or microphone interruption?
- Should exporting individual event clips be supported later?

**Most important architectural principle:** Audio is continuous; sound detections are metadata. The recording timeline, event markers, and wall-clock timestamps must remain accurately correlated.
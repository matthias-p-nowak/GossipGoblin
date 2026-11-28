# Gossip Goblin — Product Vision

## Purpose
Gossip Goblin is an Android application for monitoring ambient audio and selectively preserving sound above a user-adjustable volume threshold. It combines a live level display, timestamp-aware recording, playback, and Android sharing.

## Core interaction
The main screen has two regions:

- **Upper region — Recordings:** A list of recording sessions, displayed by hour and minute. Users can select a session for playback or select multiple sessions to share using Android's standard share functionality.
- **Lower region — Live monitor:** A continuously scrolling chart of measured audio level versus time while the app is open. A horizontal threshold line can be moved by touch. Tapping the chart toggles recording mode on or off.

The microphone is monitored continuously while the monitoring service is active, including when the app is in the background. When recording mode is off, the app displays levels but does not save audio. When recording mode is on, only threshold-triggered audio is preserved.

## Recording sessions

1. Switching recording mode **on** starts a new recording session, with its own audio file and companion timing metadata file.
2. Audio level is measured continuously and recent samples are retained in a ring buffer.
3. When the level exceeds the threshold, saved audio includes the preceding **0.5 seconds** from the ring buffer.
4. When the level falls below the threshold, saved audio continues for **0.5 seconds** afterward. A new threshold crossing during that interval should continue the current segment rather than create an unnecessary break.
5. Triggered segments within one session are concatenated into **one audio file**; silence between segments is omitted.
6. The companion metadata maps positions in the concatenated audio to actual wall-clock timestamps, preserving the locations and durations of omitted intervals.
7. Switching recording mode **off** finalizes the session's audio and metadata files. The next activation starts a new session, not a continuation of the previous one.

A recording's filename encodes its start timestamp. Recordings are stored in a journal-like directory hierarchy:

```text
Recordings/
  YYYY/
    MM/
      DD/
        <session-start-timestamp>.<audio-extension>
        <session-start-timestamp>.<metadata-extension>
```

Exact file formats and timestamp encoding remain implementation decisions. Metadata must be sufficient to reconstruct the correct clock time at any playable audio position, including across removed gaps.

## Visual recording indicator
A semi-transparent circular indicator appears at the center of the live chart. It contains six alternating light and dark sectors. It rotates slowly clockwise while recording mode is enabled and stops rotating when recording mode is disabled. This represents the recording **mode**, even during intervals below the threshold.

## Playback
Selecting a recording from the upper list replaces the lower live chart with a playback panel. The panel supports listening, seeking backward and forward, and showing the exact original clock time corresponding to the current playback position. The time display must use the companion timing metadata rather than assume that audio-file elapsed time equals wall-clock elapsed time.

## Sharing
The recordings list supports selecting one or multiple sessions and passing them to Android's standard share sheet. Whether companion metadata is included alongside audio should be decided during implementation.

## Implementation guidance
Build the application incrementally. Preserve the distinction between continuous microphone monitoring, an enabled recording session, and actual threshold-triggered audio segments. Treat this document as the product vision, not a prescribed architecture; clarify unspecified details before committing to irreversible technical choices.

## Open decisions
- Audio encoding/container and metadata format.
- How the app handles Android microphone/background-service permissions and system restrictions.
- Whether the level chart keeps running behind the playback panel.
- Behavior when a session has no threshold-triggered audio.
- Handling of interruptions, storage exhaustion, and process termination.
- Inclusion of timing metadata in shared exports.

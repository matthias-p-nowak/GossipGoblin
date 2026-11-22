# Design

## Authority and accepted change

`task/vision.md` supplies the product baseline. The user explicitly replaced its continuous-audio rule with threshold-gated saving and selected one concatenated file per armed session. This accepted change is recorded in `docs/requests.md`; `task/` remains unchanged. Deployment instructions are in `docs/deployment.md`.

## Live graph and controls

Opening the app requests microphone permission if needed and starts live analysis. A new process starts **off/disarmed**; returning to an existing armed service reflects that session’s actual state. Show the last 30 seconds on a scrolling graph: time on the x-axis, RMS dBFS on the y-axis, and a horizontal line for the adjustable threshold. Graph taps and accessible click/keyboard actions toggle arming. Indications distinguish off (live levels only), armed/waiting, recording, and finalizing a previous file.

Off never saves audio: microphone samples are analyzed in bounded memory only. When armed, save only windows whose RMS is at least the configured threshold. Below-threshold windows are omitted, including those within the hysteresis band. A 6 dB lower release boundary groups threshold detections without repeatedly starting new logical events around the threshold; it does not authorize saving below the displayed line. Changing the threshold takes effect on the next window.

Live analysis continues while the app is visible, including while disarmed. Armed analysis/saving continues when the screen is off or another app opens. Leaving the app disarmed stops microphone access after a short lifecycle grace period; reopening restarts analysis disarmed. A microphone foreground notification describes the current mode and offers Stop while armed. Stop disarms; it retains live analysis if the app remains visible.

## Recording sessions and storage

Each off-to-armed transition begins one session. Create its M4A lazily on the first qualifying window; an entirely quiet session creates no recording file. Keep one encoder/muxer open across quiet periods, submitting only selected PCM with contiguous output timestamps. Disarming finalizes the file. Playback joins the saved portions without quiet gaps. Do not rotate by hour or midnight, and do not create a file for each sound.

Use MediaStore shared audio storage: `Recordings/Gossip/yyyy/MM/dd/rec-HHmmss.m4a`. Date and six-digit 24-hour filename use one local arming-time snapshot, with zero-padded components. A session remains in that directory across midnight. Never overwrite or silently rename an existing file; a collision ends saving and reports the reason. Finalized files survive uninstall; private metadata/settings do not.

Capture mono PCM16 at 48 kHz, encode AAC-LC at approximately 64 kbit/s, and package M4A using MediaMuxer. Keep entries pending until finalization. Delete unfinalized output during recovery; preserve published audio if a subsequent catalog update fails.

## Analysis and timeline foundation

Use consecutive 100 ms RMS windows, preserving boundaries across reads and flushing a final partial window when analysis ends. Calculate `20 * log10(sqrt(sum(sample²) / count) / 32768)`, with a -120 dBFS silence floor and 0 dBFS upper clamp. A full-scale sine is approximately -3 dBFS under this convention. Persist the adjustable threshold, initially -35 dBFS, range -90 to 0 in 1 dB steps.

Analyze every live window. Only accept audio for saving if its entire capture window belongs to the same armed session; discard windows straddling arm/disarm boundaries. Use a bounded queue between microphone capture and file/SQLite work so storage latency cannot block microphone reads. A full queue or writer error ends that armed session and reports the error.

Store private SQLite sessions plus saved-segment mappings: continuous live-frame position, monotonic/UTC start estimates, compacted output-frame position/count, detection group, and threshold. Split a mapping at a quiet gap or threshold change. These preserve omitted gaps for later playback correlation; hardware timestamp anchors, dropped-frame accounting, and AAC priming/padding compensation remain future work. Do not claim exact wall-clock playback correlation yet. Live graph history is bounded in memory, not persisted.

## Interruptions and lifecycle

Microphone loss, capture/storage failure, process death, or reboot ends saving; never automatically rearm or resume a file. A handled interruption attempts finalization and releases resources. Analysis may be restarted disarmed by reopening the app or explicit interaction. Abrupt termination need not yield a playable M4A; startup marks abandoned sessions interrupted and removes unpublished output. A metadata error after finalization must preserve the audio.

Activity recreation must retain the live stream and armed session. Disable rearming while the prior file is finalizing. Screen-off/backgrounding while armed is normal operation. The full recording list, Media3 playback/sharing, and precise time correlation remain subsequent milestones.

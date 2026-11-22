package org.gossipgoblin;

import android.content.Context;
import android.os.SystemClock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One armed period: bounded selected PCM queue, one lazy file, and saved-segment metadata. */
final class ArmedSession implements Runnable {
    /** Session identity and intended journal path, captured at arming time. */
    final long token;
    final String path;
    private final Instant start;
    private final ZoneId zone;
    private final long armedElapsedNs;
    private final Context context;
    private final RecordingStore store;
    private final Consumer<Outcome> completed;
    /** Capture-thread gating; queue admission and close share a short non-I/O lock. */
    private final ThresholdGate gate = new ThresholdGate();
    private final ArrayBlockingQueue<Selected> queue = new ArrayBlockingQueue<>(64);
    private volatile boolean accepting = true;
    volatile String failure;
    volatile Outcome outcome;
    volatile long savedFrames;
    /** Writer-thread-only file and contiguous saved span. */
    private RecordingStore.Session row;
    private AacFile file;
    private AudioCapture.Window segmentStart;
    private long segmentFileFrame, segmentFrames;
    private int segmentThreshold, segmentEvent;

    ArmedSession(Context context, RecordingStore store, long token, Instant start, ZoneId zone, Consumer<Outcome> completed) {
        this.context = context; this.store = store; this.token = token; this.start = start;
        this.zone = zone; this.completed = completed;
        armedElapsedNs = SystemClock.elapsedRealtimeNanos();
        RecordingName name = new RecordingName(start, zone);
        path = name.directory + name.filename;
    }

    /** Admit only windows wholly captured during this session and above the current threshold. */
    synchronized boolean offer(AudioCapture.Window window, int threshold) {
        if (!accepting || window.armToken != token) return false;
        int event = gate.select(window.reading.dbfs, threshold);
        if (event == 0) return false;
        if (!queue.offer(new Selected(window, threshold, event))) {
            failure = "Storage could not keep up with recording.";
            accepting = false;
            return false;
        }
        return true;
    }

    /** Stop accepting new input immediately; previously selected on-period audio is finalized. */
    synchronized void close(String reason) {
        if (reason != null && failure == null) failure = reason;
        accepting = false;
    }

    @Override public void run() {
        boolean playable = false;
        try {
            while (accepting || !queue.isEmpty()) {
                Selected selected = queue.poll(100, TimeUnit.MILLISECONDS);
                if (selected == null) continue;
                if (row == null) {
                    row = store.create(start, zone, armedElapsedNs);
                    file = new AacFile(context, row);
                }
                write(selected);
            }
        } catch (Exception error) {
            failure = message(error);
            accepting = false;
            queue.clear();
        } finally {
            if (file != null) {
                try { playable = file.finish(); }
                catch (Exception error) { failure = "Could not finalize audio: " + message(error); }
                savedFrames = file.frames;
                try { flushSegment(); }
                catch (Exception error) { failure = "Could not save timeline metadata: " + message(error); }
            }
            boolean saved = false;
            if (row != null) {
                try {
                    store.finish(row, playable, savedFrames, failure);
                    saved = playable;
                } catch (Exception error) {
                    failure = "Could not complete recording metadata: " + message(error);
                    // Publication may have succeeded before the catalog update failed.
                    saved = playable && store.isPublished(row);
                }
            }
            accepting = false;
            outcome = new Outcome(row, saved, savedFrames, failure);
            completed.accept(outcome);
        }
    }

    /** Encode selected audio and map accepted output frames back onto the live timeline. */
    private void write(Selected selected) throws Exception {
        AudioCapture.Window window = selected.window;
        if (segmentStart != null && (window.reading.firstFrame != segmentStart.reading.firstFrame + segmentFrames
                || selected.threshold != segmentThreshold || selected.event != segmentEvent)) flushSegment();
        if (segmentStart == null) {
            segmentStart = window; segmentFileFrame = file.frames; segmentFrames = 0;
            segmentThreshold = selected.threshold; segmentEvent = selected.event;
        }
        long before = file.frames;
        try { file.write(window.pcm); }
        finally { segmentFrames += file.frames - before; savedFrames = file.frames; }
    }

    /** Persist one contiguous saved span; quiet gaps create separate mappings in the same file. */
    private void flushSegment() {
        if (segmentStart != null && segmentFrames > 0) {
            store.addSegment(row, segmentStart.reading.firstFrame, segmentFileFrame, segmentFrames,
                    segmentStart.startUtcMs(), segmentStart.startNs(), segmentThreshold, segmentEvent);
        }
        segmentStart = null; segmentFrames = 0;
    }

    /** Preserve a useful reason when a platform exception has no message. */
    private String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }

    /** Queue item captures the setting and detection group used at selection time. */
    private static final class Selected {
        final AudioCapture.Window window;
        final int threshold, event;
        Selected(AudioCapture.Window window, int threshold, int event) {
            this.window = window; this.threshold = threshold; this.event = event;
        }
    }

    /** Final session result; a quiet armed period has no row and no file. */
    static final class Outcome {
        final RecordingStore.Session row;
        final boolean saved;
        final long frames;
        final String error;
        Outcome(RecordingStore.Session row, boolean saved, long frames, String error) {
            this.row = row; this.saved = saved; this.frames = frames; this.error = error;
        }
    }
}

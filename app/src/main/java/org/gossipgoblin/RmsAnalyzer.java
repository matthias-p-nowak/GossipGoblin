package org.gossipgoblin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/** Streaming mono PCM16 RMS with read-independent windows and capture-frame positions. */
final class RmsAnalyzer {
    /** Display floor used for digital silence. */
    static final double FLOOR_DBFS = -120.0;
    /** Analysis cadence, callback, and partially accumulated window. */
    private final int windowFrames;
    private final Consumer<Reading> listener;
    private int count;
    private double sumSquares;
    private long firstFrame;

    RmsAnalyzer(int sampleRate, Consumer<Reading> listener) {
        if (sampleRate < 10 || sampleRate % 10 != 0) {
            throw new IllegalArgumentException("Sample rate must support integral 100 ms windows");
        }
        this.windowFrames = sampleRate / 10;
        this.listener = listener;
    }

    /** Read AudioRecord's native-endian data from offset zero without modifying its buffer. */
    void accept(ByteBuffer pcm, int bytes) {
        if (bytes < 0 || bytes > pcm.capacity() || bytes % 2 != 0) {
            throw new IllegalArgumentException("Expected complete PCM16 frames within the buffer");
        }
        ByteBuffer samples = pcm.duplicate().order(ByteOrder.nativeOrder());
        samples.clear();
        for (int offset = 0; offset < bytes; offset += 2) {
            int sample = samples.getShort(offset);
            sumSquares += (double) sample * sample;
            if (++count == windowFrames) emit();
        }
    }

    /** Include the final partial window exactly once when capture ends. */
    void finish() { if (count > 0) emit(); }

    /** Publish a completed window and advance the absolute PCM-frame origin. */
    private void emit() {
        double rms = Math.sqrt(sumSquares / count) / 32768.0;
        double dbfs = rms == 0 ? FLOOR_DBFS : Math.max(FLOOR_DBFS, Math.min(0, 20 * Math.log10(rms)));
        Reading reading = new Reading(firstFrame, count, dbfs);
        firstFrame += count;
        count = 0;
        sumSquares = 0;
        listener.accept(reading);
    }

    /** A measured sample window; positions refer to PCM, before AAC priming/padding. */
    static final class Reading {
        final long firstFrame;
        final int frameCount;
        final double dbfs;
        Reading(long firstFrame, int frameCount, double dbfs) {
            this.firstFrame = firstFrame;
            this.frameCount = frameCount;
            this.dbfs = dbfs;
        }
    }
}

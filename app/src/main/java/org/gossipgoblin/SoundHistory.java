package org.gossipgoblin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded in-memory live graph history shared by capture and UI threads. */
final class SoundHistory {
    /** The visible window and a hard bound even if a clock stops advancing. */
    static final long SPAN_NS = 30_000_000_000L;
    private static final int CAPACITY = 320;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    /** Add one immutable window-end sample; recording means accepted for the armed file. */
    synchronized void add(long elapsedNs, double dbfs, boolean recorded) {
        samples.addLast(new Sample(elapsedNs, dbfs, recorded));
        prune(elapsedNs);
        while (samples.size() > CAPACITY) samples.removeFirst();
    }

    /** Return a stable draw snapshot and expire old values even after capture stops. */
    synchronized List<Sample> snapshot(long nowNs) {
        prune(nowNs);
        return new ArrayList<>(samples);
    }

    /** Remove values outside the scrolling range. */
    private void prune(long nowNs) {
        while (!samples.isEmpty() && samples.peekFirst().elapsedNs < nowNs - SPAN_NS) samples.removeFirst();
    }

    /** A level and its saving status at one monotonic position in the live timeline. */
    static final class Sample {
        final long elapsedNs;
        final double dbfs;
        final boolean recorded;
        Sample(long elapsedNs, double dbfs, boolean recorded) {
            this.elapsedNs = elapsedNs; this.dbfs = dbfs; this.recorded = recorded;
        }
    }
}

package org.gossipgoblin;

/** Strict saving threshold with hysteresis for grouping logical detections. */
final class ThresholdGate {
    /** Lower boundary used to rearm detection grouping, never to save quiet audio. */
    static final int HYSTERESIS_DB = 6;
    /** Capture-thread-only latch and monotonically increasing group number. */
    private boolean active;
    private int event;

    /** Return the detection group for a selected window, or zero when nothing should be saved. */
    int select(double dbfs, int threshold) {
        if (!Double.isFinite(dbfs)) return 0;
        if (active && dbfs <= threshold - HYSTERESIS_DB) active = false;
        if (!active && dbfs >= threshold) { active = true; event++; }
        return active && dbfs >= threshold ? event : 0;
    }
}

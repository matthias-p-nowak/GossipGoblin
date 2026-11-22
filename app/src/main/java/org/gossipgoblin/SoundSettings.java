package org.gossipgoblin;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted threshold with a volatile value safe for capture-thread reads. */
final class SoundSettings {
    /** Initial sensitivity and adjustable dBFS bounds. */
    static final int DEFAULT_THRESHOLD = -35, MIN_THRESHOLD = -90, MAX_THRESHOLD = 0;
    /** Preference storage and the current capture-visible value. */
    private final SharedPreferences preferences;
    private volatile int threshold;

    SoundSettings(Context context) {
        preferences = context.getSharedPreferences("sound", Context.MODE_PRIVATE);
        threshold = preferences.getInt("threshold_dbfs", DEFAULT_THRESHOLD);
        if (threshold < MIN_THRESHOLD || threshold > MAX_THRESHOLD) threshold = DEFAULT_THRESHOLD;
    }

    /** Return one atomic threshold snapshot for an analysis window. */
    int thresholdDbfs() { return threshold; }

    /** Apply a valid setting immediately and persist it without blocking capture. */
    void setThresholdDbfs(int value) {
        if (value < MIN_THRESHOLD || value > MAX_THRESHOLD) throw new IllegalArgumentException("Threshold out of range");
        threshold = value;
        preferences.edit().putInt("threshold_dbfs", value).apply();
    }
}

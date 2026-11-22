package org.gossipgoblin;

import android.app.Application;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-local analysis, serial file writer, and actual service state. */
public final class GoblinApp extends Application {
    /** Separate workers prevent storage latency from blocking microphone reads. */
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final ExecutorService captureWorker = Executors.newSingleThreadExecutor();
    final SoundHistory history = new SoundHistory();
    /** Settings/catalog are initialized before any activity or service starts. */
    RecordingStore store;
    SoundSettings soundSettings;
    /** Immutable/volatile snapshots never persist an armed flag across process death. */
    volatile State state = new State("Off", "Open the app for live sound levels.", false, false, false, false, "");
    volatile RmsAnalyzer.Reading latestReading;
    volatile boolean visible;
    /** Main-thread count handles rotation and more than one activity instance safely. */
    private int visibleActivities;
    volatile String lastSavedPath = "";

    @Override public void onCreate() {
        super.onCreate();
        soundSettings = new SoundSettings(this);
        store = new RecordingStore(this);
        worker.execute(() -> {
            try {
                if (store.recoverInterrupted() > 0 && !state.listening && !state.busy) {
                    state = new State("Off", "Previous recording was interrupted.", false, false, false, false, "");
                }
            } catch (Exception error) {
                if (!state.listening && !state.busy) state = new State("Off",
                        "Could not recover previous recording: " + error.getMessage(), false, false, false, false, "");
            }
        });
    }

    /** Keep analysis active while any app activity remains started. */
    void activityStarted() { visible = ++visibleActivities > 0; }

    /** Defer microphone shutdown until the final visible activity leaves. */
    void activityStopped() { visibleActivities = Math.max(0, visibleActivities - 1); visible = visibleActivities > 0; }

    /** Coherent service state; listening and arming are distinct from selected audio saving. */
    static final class State {
        final String title, detail, path;
        final boolean listening, armed, recording, finishing, busy;
        State(String title, String detail, boolean listening, boolean armed, boolean recording, boolean finishing, String path) {
            this.title = title; this.detail = detail; this.path = path;
            this.listening = listening; this.armed = armed; this.recording = recording; this.finishing = finishing;
            this.busy = armed || finishing;
        }
    }
}

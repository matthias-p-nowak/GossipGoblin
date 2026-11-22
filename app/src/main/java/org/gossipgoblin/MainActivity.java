package org.gossipgoblin;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Always-live sound graph with explicit off/armed/recording feedback and tap-controlled saving. */
public final class MainActivity extends Activity {
    /** Permission requests and semantic status colors. */
    private static final int MICROPHONE = 1, NOTIFICATIONS = 2;
    private static final int GREEN = Color.rgb(35, 123, 78), INK = Color.rgb(30, 42, 35), AMBER = Color.rgb(154, 99, 17);
    /** UI widgets and cached render snapshots. */
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GoblinApp app;
    private TextView status, detail, filename, rmsLevel, thresholdLabel;
    private SeekBar thresholdControl;
    private SoundGraphView graph;
    private GoblinApp.State renderedState;
    private RmsAnalyzer.Reading renderedReading;
    private boolean renderedListening;
    private int renderedThreshold = Integer.MIN_VALUE;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 200); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (GoblinApp) getApplication();
        getWindow().setStatusBarColor(INK); getWindow().setNavigationBarColor(INK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(24));
        root.setBackgroundColor(Color.rgb(245, 247, 241));
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(22) + bars.left, dp(20) + bars.top, dp(22) + bars.right, dp(24) + bars.bottom);
            WindowInsetsController controller = view.getWindowInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2)); setContentView(scroll);
        root.addView(text("GossipGoblin", 28, true));
        root.addView(text("You talk. The goblin remembers.", 15, false));
        LinearLayout recordings = panel();
        recordings.addView(text("RECORDING", 12, true));
        filename = text("No saved recording yet", 16, false); filename.setTextIsSelectable(true);
        recordings.addView(filename); root.addView(recordings);
        LinearLayout monitoring = panel();
        status = text("Off", 25, true); status.setId(R.id.monitoring_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); monitoring.addView(status);
        detail = text("Live levels only • audio saving is off", 15, false); monitoring.addView(detail);
        graph = new SoundGraphView(this); graph.setOnClickListener(view -> toggleArming());
        monitoring.addView(graph, new LinearLayout.LayoutParams(-1, dp(260)));
        monitoring.addView(text("Tap the graph to arm or turn saving off", 13, false));
        rmsLevel = text(getString(R.string.rms_unavailable), 20, true); rmsLevel.setId(R.id.rms_level);
        monitoring.addView(rmsLevel);
        thresholdLabel = text("", 15, false); monitoring.addView(thresholdLabel);
        thresholdControl = new SeekBar(this); thresholdControl.setId(R.id.threshold_control);
        thresholdControl.setMax(SoundSettings.MAX_THRESHOLD - SoundSettings.MIN_THRESHOLD);
        thresholdControl.setProgress(app.soundSettings.thresholdDbfs() - SoundSettings.MIN_THRESHOLD);
        thresholdControl.setContentDescription(getString(R.string.threshold_accessibility));
        thresholdControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar control, int progress, boolean fromUser) {
                if (fromUser) { app.soundSettings.setThresholdDbfs(progress + SoundSettings.MIN_THRESHOLD); render(); graph.invalidate(); }
            }
            @Override public void onStartTrackingTouch(SeekBar control) { }
            @Override public void onStopTrackingTouch(SeekBar control) { }
        });
        monitoring.addView(thresholdControl, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(monitoring);
    }

    /** A simple vertical panel; the containing scroll view handles smaller displays. */
    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(12), 0, dp(10)); return panel;
    }

    /** Create a consistently styled text label. */
    private TextView text(String value, int size, boolean bold) {
        TextView label = new TextView(this); label.setText(value); label.setTextSize(size); label.setTextColor(INK);
        label.setPadding(0, dp(3), 0, dp(7)); if (bold) label.setTypeface(null, Typeface.BOLD); return label;
    }

    /** Convert view spacing to density-independent pixels. */
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Open live analysis disarmed, asking permissions only from the visible activity. */
    private void ensureLive(boolean explicitPermissionRequest) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (explicitPermissionRequest || !getPreferences(MODE_PRIVATE).getBoolean("micAsked", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("micAsked", true).apply();
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS); return;
        }
        command(RecordingService.LISTEN);
    }

    /** Graph click toggles saving; permission grant alone never arms the app. */
    private void toggleArming() {
        if (app.state.finishing) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ensureLive(true); return; }
        command(app.state.armed ? RecordingService.STOP : RecordingService.ARM);
    }

    /** Issue an explicit same-app service command and surface platform start failures. */
    private void command(String action) {
        try {
            Intent intent = new Intent(this, RecordingService.class).setAction(action);
            if (RecordingService.STOP.equals(action)) startService(intent);
            else startForegroundService(intent);
        }
        catch (RuntimeException error) {
            app.state = new GoblinApp.State("Off", "Could not start live levels: " + error.getMessage(),
                    false, false, false, false, app.lastSavedPath);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == MICROPHONE && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            app.state = new GoblinApp.State("Off", "Microphone permission is needed for the live graph. Enable it in app settings.",
                    false, false, false, false, app.lastSavedPath);
        } else if (code == MICROPHONE || code == NOTIFICATIONS) ensureLive(false);
    }

    /** Refresh only changed values; the graph scrolls independently at 10 Hz. */
    private void render() {
        GoblinApp.State state = app.state;
        RmsAnalyzer.Reading reading = app.latestReading;
        if (reading != renderedReading || state.listening != renderedListening) {
            renderedReading = reading; renderedListening = state.listening;
            rmsLevel.setText(state.listening && reading != null ? getString(R.string.rms_level, reading.dbfs) : getString(R.string.rms_unavailable));
        }
        int threshold = app.soundSettings.thresholdDbfs();
        if (threshold != renderedThreshold) {
            renderedThreshold = threshold;
            thresholdLabel.setText(getString(R.string.threshold_level, threshold));
            thresholdControl.setProgress(threshold - SoundSettings.MIN_THRESHOLD);
            thresholdControl.setStateDescription(getString(R.string.threshold_value, threshold));
        }
        if (state == renderedState) return;
        renderedState = state;
        status.setText(state.title); status.setTextColor(state.recording ? GREEN : state.armed ? AMBER : INK);
        detail.setText(state.detail);
        filename.setText(state.path.isEmpty() ? "No saved recording yet" : state.path);
        graph.setEnabled(!state.finishing);
        graph.setStateDescription(state.title);
    }

    @Override protected void onStart() { super.onStart(); app.activityStarted(); ensureLive(false); }
    @Override protected void onStop() {
        app.activityStopped();
        if (!app.visible && (app.state.listening || app.state.busy)) startService(new Intent(this, RecordingService.class).setAction(RecordingService.HIDE));
        super.onStop();
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}

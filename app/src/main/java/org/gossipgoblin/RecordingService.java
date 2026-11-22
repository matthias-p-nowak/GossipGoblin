package org.gossipgoblin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;

/** Live microphone foreground service with independent off/armed/saving states. */
public final class RecordingService extends Service {
    /** Same-app commands; Stop always means disarm, not automatic rearming. */
    static final String LISTEN = "org.gossipgoblin.LISTEN", ARM = "org.gossipgoblin.ARM",
            STOP = "org.gossipgoblin.STOP", HIDE = "org.gossipgoblin.HIDE";
    private static final String CHANNEL = "monitoring";
    private static final int NOTIFICATION_ID = 1;
    /** Main-thread lifecycle bookkeeping and cross-thread capture/session controls. */
    private final Handler main = new Handler(Looper.getMainLooper());
    private GoblinApp app;
    private volatile ArmedSession active;
    private volatile boolean stopCapture;
    private boolean loopRunning, listening, finishing, recording, destroyed, wanted;
    private long nextToken;
    private String message = "", lastNotification = "";
    private final Runnable stopWhenHidden = () -> {
        if (!app.visible && active == null) {
            wanted = false;
            stopCapture = true;
            stopIfIdle();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        app = (GoblinApp) getApplication();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Sound monitoring", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopIfIdle(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (STOP.equals(action)) {
            disarm(null);
        } else if (HIDE.equals(action)) {
            main.removeCallbacks(stopWhenHidden);
            main.postDelayed(stopWhenHidden, 500);
        } else if (LISTEN.equals(action) || ARM.equals(action)) {
            wanted = true;
            main.removeCallbacks(stopWhenHidden);
            if (!loopRunning && !ensureListening()) return START_NOT_STICKY;
            if (ARM.equals(action) && active == null && !finishing) {
                message = "";
                ArmedSession session = new ArmedSession(this, app.store, ++nextToken, Instant.now(), ZoneId.systemDefault(),
                        outcome -> main.post(() -> finished(outcome)));
                active = session;
                app.worker.execute(session);
                publish();
            }
        }
        return START_NOT_STICKY;
    }

    /** Start microphone analysis only from a visible/user-initiated service request. */
    private boolean ensureListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message = "Microphone permission is required for live levels.";
            publish(); stopIfIdle(); return false;
        }
        try {
            stopCapture = false; loopRunning = true; message = "";
            app.latestReading = null;
            startForeground(NOTIFICATION_ID, notification("Live levels • saving off"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            app.captureWorker.execute(this::capture);
            publish();
            return true;
        } catch (RuntimeException error) {
            loopRunning = false; message = "Could not open microphone: " + error.getMessage();
            publish(); stopIfIdle(); return false;
        }
    }

    /** Hold the microphone worker's wake lock independently of slow file/catalog work. */
    @SuppressLint("WakelockTimeout")
    private void capture() {
        PowerManager.WakeLock wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GossipGoblin:analysis");
        String error;
        try {
            wakeLock.acquire();
            error = new AudioCapture().listen(this, () -> stopCapture,
                    () -> { ArmedSession session = active; return session == null ? 0 : session.token; },
                    () -> main.post(() -> { listening = true; publish(); }),
                    reason -> main.post(() -> { wanted = false; disarm(reason); stopCapture = true; }), this::window);
        } catch (Exception failure) {
            error = failure.getMessage();
        } finally { if (wakeLock.isHeld()) wakeLock.release(); }
        String result = error;
        main.post(() -> {
            loopRunning = false; listening = false; recording = false;
            if (result != null) { wanted = false; message = result; disarm(result); }
            publish();
            if (wanted && result == null && !destroyed && (app.visible || active != null)) ensureListening();
            else stopIfIdle();
        });
    }

    /** Update the bounded graph and admit selected windows without doing disk I/O. */
    private void window(AudioCapture.Window window) {
        app.latestReading = window.reading;
        ArmedSession session = active;
        boolean selected = session != null && session.offer(window, app.soundSettings.thresholdDbfs());
        app.history.add(window.endNs(), window.reading.dbfs, selected);
        main.post(() -> {
            recording = selected && active == session && !stopCapture;
            if (session != null && session.failure != null && active == session) disarm(session.failure);
            publish();
        });
    }

    /** Immediately forbid new saved samples, then let the file worker drain already-selected input. */
    private void disarm(String reason) {
        ArmedSession session = active;
        active = null; recording = false;
        if (reason != null) message = reason;
        if (session != null) {
            finishing = true;
            session.close(reason);
        }
        publish();
        if (!app.visible) { wanted = false; stopCapture = true; stopIfIdle(); }
        stopIfIdle();
    }

    /** Complete a single file; a writer failure also disarms its still-active session. */
    private void finished(ArmedSession.Outcome outcome) {
        if (active != null && active.outcome == outcome) { active = null; recording = false; }
        finishing = false;
        if (outcome.saved && outcome.row != null) app.lastSavedPath = outcome.row.path;
        message = outcome.error != null ? outcome.error + (outcome.saved ? " Audio saved." : "")
                : outcome.saved ? "Recording saved." : "No sound reached the threshold; no file was created.";
        publish();
        if (!app.visible && active == null) { wanted = false; stopCapture = true; stopIfIdle(); }
    }

    /** Publish only actual mode changes, keeping level refreshes from churning status/accessibility text. */
    private void publish() {
        if (destroyed) return;
        boolean armed = active != null;
        String title = finishing ? "Finishing" : armed ? (recording ? "Recording" : "Waiting") : "Off";
        String detail = !message.isEmpty() ? message : finishing ? "Finishing the saved audio…"
                : armed ? (recording ? "Saving sound above the threshold" : "Armed • waiting for sound")
                : listening ? "Live levels only • audio saving is off" : "Opening live sound levels…";
        String path = armed ? active.path : app.lastSavedPath;
        GoblinApp.State previous = app.state;
        if (!previous.title.equals(title) || !previous.detail.equals(detail) || !previous.path.equals(path)
                || previous.listening != listening || previous.armed != armed || previous.recording != recording
                || previous.finishing != finishing) {
            app.state = new GoblinApp.State(title, detail, listening, armed, recording, finishing, path);
        }
        String text = armed ? (recording ? "Recording above threshold" : "Armed • waiting for sound") : "Live levels • saving off";
        String key = text + armed;
        if (loopRunning && !key.equals(lastNotification) && !destroyed) {
            lastNotification = key;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
        }
    }

    /** Remove the microphone foreground notification only after both workers are done. */
    private void stopIfIdle() {
        if (!loopRunning && !finishing && active == null) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }

    /** Ongoing live-analysis notification; armed sessions expose the native Stop action. */
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_goblin)
                .setContentTitle("GossipGoblin").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true);
        if (active != null) {
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RecordingService.class).setAction(STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        }
        return builder.build();
    }

    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("armed=" + (active != null)); writer.println("finishing=" + finishing);
        writer.println("listening=" + listening);
    }

    @Override public void onDestroy() {
        destroyed = true; main.removeCallbacks(stopWhenHidden);
        stopCapture = true;
        ArmedSession session = active;
        active = null;
        if (session != null) session.close("Monitoring service was interrupted.");
        app.state = new GoblinApp.State("Off", message.isEmpty() ? "Live levels stopped." : message,
                false, false, false, false, app.lastSavedPath);
        super.onDestroy();
    }
}

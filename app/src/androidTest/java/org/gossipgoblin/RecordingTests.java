package org.gossipgoblin;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real encoder, SQLite, graph, and microphone tests for threshold-gated armed sessions. */
public class RecordingTests {
    /** App fixtures and owned jobs/rows to clean up even after an assertion fails. */
    private Instrumentation instrumentation;
    private Context context;
    private GoblinApp app;
    private int originalThreshold;
    private long originNs, originUtcMs;
    private final List<ArmedSession> sessions = new ArrayList<>();
    private final List<Future<?>> jobs = new ArrayList<>();

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext(); app = (GoblinApp) context.getApplicationContext();
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(), Manifest.permission.RECORD_AUDIO);
        app.worker.submit(() -> { }).get(20, TimeUnit.SECONDS);
        assertFalse("Disarm before testing", app.state.busy);
        originalThreshold = app.soundSettings.thresholdDbfs();
        originNs = SystemClock.elapsedRealtimeNanos(); originUtcMs = System.currentTimeMillis();
    }

    @After public void tearDown() throws Exception {
        for (ArmedSession session : sessions) session.close(null);
        context.stopService(new Intent(context, RecordingService.class));
        instrumentation.runOnMainSync(() -> {
            Set<Activity> activities = new HashSet<>();
            for (Stage stage : new Stage[]{Stage.RESUMED, Stage.STARTED, Stage.CREATED, Stage.PAUSED, Stage.STOPPED}) {
                activities.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage));
            }
            for (Activity activity : activities) activity.finish();
        });
        for (Future<?> job : jobs) job.get(30, TimeUnit.SECONDS);
        app.captureWorker.submit(() -> { }).get(15, TimeUnit.SECONDS);
        for (ArmedSession session : sessions) if (session.outcome != null) cleanup(session.outcome.row);
        app.soundSettings.setThresholdDbfs(originalThreshold);
    }

    @Test public void sixDigitNamesUseOneLocalStartInstant() {
        RecordingName name = new RecordingName(Instant.parse("2026-11-22T23:05:07Z"), ZoneId.of("Europe/Helsinki"));
        assertEquals("Recordings/Gossip/2026/11/23/", name.directory);
        assertEquals("rec-010507.m4a", name.filename);
    }

    @Test public void versionOneCatalogUpgradePreservesLegacySessions() {
        try (SQLiteDatabase legacy = SQLiteDatabase.create(null)) {
            legacy.execSQL("CREATE TABLE sessions (_id INTEGER PRIMARY KEY, path TEXT)");
            legacy.execSQL("INSERT INTO sessions VALUES (1,'legacy.m4a')");
            app.store.onUpgrade(legacy, 1, 2);
            try (Cursor row = legacy.rawQuery("SELECT path,mode FROM sessions", null)) {
                assertTrue(row.moveToFirst()); assertEquals("legacy.m4a", row.getString(0));
                assertEquals("continuous", row.getString(1));
            }
            try (Cursor rows = legacy.rawQuery("SELECT COUNT(*) FROM segments", null)) {
                assertTrue(rows.moveToFirst()); assertEquals(0, rows.getInt(0));
            }
        }
    }

    @Test public void exactNameCollisionPreservesOriginal() throws Exception {
        Instant start = Instant.parse("2020-01-02T03:04:05Z");
        RecordingStore.Session original = app.store.create(start, ZoneId.of("UTC"));
        try {
            try (java.io.OutputStream data = new ParcelFileDescriptor.AutoCloseOutputStream(
                    context.getContentResolver().openFileDescriptor(original.uri, "w"))) { data.write(new byte[]{42,43,44}); }
            try { app.store.create(start, ZoneId.of("UTC")); fail("Collision must be rejected"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("already exists")); }
            try (java.io.InputStream data = context.getContentResolver().openInputStream(original.uri)) {
                assertNotNull(data); assertEquals(42, data.read()); assertEquals(43, data.read()); assertEquals(44, data.read()); assertEquals(-1,data.read());
            }
        } finally { app.store.finish(original, false, 0, "Test cleanup"); cleanup(original); }
    }

    @Test public void abandonedPendingAudioIsRemovedWithoutRearming() throws Exception {
        RecordingStore.Session abandoned = app.store.create(Instant.parse("2020-01-02T03:04:06Z"), ZoneId.of("UTC"));
        try {
            try (java.io.OutputStream data = context.getContentResolver().openOutputStream(abandoned.uri)) { assertNotNull(data); data.write(42); }
            assertEquals(1, app.store.recoverInterrupted());
            try (ParcelFileDescriptor ignored = context.getContentResolver().openFileDescriptor(abandoned.uri,"r")) { fail("Pending audio must be removed"); }
            catch (java.io.FileNotFoundException | SecurityException expected) { }
            assertFalse(app.state.armed);
        } finally { cleanup(abandoned); }
    }

    @Test public void quietGapsAreOmittedFromOnePlayableFileWithSegmentMappings() throws Exception {
        ArmedSession session = session(1);
        for (int window = 0; window < 35; window++) {
            boolean loud = window >= 5 && window < 15 || window >= 25;
            assertEquals(loud, session.offer(window(window, loud, 1), -35));
        }
        session.close(null);
        assertFalse("Closed sessions must never admit more audio", session.offer(window(35,true,1),-35));
        await(session);
        assertNull(session.outcome.error); assertTrue(session.outcome.saved); assertEquals(96000,session.outcome.frames);
        assertPlayable(session.outcome.row.uri, 2000000, 2200000);
        try (java.io.InputStream input = context.getContentResolver().openInputStream(session.outcome.row.uri);
                FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(),"gating-preview.m4a"))) {
            assertNotNull(input);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer,0,count);
        }
        try (Cursor rows = app.store.getReadableDatabase().rawQuery(
                "SELECT source_start_frame,file_start_frame,frame_count,event_number FROM segments WHERE session_id=? ORDER BY file_start_frame",
                new String[]{Long.toString(session.outcome.row.id)})) {
            assertEquals(2, rows.getCount());
            assertTrue(rows.moveToNext()); assertEquals(24000,rows.getLong(0)); assertEquals(0,rows.getLong(1));
            assertEquals(48000,rows.getLong(2)); assertEquals(1,rows.getInt(3));
            assertTrue(rows.moveToNext()); assertEquals(120000,rows.getLong(0)); assertEquals(48000,rows.getLong(1));
            assertEquals(48000,rows.getLong(2)); assertEquals(2,rows.getInt(3));
        }
    }

    @Test public void quietOrDisarmedWindowsNeverCreateARecordingFile() throws Exception {
        long before = sessionCount();
        ArmedSession session = session(2);
        assertFalse(session.offer(window(0,true,0),-35));
        assertFalse(session.offer(window(1,true,3),-35));
        for (int i=2;i<15;i++) assertFalse(session.offer(window(i,false,2),-35));
        session.close(null); await(session);
        assertNull(session.outcome.row); assertFalse(session.outcome.saved); assertEquals(0,session.outcome.frames);
        assertEquals(before,sessionCount());
    }

    @Test public void catalogFailurePreservesFinalizedAudioForRecovery() throws Exception {
        app.store.getWritableDatabase().execSQL("CREATE TRIGGER fail_completion BEFORE UPDATE OF status ON sessions "
                + "WHEN NEW.status='completed' BEGIN SELECT RAISE(FAIL,'test catalog failure'); END");
        ArmedSession session = session(3);
        try {
            for(int i=0;i<10;i++) session.offer(window(i,true,3),-35);
            session.close(null); await(session);
            assertTrue(session.outcome.saved); assertNotNull(session.outcome.error);
            assertTrue(app.store.isPublished(session.outcome.row));
        } finally { app.store.getWritableDatabase().execSQL("DROP TRIGGER IF EXISTS fail_completion"); }
        assertEquals(1,app.store.recoverInterrupted());
        assertPlayable(session.outcome.row.uri,1000000,1200000);
        try (Cursor row = app.store.getReadableDatabase().rawQuery("SELECT frames FROM sessions WHERE _id=?",
                new String[]{Long.toString(session.outcome.row.id)})) {
            assertTrue(row.moveToFirst()); assertEquals(48000,row.getLong(0));
        }
    }

    @Test public void timelineFailureReportsErrorButPreservesAudio() throws Exception {
        app.store.getWritableDatabase().execSQL("CREATE TRIGGER fail_segments BEFORE INSERT ON segments BEGIN SELECT RAISE(FAIL,'test timeline failure'); END");
        ArmedSession session = session(4);
        try {
            for(int i=0;i<10;i++) session.offer(window(i,true,4),-35);
            session.close(null); await(session);
            assertTrue(session.outcome.saved); assertNotNull(session.outcome.error);
            assertTrue(session.outcome.error.contains("timeline"));
            assertPlayable(session.outcome.row.uri,1000000,1200000);
        } finally { app.store.getWritableDatabase().execSQL("DROP TRIGGER IF EXISTS fail_segments"); }
    }

    @Test public void graphIsLiveWhileOffAndTapTogglesArmingWithNativeStop() throws Exception {
        app.soundSettings.setThresholdDbfs(0);
        assertEquals(0,new SoundSettings(context).thresholdDbfs());
        long before=sessionCount();
        Activity activity = openActivity();
        waitUntil(() -> app.state.listening && app.latestReading != null,15000);
        assertFalse(app.state.armed);
        long firstFrame=app.latestReading.firstFrame;
        waitUntil(() -> app.latestReading.firstFrame > firstFrame+4800,5000);
        assertFalse(app.history.snapshot(SystemClock.elapsedRealtimeNanos()).isEmpty());
        assertEquals(before,sessionCount());
        View graph=activity.findViewById(R.id.sound_graph);
        assertNotNull(graph);
        instrumentation.runOnMainSync(graph::performClick);
        waitUntil(() -> app.state.armed,10000);
        assertFalse(app.state.recording);
        instrumentation.runOnMainSync(graph::performClick);
        waitUntil(() -> !app.state.busy,10000);
        assertTrue(app.state.listening); assertEquals(before,sessionCount());
        instrumentation.runOnMainSync(graph::performClick);
        waitUntil(() -> app.state.armed,10000);
        String path=app.state.path;
        instrumentation.runOnMainSync(activity::recreate);
        SystemClock.sleep(1200);
        assertTrue(app.state.armed); assertEquals(path,app.state.path);
        // Finish the visible app: armed analysis must continue in the background.
        instrumentation.runOnMainSync(this::finishActivities);
        long backgroundFrame=app.latestReading.firstFrame;
        waitUntil(() -> app.latestReading.firstFrame > backgroundFrame+4800,5000);
        assertTrue(app.state.armed);
        Notification notification=null;
        for(android.service.notification.StatusBarNotification entry:context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if(entry.getId()==1) notification=entry.getNotification();
        }
        assertNotNull(notification); assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT)!=0);
        assertNotNull(notification.actions); assertEquals("Stop",notification.actions[0].title.toString());
        notification.actions[0].actionIntent.send();
        waitUntil(() -> !app.state.armed && !app.state.finishing && !app.state.listening,15000);
        assertEquals(before,sessionCount());
        openActivity(); waitUntil(() -> app.state.listening,15000);
        assertFalse("Opening the app must not rearm",app.state.armed);
    }

    @Test public void graphThresholdLineMovesAndNativeGraphRenders() throws Exception {
        instrumentation.runOnMainSync(() -> {
            SoundHistory previewHistory=new SoundHistory();
            long now=SystemClock.elapsedRealtimeNanos();
            for(int i=0;i<=300;i++) {
                double db=-75+45*Math.max(0,Math.sin(i/12.0));
                previewHistory.add(now-(300-i)*100000000L,db,db>=-35);
            }
            SoundGraphView graph=new SoundGraphView(context,previewHistory,() -> app.soundSettings.thresholdDbfs());
            graph.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(420,View.MeasureSpec.EXACTLY));
            graph.layout(0,0,800,420);
            Bitmap high=Bitmap.createBitmap(800,420,Bitmap.Config.ARGB_8888);
            Bitmap low=Bitmap.createBitmap(800,420,Bitmap.Config.ARGB_8888);
            app.soundSettings.setThresholdDbfs(-30); graph.draw(new Canvas(high));
            app.soundSettings.setThresholdDbfs(-60); graph.draw(new Canvas(low));
            assertTrue("Lower thresholds must move the line down",thresholdRow(high)<thresholdRow(low));
            app.soundSettings.setThresholdDbfs(-35); graph.draw(new Canvas(low));
            try(FileOutputStream output=new FileOutputStream(new File(context.getCacheDir(),"graph-preview.png"))) {
                low.compress(Bitmap.CompressFormat.PNG,100,output);
            } catch(java.io.IOException error) { throw new AssertionError(error); }
        });
    }

    /** Launch with no entry animation; locked-device animation waits are irrelevant to the contract. */
    private Activity openActivity() {
        Activity activity = instrumentation.startActivitySync(new Intent(context,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION),
                ActivityOptions.makeCustomAnimation(context,0,0).toBundle());
        // Only this test window may appear above keyguard; no PIN or device setting is changed.
        instrumentation.runOnMainSync(() -> { activity.setShowWhenLocked(true); activity.setTurnScreenOn(true); });
        return activity;
    }

    /** Finish all tracked activity instances after recreation, without changing lock settings. */
    private void finishActivities() {
        Set<Activity> activities=new HashSet<>();
        for(Stage stage:new Stage[]{Stage.RESUMED,Stage.STARTED,Stage.CREATED,Stage.PAUSED,Stage.STOPPED}) {
            activities.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage));
        }
        for(Activity activity:activities) activity.finish();
    }

    /** Find a long orange line rather than comparing renderer implementation coordinates. */
    private int thresholdRow(Bitmap bitmap) {
        int best=0,row=-1;
        for(int y=0;y<bitmap.getHeight();y++) {
            int count=0;
            for(int x=0;x<bitmap.getWidth();x++) if(bitmap.getPixel(x,y)==SoundGraphView.THRESHOLD_COLOR) count++;
            if(count>best) {best=count;row=y;}
        }
        assertTrue("Threshold must span most of the graph",best>400); return row;
    }

    /** Start a real asynchronous file writer with deterministic input, independent of ambient sound. */
    private ArmedSession session(long token) {
        ArmedSession session=new ArmedSession(context,app.store,token,Instant.now(),ZoneId.systemDefault(),outcome -> { });
        sessions.add(session); jobs.add(app.worker.submit(session)); return session;
    }

    /** Use the actual RMS calculator on a silent or full-scale-half sine window. */
    private AudioCapture.Window window(int index,boolean loud,long token) {
        ByteBuffer pcm=ByteBuffer.allocate(AudioCapture.WINDOW_BYTES).order(ByteOrder.nativeOrder());
        for(int i=0;i<4800;i++) pcm.putShort(loud?(short)Math.round(16384*Math.sin(2*Math.PI*i/48.0)):(short)0);
        RmsAnalyzer.Reading[] measured=new RmsAnalyzer.Reading[1];
        new RmsAnalyzer(48000,reading -> measured[0]=reading).accept(pcm,pcm.capacity());
        return new AudioCapture.Window(pcm.array(),new RmsAnalyzer.Reading(index*4800L,4800,measured[0].dbfs),originNs,originUtcMs,token);
    }

    /** Wait for finalization, including file publication and timeline persistence. */
    private void await(ArmedSession session) throws Exception {
        jobs.get(sessions.indexOf(session)).get(30,TimeUnit.SECONDS);
        assertNotNull(session.outcome);
    }

    /** Inspect independent AAC playback metadata rather than relying on catalog claims. */
    private void assertPlayable(Uri uri,long minimumUs,long maximumUs) throws Exception {
        MediaExtractor extractor=new MediaExtractor();
        try {
            extractor.setDataSource(context,uri,null); assertEquals(1,extractor.getTrackCount());
            MediaFormat format=extractor.getTrackFormat(0);
            assertEquals("audio/mp4a-latm",format.getString(MediaFormat.KEY_MIME));
            assertEquals(1,format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)); assertEquals(48000,format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            long duration=format.getLong(MediaFormat.KEY_DURATION);
            assertTrue("Unexpected duration "+duration,duration>=minimumUs && duration<=maximumUs);
        } finally {extractor.release();}
    }

    /** Count actual catalog rows to prove off/quiet operation creates nothing. */
    private long sessionCount() {
        try(Cursor rows=app.store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sessions",null)) {rows.moveToFirst();return rows.getLong(0);}
    }

    /** Remove only rows and files owned by this test case. */
    private void cleanup(RecordingStore.Session row) {
        if(row==null)return;
        try {context.getContentResolver().delete(row.uri,null,null);} catch(SecurityException ignored) { }
        app.store.getWritableDatabase().delete("sessions","_id=?",new String[]{Long.toString(row.id)});
    }

    /** Bound asynchronous state assertions and report the actual service mode on failure. */
    private void waitUntil(BooleanSupplier condition,long timeoutMs) {
        long deadline=SystemClock.elapsedRealtime()+timeoutMs;
        while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50);
        assertTrue("Timed out: "+app.state.title+" / "+app.state.detail,condition.getAsBoolean());
    }
}

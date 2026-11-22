package org.gossipgoblin;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContentResolver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.SystemClock;
import android.os.Bundle;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;

/** Writer-worker-owned SQLite catalog, saved-segment mappings, and shared-audio lifecycle. */
final class RecordingStore extends SQLiteOpenHelper {
    /** Application context and primary shared-audio collection. */
    private final Context context;
    private static final Uri AUDIO = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

    RecordingStore(Context context) {
        super(context, "recordings.db", null, 2);
        this.context = context.getApplicationContext();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (_id INTEGER PRIMARY KEY, uri TEXT, path TEXT NOT NULL, "
                + "start_utc_ms INTEGER NOT NULL, start_elapsed_ns INTEGER NOT NULL, timezone TEXT NOT NULL, "
                + "status TEXT NOT NULL, frames INTEGER NOT NULL DEFAULT 0, reason TEXT, mode TEXT NOT NULL DEFAULT 'continuous')");
        createSegments(db);
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'continuous'");
            createSegments(db);
        }
    }

    /** Keep legacy sessions intact while adding the compacted-to-live timeline mapping. */
    private void createSegments(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE segments (_id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL REFERENCES sessions(_id) ON DELETE CASCADE, "
                + "source_start_frame INTEGER NOT NULL, file_start_frame INTEGER NOT NULL, frame_count INTEGER NOT NULL, "
                + "start_utc_ms INTEGER NOT NULL, start_elapsed_ns INTEGER NOT NULL, threshold_dbfs INTEGER NOT NULL, event_number INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX segments_session ON segments(session_id,file_start_frame)");
    }

    /** Persist a contiguous saved span after its encoder input has been accepted. */
    void addSegment(Session session, long sourceFrame, long fileFrame, long count,
            long utcMs, long elapsedNs, int threshold, int event) {
        ContentValues row = new ContentValues();
        row.put("session_id", session.id); row.put("source_start_frame", sourceFrame);
        row.put("file_start_frame", fileFrame); row.put("frame_count", count);
        row.put("start_utc_ms", utcMs); row.put("start_elapsed_ns", elapsedNs);
        row.put("threshold_dbfs", threshold); row.put("event_number", event);
        getWritableDatabase().insertOrThrow("segments", null, row);
    }

    /** Reserve the exact journal path; reject collisions rather than silently rename. */
    Session create(Instant start, ZoneId zone) throws IOException {
        return create(start, zone, SystemClock.elapsedRealtimeNanos());
    }

    /** Pair the arming UTC instant with its actual monotonic time, not lazy file-creation time. */
    Session create(Instant start, ZoneId zone, long startElapsedNs) throws IOException {
        RecordingName name = new RecordingName(start, zone);
        try (Cursor reserved = getReadableDatabase().rawQuery(
                "SELECT _id FROM sessions WHERE path=? AND status='active'",
                new String[]{name.directory + name.filename})) {
            if (reserved.moveToFirst()) throw new IOException("A recording with this start time already exists.");
        }
        try (Cursor existing = context.getContentResolver().query(AUDIO,
                new String[]{MediaStore.MediaColumns._ID},
                includingPending("relative_path=? AND _display_name=?", name.directory, name.filename), null)) {
            if (existing != null && existing.moveToFirst()) {
                throw new IOException("A recording with this start time already exists. Try again in a second.");
            }
        }
        ContentValues row = new ContentValues();
        row.put("path", name.directory + name.filename);
        row.put("start_utc_ms", start.toEpochMilli());
        row.put("start_elapsed_ns", startElapsedNs);
        row.put("timezone", zone.getId());
        row.put("status", "active");
        row.put("mode", "threshold");
        long id = getWritableDatabase().insertOrThrow("sessions", null, row);
        Uri uri = null;
        try {
            ContentValues media = new ContentValues();
            media.put(MediaStore.MediaColumns.DISPLAY_NAME, name.filename);
            media.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4");
            media.put(MediaStore.MediaColumns.RELATIVE_PATH, name.directory);
            media.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = context.getContentResolver().insert(AUDIO, media);
            if (uri == null) throw new IOException("Shared audio storage is unavailable.");
            ContentValues link = new ContentValues();
            link.put("uri", uri.toString());
            getWritableDatabase().update("sessions", link, "_id=?", new String[]{Long.toString(id)});
            try (Cursor actual = context.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (actual == null || !actual.moveToFirst() || !name.filename.equals(actual.getString(0))) {
                    throw new IOException("The recording filename is already in use.");
                }
            }
            return new Session(id, uri, name.directory + name.filename);
        } catch (Exception error) {
            if (uri != null) context.getContentResolver().delete(uri, null, null);
            update(id, "failed", 0, error.getMessage());
            throw new IOException(error.getMessage(), error);
        }
    }

    /** Publish only finalized audio; failed audio remains unpublished and is removed. */
    void finish(Session session, boolean playable, long frames, String interruption) throws IOException {
        if (playable) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (context.getContentResolver().update(session.uri, values, null, null) != 1) {
                throw new IOException("Could not publish the finalized recording.");
            }
        } else {
            context.getContentResolver().delete(session.uri, null, null);
        }
        update(session.id, playable ? (interruption == null ? "completed" : "interrupted") : "failed",
                frames, interruption);
    }

    /** Verify whether a finalized file was published even if its catalog update failed. */
    boolean isPublished(Session session) {
        try (Cursor media = context.getContentResolver().query(session.uri,
                new String[]{MediaStore.MediaColumns.IS_PENDING}, null, null, null)) {
            return media != null && media.moveToFirst() && media.getInt(0) == 0;
        } catch (RuntimeException unavailable) { return false; }
    }

    /** Reconcile abandoned sessions and unpublished owned audio after process death. */
    int recoverInterrupted() {
        int recovered;
        try (Cursor rows = getReadableDatabase().query("sessions", new String[]{"_id", "uri"},
                "status='active'", null, null, null, null)) {
            recovered = rows.getCount();
            while (rows.moveToNext()) {
                // A file published immediately before a crash is already independently playable.
                boolean published = false;
                try {
                    if (!rows.isNull(1)) {
                        Uri uri = Uri.parse(rows.getString(1));
                        try (Cursor media = context.getContentResolver().query(uri,
                                new String[]{MediaStore.MediaColumns.IS_PENDING}, null, null, null)) {
                            published = media != null && media.moveToFirst() && media.getInt(0) == 0;
                        }
                        if (!published) context.getContentResolver().delete(uri, null, null);
                    }
                } catch (SecurityException | IllegalArgumentException unavailable) {
                    // Deleted or no-longer-owned entries must not block recovery of other sessions.
                }
                long recoveredFrames = 0;
                if (published) {
                    try (Cursor spans = getReadableDatabase().rawQuery(
                            "SELECT COALESCE(SUM(frame_count),0) FROM segments WHERE session_id=?",
                            new String[]{Long.toString(rows.getLong(0))})) {
                        if (spans.moveToFirst()) recoveredFrames = spans.getLong(0);
                    }
                }
                update(rows.getLong(0), "interrupted", recoveredFrames,
                        published ? "Process ended after audio was finalized." : "Process ended before finalization.");
            }
        }
        // Also close the insert/link crash window: only this app's unpublished journal entries.
        try (Cursor pending = context.getContentResolver().query(AUDIO,
                new String[]{MediaStore.MediaColumns._ID},
                includingPending("is_pending=1 AND owner_package_name=? AND relative_path LIKE ?",
                        context.getPackageName(), "Recordings/Gossip/%"), null)) {
            if (pending != null) while (pending.moveToNext()) {
                context.getContentResolver().delete(Uri.withAppendedPath(AUDIO, pending.getString(0)), null, null);
            }
        }
        return recovered;
    }

    /** MediaStore excludes pending collection entries unless explicitly included. */
    private Bundle includingPending(String selection, String... arguments) {
        Bundle query = new Bundle();
        query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments);
        query.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        return query;
    }

    /** Write one terminal catalog state. */
    private void update(long id, String status, long frames, String reason) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("frames", frames);
        values.put("reason", reason);
        getWritableDatabase().update("sessions", values, "_id=?", new String[]{Long.toString(id)});
    }

    /** Identifies a reserved MediaStore entry and its SQLite row. */
    static final class Session {
        final long id;
        final Uri uri;
        final String path;
        Session(long id, Uri uri, String path) { this.id = id; this.uri = uri; this.path = path; }
    }
}

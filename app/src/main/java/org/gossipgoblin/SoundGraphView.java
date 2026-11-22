package org.gossipgoblin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;
import java.util.List;
import java.util.function.IntSupplier;

/** Native scrolling 30-second RMS plot; standard View click/keyboard handling toggles arming. */
final class SoundGraphView extends View {
    /** Shared drawing colors also identify selected audio without relying on status text alone. */
    static final int THRESHOLD_COLOR = Color.rgb(193, 101, 25);
    private static final int INK = Color.rgb(30, 42, 35), GREEN = Color.rgb(35, 123, 78);
    /** Source data, reusable drawing tools, and lifecycle-controlled redraw loop. */
    private final SoundHistory history;
    private final IntSupplier threshold;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trace = new Path();
    private boolean attached;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (attached && getWindowVisibility() == VISIBLE) { invalidate(); postDelayed(this, 100); }
        }
    };

    SoundGraphView(Context context) {
        this(context, ((GoblinApp) context.getApplicationContext()).history,
                () -> ((GoblinApp) context.getApplicationContext()).soundSettings.thresholdDbfs());
    }

    /** Explicit data sources also permit deterministic native-rendering validation. */
    SoundGraphView(Context context, SoundHistory history, IntSupplier threshold) {
        super(context);
        this.history = history; this.threshold = threshold;
        setId(R.id.sound_graph);
        setClickable(true); setFocusable(true);
        setContentDescription(context.getString(R.string.graph_description));
        setMinimumHeight(Math.round(dp(240)));
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; post(tick); }
    @Override protected void onDetachedFromWindow() { attached = false; removeCallbacks(tick); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(tick);
        if (attached && visibility == VISIBLE) post(tick);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(46), right = getWidth() - dp(16), top = dp(26), bottom = getHeight() - dp(38);
        if (right <= left || bottom <= top) return;
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(12), dp(12), paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT); paint.setTextSize(dp(11));
        paint.setStrokeWidth(dp(1));
        for (int db = 0; db >= -120; db -= 30) {
            float y = levelY(db, top, bottom);
            paint.setColor(Color.rgb(223, 230, 222)); canvas.drawLine(left, y, right, y, paint);
            paint.setColor(INK); canvas.drawText(Integer.toString(db), dp(7), y + dp(4), paint);
        }
        for (int seconds = -30; seconds <= 0; seconds += 10) {
            float x = left + (seconds + 30) / 30f * (right - left);
            paint.setColor(Color.rgb(233, 237, 231)); canvas.drawLine(x, top, x, bottom, paint);
            paint.setColor(INK);
            String label = seconds == 0 ? getContext().getString(R.string.now) : seconds + " s";
            canvas.drawText(label, Math.min(x, right - paint.measureText(label)), bottom + dp(22), paint);
        }
        canvas.drawText("dBFS", dp(7), dp(16), paint);
        long nowNs = SystemClock.elapsedRealtimeNanos();
        List<SoundHistory.Sample> samples = history.snapshot(nowNs);
        canvas.save(); canvas.clipRect(left, top, right, bottom);
        trace.reset();
        SoundHistory.Sample previous = null;
        for (SoundHistory.Sample sample : samples) {
            float x = timeX(sample.elapsedNs, nowNs, left, right), y = levelY(sample.dbfs, top, bottom);
            if (previous == null || sample.elapsedNs - previous.elapsedNs > 300000000L) trace.moveTo(x, y);
            else trace.lineTo(x, y);
            previous = sample;
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(Color.rgb(101, 120, 106));
        canvas.drawPath(trace, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(GREEN);
        for (SoundHistory.Sample sample : samples) {
            if (sample.recorded) canvas.drawCircle(timeX(sample.elapsedNs, nowNs, left, right),
                    levelY(sample.dbfs, top, bottom), dp(2.5f), paint);
        }
        paint.setColor(THRESHOLD_COLOR); paint.setStrokeWidth(dp(2));
        int thresholdDbfs = threshold.getAsInt();
        float thresholdY = levelY(thresholdDbfs, top, bottom);
        canvas.drawLine(left, thresholdY, right, thresholdY, paint);
        canvas.restore();
        paint.setColor(THRESHOLD_COLOR); paint.setStyle(Paint.Style.FILL);
        canvas.drawText(getContext().getString(R.string.threshold_short, thresholdDbfs),
                left + dp(5), Math.max(top + dp(13), thresholdY - dp(6)), paint);
    }

    /** Map the full digital range, including the silence floor, onto the plot. */
    private float levelY(double db, float top, float bottom) {
        return top + (float) (-Math.max(-120, Math.min(0, db)) / 120) * (bottom - top);
    }

    /** The right edge is always now; old samples naturally scroll left. */
    private float timeX(long elapsedNs, long nowNs, float left, float right) {
        return right - (float) ((nowNs - elapsedNs) / (double) SoundHistory.SPAN_NS) * (right - left);
    }

    /** Convert plot dimensions to density-independent pixels. */
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}

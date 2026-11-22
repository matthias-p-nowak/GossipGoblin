package org.gossipgoblin;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Continuous microphone analysis, independent of whether any audio is saved. */
final class AudioCapture {
    /** One 100 ms native-endian mono PCM16 window and the selected output format. */
    static final int SAMPLE_RATE = 48000, BIT_RATE = 64000, WINDOW_BYTES = 9600;

    /** Run live analysis until requested to stop, reporting errors after resource release. */
    String listen(Context context, BooleanSupplier stopped, LongSupplier armToken,
            Runnable started, Consumer<String> interrupted, Consumer<Window> windows) {
        AudioRecord microphone = null;
        AudioManager.AudioRecordingCallback callback = new AudioManager.AudioRecordingCallback() {
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                for (AudioRecordingConfiguration config : configs) {
                    if (config.isClientSilenced()) interrupted.accept("Microphone access was interrupted.");
                }
            }
        };
        String error = null;
        try {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Microphone permission is required.");
            }
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("Mono microphone capture is unsupported.");
            microphone = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum * 4, 32768)).build();
            microphone.registerAudioRecordingCallback(context.getMainExecutor(), callback);
            long originNs = SystemClock.elapsedRealtimeNanos();
            long originUtcMs = System.currentTimeMillis();
            microphone.startRecording();
            if (microphone.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microphone did not start.");
            AudioRecordingConfiguration configuration = microphone.getActiveRecordingConfiguration();
            if (configuration != null && configuration.isClientSilenced()) throw new IOException("Microphone access is unavailable.");
            started.run();
            ByteBuffer input = ByteBuffer.allocateDirect(WINDOW_BYTES);
            byte[] pcm = new byte[WINDOW_BYTES];
            int filled = 0;
            long token = 0;
            boolean consistent = true;
            RmsAnalyzer.Reading[] measured = new RmsAnalyzer.Reading[1];
            RmsAnalyzer analyzer = new RmsAnalyzer(SAMPLE_RATE, reading -> measured[0] = reading);
            while (!stopped.getAsBoolean()) {
                long before = armToken.getAsLong();
                if (filled == 0) { token = before; consistent = true; }
                if (token != before) consistent = false;
                int bytes = microphone.read(input, WINDOW_BYTES - filled, AudioRecord.READ_BLOCKING);
                if (bytes <= 0) throw new IOException("Microphone capture stopped (" + bytes + ").");
                if (armToken.getAsLong() != before) consistent = false;
                input.position(0); input.limit(bytes);
                input.get(pcm, filled, bytes);
                analyzer.accept(input, bytes);
                filled += bytes;
                if (filled == WINDOW_BYTES) {
                    windows.accept(new Window(pcm, measured[0], originNs, originUtcMs, consistent ? token : 0));
                    pcm = new byte[WINDOW_BYTES]; filled = 0;
                }
            }
            if (filled > 0) {
                analyzer.finish();
                // A stopping or changing mode must not save a window straddling that transition.
                windows.accept(new Window(Arrays.copyOf(pcm, filled), measured[0], originNs, originUtcMs, 0));
            }
        } catch (Exception failure) {
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        } finally {
            if (microphone != null) {
                try { microphone.unregisterAudioRecordingCallback(callback); } catch (RuntimeException ignored) { }
                try { microphone.stop(); } catch (RuntimeException ignored) { }
                microphone.release();
            }
        }
        return error;
    }

    /** Immutable window; source positions remain continuous even when the saved file omits gaps. */
    static final class Window {
        final byte[] pcm;
        final RmsAnalyzer.Reading reading;
        final long originNs, originUtcMs, armToken;
        Window(byte[] pcm, RmsAnalyzer.Reading reading, long originNs, long originUtcMs, long armToken) {
            this.pcm = pcm; this.reading = reading; this.originNs = originNs;
            this.originUtcMs = originUtcMs; this.armToken = armToken;
        }
        /** Estimated window-start monotonic time, before hardware/encoder calibration. */
        long startNs() {
            // Divide first: multiplying a 48 kHz frame index by 1e9 overflows after about 53 hours.
            return originNs + reading.firstFrame / SAMPLE_RATE * 1000000000L
                    + reading.firstFrame % SAMPLE_RATE * 1000000000L / SAMPLE_RATE;
        }
        /** Estimated window-start UTC from the capture-start clock pair. */
        long startUtcMs() { return originUtcMs + reading.firstFrame * 1000L / SAMPLE_RATE; }
        /** Monotonic end used for the scrolling graph. */
        long endNs() { return startNs() + reading.frameCount * 1000000000L / SAMPLE_RATE; }
    }
}

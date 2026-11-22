package org.gossipgoblin;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Writer-thread-owned AAC/M4A output with a compacted, continuous sample timeline. */
final class AacFile {
    /** Owned encoder, muxer, descriptor, and output counters. */
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private ParcelFileDescriptor descriptor;
    private int track = -1;
    private boolean started, sawEnd;
    private long packets;
    long frames;

    AacFile(Context context, RecordingStore.Session session) throws IOException {
        try {
            descriptor = context.getContentResolver().openFileDescriptor(session.uri, "rw");
            if (descriptor == null) throw new IOException("Recording storage is unavailable.");
            muxer = new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioCapture.SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AudioCapture.BIT_RATE);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
        } catch (Exception failure) {
            release();
            throw new IOException("Could not open audio output: " + failure.getMessage(), failure);
        }
    }

    /** Submit selected PCM only, assigning timestamps from saved frames rather than live gaps. */
    void write(byte[] pcm) throws IOException {
        int offset = 0;
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (offset < pcm.length) {
            int index = encoder.dequeueInputBuffer(10000);
            if (index >= 0) {
                ByteBuffer input = encoder.getInputBuffer(index);
                if (input == null) throw new IOException("Encoder input is unavailable.");
                input.clear();
                int bytes = Math.min(input.capacity() & ~1, pcm.length - offset);
                if (bytes == 0) throw new IOException("Encoder input is too small.");
                input.put(pcm, offset, bytes);
                encoder.queueInputBuffer(index, 0, bytes, frames * 1000000L / AudioCapture.SAMPLE_RATE, 0);
                frames += bytes / 2;
                offset += bytes;
                deadline = SystemClock.elapsedRealtime() + 5000;
            }
            drain();
            if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("Audio encoder stopped accepting samples.");
        }
    }

    /** Flush EOS and finalize the file; release all resources even when finalization fails. */
    boolean finish() throws IOException {
        try {
            if (frames == 0) return false;
            long deadline = SystemClock.elapsedRealtime() + 5000;
            boolean sentEnd = false;
            while (!sawEnd && SystemClock.elapsedRealtime() < deadline) {
                if (!sentEnd) {
                    int index = encoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0, frames * 1000000L / AudioCapture.SAMPLE_RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sentEnd = true;
                    }
                }
                drain();
                if (!sawEnd) SystemClock.sleep(2);
            }
            if (!sawEnd) throw new IOException("Audio encoder did not finish in time.");
            if (!started || packets == 0) return false;
            muxer.stop();
            return true;
        } finally { release(); }
    }

    /** Drain available AAC access units into the single muxer track. */
    private void drain() throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (;;) {
            int index = encoder.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (started) throw new IOException("Encoder format changed during recording.");
                track = muxer.addTrack(encoder.getOutputFormat());
                muxer.start(); started = true;
            } else if (index >= 0) {
                try {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (!started) throw new IOException("Audio arrived before its format.");
                        ByteBuffer output = encoder.getOutputBuffer(index);
                        if (output == null) throw new IOException("Encoder output is unavailable.");
                        output.position(info.offset); output.limit(info.offset + info.size);
                        muxer.writeSampleData(track, output, info); packets++;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEnd = true;
                } finally { encoder.releaseOutputBuffer(index, false); }
            }
        }
    }

    /** Best-effort cleanup without masking the original write/finalization error. */
    private void release() {
        if (encoder != null) { try { encoder.release(); } catch (RuntimeException ignored) { } encoder = null; }
        if (muxer != null) { try { muxer.release(); } catch (RuntimeException ignored) { } muxer = null; }
        if (descriptor != null) { try { descriptor.close(); } catch (IOException ignored) { } descriptor = null; }
    }
}

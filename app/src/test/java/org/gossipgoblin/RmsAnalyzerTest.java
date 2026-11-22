package org.gossipgoblin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Known digital signals verify scale, streaming boundaries, and non-destructive analysis. */
public class RmsAnalyzerTest {
    @Test public void knownLevelsMatchDigitalFullScaleConvention() {
        assertEquals(-120, constant((short) 0), 0);
        assertEquals(0, constant(Short.MIN_VALUE), 0);
        assertEquals(-6.020599913, constant((short) 16384), 0.000001);
        List<RmsAnalyzer.Reading> output = new ArrayList<>();
        RmsAnalyzer analyzer = new RmsAnalyzer(48000, output::add);
        ByteBuffer sine = pcm(4800);
        for (int frame = 0; frame < 4800; frame++) {
            sine.putShort((short) Math.round(32767 * Math.sin(2 * Math.PI * frame / 48.0)));
        }
        analyzer.accept(sine, sine.capacity());
        assertEquals(-3.0106, output.get(0).dbfs, 0.001);
    }

    @Test public void windowsAreIndependentOfCaptureReadBoundaries() {
        List<RmsAnalyzer.Reading> output = new ArrayList<>();
        RmsAnalyzer analyzer = new RmsAnalyzer(48000, output::add);
        int[] chunks = {4096, 10, 703, 4096, 1000};
        long submitted = 0;
        for (int frames : chunks) {
            ByteBuffer input = pcm(frames);
            for (int frame = 0; frame < frames; frame++) input.putShort((short) 8192);
            analyzer.accept(input, input.capacity());
            submitted += frames;
        }
        analyzer.finish();
        analyzer.finish();
        assertEquals(3, output.size());
        assertEquals(0, output.get(0).firstFrame);
        assertEquals(4800, output.get(1).firstFrame);
        assertEquals(9600, output.get(2).firstFrame);
        assertEquals(submitted - 9600, output.get(2).frameCount);
        for (RmsAnalyzer.Reading reading : output) assertEquals(-12.041199827, reading.dbfs, 0.000001);
    }

    @Test public void bufferStateAndSamplesRemainUnchanged() {
        ByteBuffer input = pcm(4800);
        for (int frame = 0; frame < 4800; frame++) input.putShort((short) -16384);
        input.position(7);
        input.limit(100);
        List<RmsAnalyzer.Reading> output = new ArrayList<>();
        new RmsAnalyzer(48000, output::add).accept(input, input.capacity());
        assertEquals(7, input.position());
        assertEquals(100, input.limit());
        assertEquals(-16384, input.getShort(0));
        assertEquals(-6.020599913, output.get(0).dbfs, 0.000001);
    }

    @Test(expected = IllegalArgumentException.class) public void incompletePcmFrameIsRejected() {
        new RmsAnalyzer(48000, reading -> { }).accept(pcm(1), 1);
    }

    /** Measure exactly one constant-amplitude window. */
    private double constant(short amplitude) {
        List<RmsAnalyzer.Reading> output = new ArrayList<>();
        ByteBuffer input = pcm(4800);
        for (int frame = 0; frame < 4800; frame++) input.putShort(amplitude);
        new RmsAnalyzer(48000, output::add).accept(input, input.capacity());
        return output.get(0).dbfs;
    }

    /** Allocate the same native-endian direct buffer format used by AudioRecord. */
    private ByteBuffer pcm(int frames) {
        return ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder());
    }
}

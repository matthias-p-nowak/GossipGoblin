package org.gossipgoblin;

import org.junit.Test;
import static org.junit.Assert.*;

/** Long-running analysis must not overflow when projecting frame positions into nanoseconds. */
public class CaptureTimingTest {
    @Test public void multiDaySourcePositionsKeepTheirMonotonicAndUtcTime() {
        long seconds = 72 * 3600L;
        long originNs = 123000000000L, originUtcMs = 1700000000000L;
        RmsAnalyzer.Reading reading = new RmsAnalyzer.Reading(seconds * 48000 + 24000, 4800, -50);
        AudioCapture.Window window = new AudioCapture.Window(new byte[0], reading, originNs, originUtcMs, 1);
        assertEquals(originNs + seconds * 1000000000L + 500000000L, window.startNs());
        assertEquals(window.startNs() + 100000000L, window.endNs());
        assertEquals(originUtcMs + seconds * 1000L + 500, window.startUtcMs());
    }
}

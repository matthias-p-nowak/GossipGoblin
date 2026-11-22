package org.gossipgoblin;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Bound memory and ensure stale history scrolls away even without incoming samples. */
public class SoundHistoryTest {
    @Test public void onlyRecentThirtySecondsRemain() {
        SoundHistory history = new SoundHistory();
        for (int i = 0; i < 1000; i++) history.add(i * 100000000L, -50, false);
        List<SoundHistory.Sample> samples = history.snapshot(99900000000L);
        assertTrue(samples.size() <= 320);
        assertTrue(samples.get(0).elapsedNs >= 69900000000L);
        assertEquals(99900000000L, samples.get(samples.size() - 1).elapsedNs);
        assertTrue(history.snapshot(131000000000L).isEmpty());
    }

    @Test public void frozenClockCannotGrowHistoryWithoutBound() {
        SoundHistory history = new SoundHistory();
        for (int i = 0; i < 10000; i++) history.add(1, -40, false);
        assertTrue(history.snapshot(1).size() <= 320);
    }
}

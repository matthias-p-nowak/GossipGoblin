package org.gossipgoblin;

import org.junit.Test;
import static org.junit.Assert.*;

/** Verify strict saving and the separate hysteresis latch used for detection grouping. */
public class ThresholdGateTest {
    @Test public void quietWindowsNeverSaveEvenInsideHysteresisBand() {
        ThresholdGate gate = new ThresholdGate();
        assertEquals(0, gate.select(-50, -35));
        assertEquals(1, gate.select(-35, -35));
        assertEquals(0, gate.select(-36, -35));
        assertEquals(1, gate.select(-34, -35));
        assertEquals(0, gate.select(-41, -35));
        assertEquals(2, gate.select(-34, -35));
    }

    @Test public void thresholdChangesTakeEffectImmediatelyWithoutSavingBelowTheNewLine() {
        ThresholdGate gate = new ThresholdGate();
        assertEquals(1, gate.select(-30, -35));
        assertEquals(0, gate.select(-30, -20));
        assertEquals(2, gate.select(-30, -40));
    }

    @Test public void invalidLevelsCannotTriggerSaving() {
        ThresholdGate gate = new ThresholdGate();
        assertEquals(0, gate.select(Double.NaN, -35));
        assertEquals(0, gate.select(Double.POSITIVE_INFINITY, -35));
        assertEquals(0, gate.select(Double.NEGATIVE_INFINITY, -35));
    }
}

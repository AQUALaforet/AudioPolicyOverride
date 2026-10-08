package dev.aqua.audiopolicy.automation

import org.junit.Assert.*
import org.junit.Test

class ForegroundGateTest {
    @Test fun enteringIsImmediateAndLeavingIsDebounced() {
        val gate = ForegroundGate()
        assertFalse(gate.update(false, 0))
        assertTrue(gate.update(true, 100))
        assertTrue(gate.update(false, 200))
        assertTrue(gate.update(false, 999))
        assertFalse(gate.update(false, 1000))
    }
    @Test fun switchingBetweenSelectedAppsDoesNotRelease() {
        val gate = ForegroundGate()
        assertTrue(gate.update(true, 0))
        assertTrue(gate.update(false, 100))
        assertTrue(gate.update(true, 300))
        assertTrue(gate.update(false, 1000))
        assertTrue(gate.update(false, 1799))
        assertFalse(gate.update(false, 1800))
    }
    @Test fun unknownObservationDoesNotRestoreOrCountAsExitTime() {
        val gate = ForegroundGate()
        assertTrue(gate.update(true, 0))
        assertTrue(gate.update(false, 100))
        assertTrue(gate.update(null, 1000))
        assertTrue(gate.update(false, 2000))
        assertFalse(gate.update(false, 2800))
    }
    @Test fun resetDiscardsPreviousMatch() {
        val gate = ForegroundGate()
        gate.update(true, 0)
        gate.reset()
        assertFalse(gate.update(false, 1000))
    }
}

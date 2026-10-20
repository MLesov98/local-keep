package com.localkeep.core.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLockPresetTest {

    @Test
    fun backgroundGraceIsAlwaysShorterThanIdle() {
        // The whole point of the split: an unattended phone must lock sooner than one being
        // looked at. A preset that inverted this would silently be worse than the flat timeout.
        AutoLockPreset.entries.forEach { preset ->
            assertTrue(
                "${preset.name} background grace should be shorter than its idle timeout",
                preset.backgroundGraceSeconds < preset.idleSeconds,
            )
        }
    }

    @Test
    fun presetsAreOrderedFromStrictestToLoosest() {
        val byOrder = AutoLockPreset.entries
        byOrder.zipWithNext().forEach { (stricter, looser) ->
            assertTrue(
                "${stricter.name} should not lock later than ${looser.name}",
                stricter.idleSeconds <= looser.idleSeconds &&
                    stricter.backgroundGraceSeconds <= looser.backgroundGraceSeconds,
            )
        }
    }

    @Test
    fun neverLocksInstantly() {
        // Zero would mean re-entering the master password after every app switch, which trains
        // people toward shorter passwords — a net loss.
        AutoLockPreset.entries.forEach { preset ->
            assertTrue("${preset.name} must allow a real grace period", preset.backgroundGraceMs > 0)
        }
    }

    @Test
    fun legacyTimeoutMapsToNearestPreset() {
        // Upgrading must not quietly relax someone's deliberate choice.
        assertEquals(AutoLockPreset.AGGRESSIVE, AutoLockPreset.closestTo(30))
        assertEquals(AutoLockPreset.BALANCED, AutoLockPreset.closestTo(120))
        assertEquals(AutoLockPreset.RELAXED, AutoLockPreset.closestTo(300))
        // The old default (60s) sits between 30 and 120; nearest wins, and either way it is not
        // allowed to land on RELAXED.
        assertTrue(AutoLockPreset.closestTo(60) != AutoLockPreset.RELAXED)
    }

    @Test
    fun unknownStoredNameFallsBackRatherThanThrowing() {
        assertNull(AutoLockPreset.byName("SOMETHING_ELSE"))
        assertNull(AutoLockPreset.byName(null))
    }
}

package com.localkeep.core.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * An `OutOfMemoryError` from an in-app key derivation becomes a typed, named failure rather than
 * escaping as an `Error` the view models render as "That didn't go through. Try again."
 * (device, 2026-09-14: duress setup on a 48 MiB heap, three times out of three).
 */
class KdfMemoryGuardTest {

    @Test
    fun anOutOfMemoryErrorBecomesTheTypedFailure() {
        val failure = assertFailsWith<KdfNeedsMoreMemoryException> {
            guardKdfMemory { throw OutOfMemoryError("Failed to allocate a 1040 byte allocation") }
        }
        assertEquals(Runtime.getRuntime().maxMemory() / (1024 * 1024), failure.heapLimitMib)
    }

    @Test
    fun aSuccessfulDerivationPassesThrough() {
        assertEquals(42, guardKdfMemory { 42 })
    }

    /** Anything that is not a memory failure keeps its own type, so callers' named catches still fire. */
    @Test
    fun otherThrowablesAreNotRewritten() {
        assertFailsWith<IllegalArgumentException> {
            guardKdfMemory { throw IllegalArgumentException("wrong") }
        }
    }
}

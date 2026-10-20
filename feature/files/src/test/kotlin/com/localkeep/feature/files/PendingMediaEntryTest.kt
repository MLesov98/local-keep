package com.localkeep.feature.files

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PendingMediaEntryTest {

    @Test
    fun `a write that finishes keeps the entry`() {
        val discarded = mutableListOf<String>()
        var published = false
        val result = completePendingEntry("content://media/external/images/media/7", discard = { discarded += it }) {
            published = true
        }
        assertEquals("content://media/external/images/media/7", result)
        assertTrue(published)
        assertEquals(emptyList<String>(), discarded)
    }

    @Test
    fun `a write that runs out of disk discards the pending entry and rethrows`() {
        val discarded = mutableListOf<String>()
        val failure = assertFailsWith<IOException> {
            completePendingEntry("content://media/external/video/media/9", discard = { discarded += it }) {
                throw IOException("No space left on device")
            }
        }
        assertEquals("No space left on device", failure.message)
        assertEquals(listOf("content://media/external/video/media/9"), discarded)
    }

    @Test
    fun `an oversized restore discards the pending entry even though it fails with an Error`() {
        val discarded = mutableListOf<String>()
        assertFailsWith<OutOfMemoryError> {
            completePendingEntry("uri", discard = { discarded += it }) { throw OutOfMemoryError() }
        }
        assertEquals(listOf("uri"), discarded)
    }

    @Test
    fun `a discard that fails does not mask the original failure`() {
        val failure = assertFailsWith<IOException> {
            completePendingEntry("uri", discard = { throw IllegalStateException("delete refused") }) {
                throw IOException("stream closed")
            }
        }
        assertEquals("stream closed", failure.message)
    }
}

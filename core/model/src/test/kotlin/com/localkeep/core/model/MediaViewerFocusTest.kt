package com.localkeep.core.model

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaViewerFocusTest {
    @AfterTest
    fun clear() {
        MediaViewerFocus.clear()
    }

    @Test
    fun `focus is one shot and correlated to the destination id`() {
        val first = UUID.randomUUID()
        val other = UUID.randomUUID()
        val request = MediaViewerFocusRequest(
            scope = MediaViewerScope.GlobalSearch,
            visibleOrderedItemIds = listOf(first),
            projectionVersion = 4L,
        )

        MediaViewerFocus.request(first, request)

        assertNull(MediaViewerFocus.take(other))
        assertEquals(request, MediaViewerFocus.take(first))
        assertNull(MediaViewerFocus.take(first))
    }

    @Test
    fun `a replacement request wins without letting a stale request leak`() {
        val id = UUID.randomUUID()
        val old = MediaViewerFocusRequest(MediaViewerScope.RootUnfiled, emptyList(), 1L)
        val fresh = MediaViewerFocusRequest(MediaViewerScope.GlobalSearch, listOf(id), 2L)

        MediaViewerFocus.request(id, old)
        MediaViewerFocus.request(id, fresh)

        assertEquals(fresh, MediaViewerFocus.take(id))
    }
}

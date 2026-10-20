package com.localkeep.autofill

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-46: the second inline autofill chip was clipped to the item's title ("LocalKeep") with no
 * username, so two of three matching vault entries were indistinguishable in the IME strip. The
 * fix leads with the username — the only thing that actually tells two matching rows apart — and
 * demotes the shared item title to the subtitle a truncated chip may lose instead of the title.
 */
class DatasetBuilderInlineLoginPresentationTest {

    @Test
    fun `the username leads and the item title is the subtitle`() {
        val (title, subtitle) = DatasetBuilder.inlineLoginPresentationText(
            itemTitle = "LocalKeep Autofill Test",
            username = "g18user",
        )

        assertEquals("g18user", title)
        assertEquals("LocalKeep Autofill Test", subtitle)
    }

    @Test
    fun `a blank username falls back to the title alone rather than an empty leading line`() {
        val (title, subtitle) = DatasetBuilder.inlineLoginPresentationText(
            itemTitle = "LocalKeep",
            username = "",
        )

        assertEquals("LocalKeep", title)
        assertEquals("", subtitle)
    }
}

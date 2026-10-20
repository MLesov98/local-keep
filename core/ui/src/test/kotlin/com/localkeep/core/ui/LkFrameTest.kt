package com.localkeep.core.ui

import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkFrame
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The frame tokens are a *naming* of values that already shipped, not a new spacing decision.
 *
 * Fourteen screens were re-pointed at [LkFrame.screenHorizontal], [LkFrame.screenTop],
 * [LkFrame.gutterFab] and [LkFrame.gutterPlain] on 2026-08-12 on the promise that nothing renders
 * differently. The obvious next edit is to "tidy" these onto the 4/8/16/24/32 rungs of `LkSpace`,
 * which would silently move the padding of every one of those screens — reopening a density
 * decision measured on 2026-07-29. This test is the tripwire for that edit: changing a value here
 * is a deliberate design change and must fail the build until it is argued somewhere.
 */
class LkFrameTest {

    @Test
    fun `ordinary screen frame keeps the values it shipped with`() {
        assertEquals(20.dp, LkFrame.screenHorizontal)
        assertEquals(12.dp, LkFrame.screenTop)
    }

    @Test
    fun `scroll gutters keep the values they shipped with`() {
        assertEquals(120.dp, LkFrame.gutterFab)
        assertEquals(40.dp, LkFrame.gutterPlain)
    }

    @Test
    fun `hero frame is wider than the ordinary screen frame`() {
        // The two frames exist precisely because they differ; if they ever converge, one of them
        // is redundant and the caller should be told which to use.
        assertEquals(26.dp, LkFrame.horizontal)
        assert(LkFrame.horizontal > LkFrame.screenHorizontal)
        assert(LkFrame.top > LkFrame.screenTop)
    }
}

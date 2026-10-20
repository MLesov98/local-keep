package com.localkeep.app

import com.localkeep.core.ui.LkNotices
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * An Undo that could not put the item back says so as a refusal, not as a success.
 *
 * "Couldn't put it back. It is still in Trash." was posted through the success channel, so it
 * arrived with the jade check and the success buzz — the opposite of what the sentence says.
 */
class UndoRestoreFailureIsARefusalTest {

    @BeforeTest
    @AfterTest
    fun reset() = LkNotices.clear()

    @Test
    fun `a failed restore is announced with the refusal tone and its own sentence`() = runBlocking {
        TrashUndoBus.restoreAndAnnounce(
            restore = { error("vault write failed") },
            restoredNotice = "Restored",
            restoreFailedNotice = "Couldn't put it back. It is still in Trash.",
        )
        val notice = LkNotices.current.value!!
        assertEquals(LkNotices.Message.Text("Couldn't put it back. It is still in Trash."), notice.message)
        assertEquals(LkNotices.Tone.Refusal, notice.tone)
    }

    /** The control: a restore that worked is still a success. */
    @Test
    fun `a successful restore stays a success`() = runBlocking {
        TrashUndoBus.restoreAndAnnounce(
            restore = {},
            restoredNotice = "Restored",
            restoreFailedNotice = "Couldn't put it back. It is still in Trash.",
        )
        val notice = LkNotices.current.value!!
        assertEquals(LkNotices.Message.Text("Restored"), notice.message)
        assertEquals(LkNotices.Tone.Success, notice.tone)
    }
}

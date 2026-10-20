package com.localkeep.feature.vaultlist

import com.localkeep.core.ui.LkNotices
import com.localkeep.core.vault.FolderNameTakenException
import com.localkeep.core.vault.FolderTooDeepException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A refused folder write is announced as a refusal, not as a success.
 *
 * Both folder-write notice sites (create, and a drag that re-parents) went through the success
 * channel, so "Folders can nest eight levels deep" came with a jade check and a success buzz.
 */
class FolderWriteRefusalToneTest {

    @BeforeTest
    @AfterTest
    fun reset() = LkNotices.clear()

    @Test
    fun `a too-deep refusal is posted with the refusal tone and its own message`() {
        postFolderWriteRefusal(FolderTooDeepException(), R.string.vaultlist_error_action)
        val notice = LkNotices.current.value!!
        assertEquals(LkNotices.Tone.Refusal, notice.tone)
        assertEquals(LkNotices.Message.Res(R.string.vaultlist_folders_error_too_deep), notice.message)
    }

    @Test
    fun `a name held by a trashed folder is a refusal too`() {
        postFolderWriteRefusal(FolderNameTakenException(inTrash = true), R.string.vaultlist_error_action)
        val notice = LkNotices.current.value!!
        assertEquals(LkNotices.Tone.Refusal, notice.tone)
        assertEquals(LkNotices.Message.Res(R.string.vaultlist_folders_error_name_in_trash), notice.message)
    }

    @Test
    fun `an unclassified failure falls back to the screen's action error, still as a refusal`() {
        postFolderWriteRefusal(IllegalStateException("io"), R.string.vaultlist_error_action)
        val notice = LkNotices.current.value!!
        assertEquals(LkNotices.Tone.Refusal, notice.tone)
        assertEquals(LkNotices.Message.Res(R.string.vaultlist_error_action), notice.message)
    }

    /** The control: ordinary confirmations posted through `post` stay successes. */
    @Test
    fun `a plain post is still a success`() {
        LkNotices.post(R.string.vaultlist_notice_favourited)
        assertEquals(LkNotices.Tone.Success, LkNotices.current.value?.tone)
    }
}

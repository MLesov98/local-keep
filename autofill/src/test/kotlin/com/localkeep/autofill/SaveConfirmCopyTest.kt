package com.localkeep.autofill

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which of the three save-confirmation titles, and which primary-action word, a given state picks.
 *
 * The platform's own save prompt distinguishes a replacement from a first save; LocalKeep's screen
 * has the strings to do the same, and this pins that it actually uses them. The selection used to
 * sit inline in `SaveConfirmActivity.onCreate`, which needs Hilt, an injected repository and a live
 * lock manager to construct — so the branch shipped untested and a title reading "Save a new login"
 * over a vault that already held that login would have looked exactly like a working screen.
 *
 * The locked row is the one worth reading twice. `SaveHandler` cannot search a locked vault, so it
 * resolves no match and `isUpdate` is false there in practice; the combination is still asserted
 * because a proposal captured while the vault was open can reach this screen after it has locked,
 * and the neutral title plus "Save" is the honest pair for a match nobody can confirm yet.
 */
class SaveConfirmCopyTest {

    @Test
    fun `a login the vault already holds is offered as an update, not a save`() {
        assertEquals(
            R.string.autofill_save_confirm_password_title_update,
            saveConfirmTitleRes(hasCard = false, locked = false, isUpdate = true),
        )
        assertEquals(
            R.string.autofill_save_confirm_update,
            saveConfirmActionRes(locked = false, isUpdate = true),
        )
    }

    @Test
    fun `a login the vault does not hold is offered as a new one`() {
        assertEquals(
            R.string.autofill_save_confirm_password_title_new,
            saveConfirmTitleRes(hasCard = false, locked = false, isUpdate = false),
        )
        assertEquals(
            R.string.autofill_save_confirm_save,
            saveConfirmActionRes(locked = false, isUpdate = false),
        )
    }

    @Test
    fun `a locked vault says neither, because it could not look`() {
        assertEquals(
            R.string.autofill_save_confirm_password_title_locked,
            saveConfirmTitleRes(hasCard = false, locked = true, isUpdate = false),
        )
        assertEquals(
            R.string.autofill_save_confirm_save,
            saveConfirmActionRes(locked = true, isUpdate = false),
        )
    }

    @Test
    fun `a stale match found before the lock does not promise an update`() {
        assertEquals(
            R.string.autofill_save_confirm_password_title_locked,
            saveConfirmTitleRes(hasCard = false, locked = true, isUpdate = true),
        )
        assertEquals(
            R.string.autofill_save_confirm_save,
            saveConfirmActionRes(locked = true, isUpdate = true),
        )
    }

    @Test
    fun `a captured card keeps its own title whatever the vault is doing`() {
        // Cards are always added, never updated — see SaveConfirmActivity.saveCard — so neither
        // the lock state nor a login match may reword this one.
        assertEquals(
            R.string.autofill_save_confirm_card_title,
            saveConfirmTitleRes(hasCard = true, locked = false, isUpdate = false),
        )
        assertEquals(
            R.string.autofill_save_confirm_card_title,
            saveConfirmTitleRes(hasCard = true, locked = true, isUpdate = true),
        )
    }

    @Test
    fun `a login with no username gets a title that does not name one`() {
        // 2026-10-02: password-only forms are saved now, and every login title takes a username —
        // "Save a new login for  on example.com?" with the gap where it should have been.
        assertEquals(
            R.string.autofill_save_confirm_password_title_no_username,
            saveConfirmTitleRes(hasCard = false, locked = false, isUpdate = false, hasUsername = false),
        )
        assertEquals(
            R.string.autofill_save_confirm_password_title_no_username,
            saveConfirmTitleRes(hasCard = false, locked = true, isUpdate = true, hasUsername = false),
        )
        assertEquals(
            R.string.autofill_save_confirm_password_title_no_username,
            saveConfirmTitleRes(
                hasCard = false,
                locked = false,
                isUpdate = false,
                awaitingUnlock = true,
                hasUsername = false,
            ),
        )
        assertEquals(
            R.string.autofill_save_confirm_password_title_update_no_username,
            saveConfirmTitleRes(hasCard = false, locked = false, isUpdate = true, hasUsername = false),
        )
    }
}

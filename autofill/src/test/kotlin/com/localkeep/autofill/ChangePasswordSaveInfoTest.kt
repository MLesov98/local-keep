package com.localkeep.autofill

import android.content.Context
import android.service.autofill.SaveInfo
import android.view.View
import android.view.autofill.AutofillId
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A change-password form's New and Confirm boxes are part of what the platform watches for a save.
 *
 * Only the first password box was declared — on a change-password form, the Current one, which the
 * stored login had filled. The platform offers a save only when a declared box changed, so with New
 * and Confirm undeclared it saw the Current box unchanged and never asked: the vault kept the dead
 * password and the next sign-in filled one that no longer works (2026-10-02). Whether the platform
 * then raises the prompt is its behaviour and needs a device; what LocalKeep declares is checked
 * here.
 *
 * `getRequiredIds`/`getOptionalIds` are `@hide`, so reflection, as `SplitSignInSaveTest` reads flags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangePasswordSaveInfoTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newField(type: FieldType) = FillableField(View(context).autofillId!!, type)

    @Suppress("UNCHECKED_CAST")
    private fun ids(saveInfo: SaveInfo, getter: String): List<AutofillId> =
        (SaveInfo::class.java.getMethod(getter).invoke(saveInfo) as Array<AutofillId>?)?.toList().orEmpty()

    @Test
    fun `New and Confirm are declared alongside the Current box`() {
        val current = newField(FieldType.Password)
        val new = newField(FieldType.Password)
        val confirm = newField(FieldType.Password)

        val saveInfo = requireNotNull(
            DatasetBuilder.buildSaveInfo(
                usernameField = null,
                passwordField = current,
                urlField = null,
                otherPasswordFields = listOf(new, confirm),
            ),
        )

        assertEquals(listOf(current.id), ids(saveInfo, "getRequiredIds"))
        assertTrue(
            ids(saveInfo, "getOptionalIds").containsAll(listOf(new.id, confirm.id)),
            "the rotated password's boxes are not watched, so the platform never offers to save it",
        )
    }

    @Test
    fun `a single-password sign-in declares nothing extra`() {
        // The control: an ordinary login form keeps the shape it always had.
        val username = newField(FieldType.Username)
        val password = newField(FieldType.Password)

        val saveInfo = requireNotNull(
            DatasetBuilder.buildSaveInfo(usernameField = username, passwordField = password, urlField = null),
        )

        assertEquals(listOf(username.id, password.id), ids(saveInfo, "getRequiredIds"))
        assertEquals(emptyList(), ids(saveInfo, "getOptionalIds"))
    }
}

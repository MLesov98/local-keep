package com.localkeep.autofill

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "Generate password" on a change-password form leaves the Current box alone (2026-10-02).
 *
 * The generator filled every password box, so on a Current / New / Confirm form the Current box got
 * the new password as well and the site refused the change. The distinction has to survive every
 * hop the field list makes as Intent extras — the unlock gate, the domain confirmation and the
 * picker each rebuild it on the other side — or the generator offered after an unlock is the old
 * one again.
 */
@RunWith(RobolectricTestRunner::class)
class GeneratedPasswordTargetsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newId() = View(context).autofillId!!

    private fun changePasswordForm(): List<FillableField> = listOf(
        FillableField(newId(), FieldType.Password, isCurrentPassword = true),
        FillableField(newId(), FieldType.Password),
        FillableField(newId(), FieldType.Password),
    )

    @Test
    fun `fills New and Confirm but not Current`() {
        val form = changePasswordForm()

        assertEquals(form.drop(1), SuggestionPolicy.generatedPasswordTargets(form))
    }

    @Test
    fun `a sign-up form with no Current box still fills both boxes`() {
        // The control: the confirmation box is why the generator fills more than one field at all.
        val form = listOf(
            FillableField(newId(), FieldType.Username),
            FillableField(newId(), FieldType.Password),
            FillableField(newId(), FieldType.Password),
        )

        assertEquals(form.drop(1), SuggestionPolicy.generatedPasswordTargets(form))
    }

    @Test
    fun `a box that only reads as current is still filled when there is no other`() {
        // A sign-in form declaring autocomplete="current-password" has nothing to tell apart.
        val only = FillableField(newId(), FieldType.Password, isCurrentPassword = true)

        assertEquals(listOf(only), SuggestionPolicy.generatedPasswordTargets(listOf(only)))
    }

    @Test
    fun `the picker intent keeps which box is Current`() {
        val form = changePasswordForm()

        val restored = PickerActivity.fieldsFromExtras(
            PickerActivity.intent(context, PickerActivity.MODE_GENERATE, form, "example.com", "com.example"),
        )

        assertEquals(form, restored)
    }

    @Test
    fun `the unlock and reprompt intents keep which box is Current`() {
        val form = changePasswordForm()

        val unlocked = AuthActivity.fieldsFromExtras(
            AuthActivity.unlockIntent(context, form, "example.com", "com.example", isNewPasswordForm = true),
        )
        val reprompted = AuthActivity.fieldsFromExtras(
            AuthActivity.repromptIntent(context, login(), form, "example.com", "com.example"),
        )

        assertEquals(form, unlocked)
        assertEquals(form, reprompted)
    }

    @Test
    fun `the domain confirmation intent keeps which box is Current`() {
        val form = changePasswordForm()

        val restored = ConfirmDomainActivity.fieldsFromExtras(
            ConfirmDomainActivity.intent(context, form, "example.com", "com.example.browser", true),
        )

        assertEquals(form, restored)
    }

    private fun login() = VaultItem(
        id = UUID.randomUUID(),
        title = "Example",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "me@example.com", password = "old"),
    )
}

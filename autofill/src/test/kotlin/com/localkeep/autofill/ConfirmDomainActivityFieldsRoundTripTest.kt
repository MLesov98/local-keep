package com.localkeep.autofill

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ConfirmDomainActivity.intent() writes the field set the round trip has to survive, and
 * fieldsFromExtras() reads it back after the user taps Allow. Before this fix the intent carried
 * only the first password id and no card ids at all, so a card-only checkout form's fields never
 * survived the gate: fieldsFromExtras returned empty, ConfirmDomainActivity.onCreate treated that
 * as malformed input and cancelled — the "Confirm app & site" row a permanent dead tap.
 */
@RunWith(RobolectricTestRunner::class)
class ConfirmDomainActivityFieldsRoundTripTest {

    @Test
    fun `intent round trip preserves every password id, every card id and the new password flag`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun newId() = View(context).autofillId!!

        val fields = listOf(
            FillableField(newId(), FieldType.Username),
            FillableField(newId(), FieldType.Password),
            // A sign-up form's confirmation box: the second password id, which the truncated
            // single-id extra used to drop.
            FillableField(newId(), FieldType.Password),
            FillableField(newId(), FieldType.Totp),
            FillableField(newId(), FieldType.Url),
            FillableField(newId(), FieldType.CardNumber),
            FillableField(newId(), FieldType.CardExpiry),
            FillableField(newId(), FieldType.CardSecurityCode),
            FillableField(newId(), FieldType.CardHolder),
        )

        val intent = ConfirmDomainActivity.intent(
            context = context,
            fields = fields,
            webDomain = "example.com",
            packageName = "com.example.browser",
            isNewPasswordForm = true,
        )

        assertEquals(fields, ConfirmDomainActivity.fieldsFromExtras(intent))
        assertEquals(
            true,
            intent.getBooleanExtra("com.localkeep.autofill.CONFIRM_NEW_PASSWORD_FORM", false),
        )
    }

    @Test
    fun `a card only field list survives the round trip instead of cancelling the gate`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun newId() = View(context).autofillId!!

        val fields = listOf(
            FillableField(newId(), FieldType.CardNumber),
            FillableField(newId(), FieldType.CardExpiry),
            FillableField(newId(), FieldType.CardSecurityCode),
            FillableField(newId(), FieldType.CardHolder),
        )

        val intent = ConfirmDomainActivity.intent(
            context = context,
            fields = fields,
            webDomain = "shop.example.com",
            packageName = "com.example.browser",
        )

        // Empty here is exactly what onCreate treats as malformed input and cancels on — a
        // card-only form must not round-trip to an empty list.
        assertEquals(fields, ConfirmDomainActivity.fieldsFromExtras(intent))
    }
}

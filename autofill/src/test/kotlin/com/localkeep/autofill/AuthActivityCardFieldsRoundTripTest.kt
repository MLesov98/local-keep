package com.localkeep.autofill

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.model.Card
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A stored card's autofill row has to survive the reprompt.
 *
 * `FillResponseFactory` points every Card's authentication row at `AuthActivity`, but the intent
 * carried username, password, TOTP and URL ids only — no card ids at all. So `fieldsFromExtras`
 * came back without them, and the row was a permanent dead tap in both directions: a card-only
 * checkout form left `fields` empty and the activity cancelled before drawing anything, while a
 * mixed form got as far as `DatasetBuilder.buildCardDataset`, which fills only `FieldType.Card*`,
 * found none, returned null and cancelled there. A Pro feature that could never once fill a card.
 *
 * This is the same defect `ConfirmDomainActivityFieldsRoundTripTest` describes and fixed for its own
 * activity — the fix landed on one of the two and not on the one every card row actually points at.
 * Found 2026-09-03.
 */
@RunWith(RobolectricTestRunner::class)
class AuthActivityCardFieldsRoundTripTest {

    private fun cardItem() = VaultItem(
        id = UUID.randomUUID(),
        title = "Bank",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Card(holder = "A Person", number = "4111111111111111"),
    )

    @Test
    fun `a card-only checkout form's ids survive the reprompt intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun newId() = View(context).autofillId!!

        // Exactly what a payment form offers: no username, no password, nothing the old extras knew.
        val number = newId()
        val expiry = newId()
        val cvv = newId()
        val holder = newId()
        val fields = listOf(
            FillableField(number, FieldType.CardNumber),
            FillableField(expiry, FieldType.CardExpiry),
            FillableField(cvv, FieldType.CardSecurityCode),
            FillableField(holder, FieldType.CardHolder),
        )

        val intent = AuthActivity.repromptIntent(
            context = context,
            item = cardItem(),
            fields = fields,
            webDomain = "shop.example",
            packageName = "com.example.browser",
        )

        val restored = AuthActivity.fieldsFromExtras(intent)

        // Non-empty is the whole point: `onCreate` treats an empty field list as malformed and
        // cancels, which is how this presented to the user.
        assertTrue(restored.isNotEmpty(), "a card-only form must survive the reprompt")
        assertEquals(
            mapOf(
                FieldType.CardNumber to number,
                FieldType.CardExpiry to expiry,
                FieldType.CardSecurityCode to cvv,
                FieldType.CardHolder to holder,
            ),
            restored.associate { it.type to it.id },
        )
    }

    @Test
    fun `a mixed form keeps its login ids alongside the card ids`() {
        // The control. The login half already worked, and a fix that carried cards by dropping
        // usernames or the second password id would be a different regression wearing the same fix.
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun newId() = View(context).autofillId!!

        val username = newId()
        val password = newId()
        val confirmPassword = newId()
        val number = newId()

        val intent = AuthActivity.repromptIntent(
            context = context,
            item = cardItem(),
            fields = listOf(
                FillableField(username, FieldType.Username),
                FillableField(password, FieldType.Password),
                FillableField(confirmPassword, FieldType.Password),
                FillableField(number, FieldType.CardNumber),
            ),
            webDomain = "shop.example",
            packageName = "com.example.browser",
        )

        val restored = AuthActivity.fieldsFromExtras(intent)

        assertEquals(username, restored.single { it.type == FieldType.Username }.id)
        assertEquals(
            listOf(password, confirmPassword),
            restored.filter { it.type == FieldType.Password }.map { it.id },
        )
        assertEquals(number, restored.single { it.type == FieldType.CardNumber }.id)
    }

    @Test
    fun `a form with no card fields carries no card ids`() {
        // The other control: the extras are written unconditionally, so a null id must round-trip
        // as absent rather than as a FillableField with a null AutofillId.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val username = View(context).autofillId!!

        val intent = AuthActivity.repromptIntent(
            context = context,
            item = cardItem(),
            fields = listOf(FillableField(username, FieldType.Username)),
            webDomain = null,
            packageName = "com.example.app",
        )

        val restored = AuthActivity.fieldsFromExtras(intent)

        assertEquals(listOf(FieldType.Username), restored.map { it.type })
    }
}

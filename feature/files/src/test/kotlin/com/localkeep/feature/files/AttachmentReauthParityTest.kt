package com.localkeep.feature.files

import com.localkeep.core.model.ALWAYS_REAUTH_FIELDS
import com.localkeep.core.model.CARD_CVV_FIELD
import com.localkeep.core.model.CARD_PIN_FIELD
import com.localkeep.core.model.COORDINATE_FIELD
import com.localkeep.core.model.Card
import com.localkeep.core.model.Coordinate
import java.time.Instant
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.PHRASE_FIELD
import com.localkeep.core.model.PHRASE_PASSPHRASE_FIELD
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attachment gate and `ALWAYS_REAUTH_FIELDS` are two halves of one rule.
 *
 * `ReauthFields.kt` says "Never copy this set", and `AttachmentPicker` keeps something close to a
 * copy — legitimately, because that set names *fields* while the picker asks whether a content
 * *type* owns one. What was not legitimate was the reason written beside it, which claimed the set
 * was `internal` to `:feature:itemedit`; it has been public in `:core:model` for some time, so the
 * stated justification for the second list had quietly stopped being true (V28, 2026-09-12).
 *
 * The lists agreed when this was written. This test is what makes them keep agreeing: add a field
 * to `ALWAYS_REAUTH_FIELDS` for a type the picker does not gate, and the build says so — instead of
 * an attachment screen opening with no challenge beside a field that always demands one.
 */
class AttachmentReauthParityTest {

    /** Every always-reauth field, mapped to a content that owns it. */
    private val fieldOwners: Map<String, ItemContent> = mapOf(
        "totpSecret" to Totp(secretBase32 = "JBSWY3DPEHPK3PXP"),
        CARD_CVV_FIELD to Card(cvv = "737"),
        CARD_PIN_FIELD to Card(pin = "4291"),
        COORDINATE_FIELD to Coordinate(capturedAt = Instant.EPOCH),
        PHRASE_FIELD to SeedPhrase(),
        PHRASE_PASSPHRASE_FIELD to SeedPhrase(),
        "ssn" to Identity(ssn = "078-05-1120"),
        "passport" to Identity(passport = "X01234567"),
        "license" to Identity(license = "LOVEL751213AL9AA"),
    )

    @Test
    fun `every always-reauth field belongs to a type the attachment gate covers`() {
        val unmapped = ALWAYS_REAUTH_FIELDS - fieldOwners.keys
        assertTrue(
            "ALWAYS_REAUTH_FIELDS gained $unmapped with no owning content type in this test — " +
                "decide whether AttachmentPickerViewModel.isHighValueByType must gate it, then map it here",
            unmapped.isEmpty(),
        )
        ALWAYS_REAUTH_FIELDS.forEach { field ->
            val content = fieldOwners.getValue(field)
            assertTrue(
                "$field always re-asks, but an item holding it opens its attachments unchallenged",
                AttachmentPickerViewModel.isHighValueByType(content),
            )
        }
    }

    /** The deliberate non-type case: a Login is gated only when it actually stores a seed. */
    @Test
    fun `a login is gated on its stored seed, not on being a login`() {
        assertFalse(AttachmentPickerViewModel.isHighValueByType(Login(username = "ada", password = "hunter2")))
        assertTrue(
            AttachmentPickerViewModel.isHighValueByType(
                Login(username = "ada", password = "hunter2", totp = Totp(secretBase32 = "JBSWY3DPEHPK3PXP")),
            ),
        )
    }

    /** A plain note owns none of them and must not cost the user a prompt. */
    @Test
    fun `a note is not high value`() {
        assertFalse(AttachmentPickerViewModel.isHighValueByType(Note(body = "shopping list")))
    }
}

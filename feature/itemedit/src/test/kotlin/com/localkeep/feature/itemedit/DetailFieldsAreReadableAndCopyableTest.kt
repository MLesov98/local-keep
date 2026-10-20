package com.localkeep.feature.itemedit

import com.localkeep.core.model.CardBrand
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Three detail-screen gaps from the 2026-09-20 release sweep, in one place because they are one
 * complaint: a value the screen holds but the user cannot take off it.
 *
 * - **4B-8** — a revealed card number rendered as `4111111111111111`, one unbroken run, while the
 *   masked face directly above grouped the same digits 4-4-4-4 and the editor showed
 *   `4111 1111 1111 1111`. The difference between a number a human can read back off a phone and
 *   one they cannot.
 * - **4B-10** — the editor said `PASSPORT NUMBER` and `DRIVER'S LICENSE`; this screen said
 *   `PASSPORT` and `LICENSE`. The project glossary is binding on terminology, and the
 *   national-ID row beside them already shared its key with the editor.
 * - **4B-11** — of an identity's rows only the three government-ID numbers could be copied. The
 *   name, the company and the whole address — the fields an identity exists to paste into someone
 *   else's form — had no copy path at all, against spec §5.3.
 */
class DetailFieldsAreReadableAndCopyableTest {

    private val detail =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt").readText()

    private val identity = detail
        .substringAfter("private fun IdentityDetail(", "")
        .substringBefore("\n@Composable")

    // ---- 4B-8 -------------------------------------------------------------------------------

    @Test
    fun `the grouping the detail's number row now draws is the one the card face uses`() {
        assertEquals("4111 1111 1111 1111", cardNumberFace("4111111111111111", CardBrand.VISA, revealed = true))
        assertEquals("3782 822463 10005", cardNumberFace("378282246310005", CardBrand.AMEX, revealed = true))
    }

    @Test
    fun `the revealed number row is drawn grouped`() {
        val row = detail.substringAfter("R.string.itemedit_detail_card_number_label", "")
            .substringBefore("SecretReadField(")
        assertTrue(row.isNotEmpty(), "the card number row moved; this test no longer reads it")
        assertTrue(
            "displayValue = cardNumberFace(state.cardNumber, brand, revealed = true)" in row,
            "the revealed card number is one unbroken run of digits again",
        )
    }

    /**
     * And only the drawing. Copy, the masked bullet count and the screen reader must all still get
     * the raw digits — a clipboard carrying `4111 1111 1111 1111` would not paste into a payment
     * form, which is the one thing copying a card number is for.
     */
    @Test
    fun `the value behind it is untouched`() {
        val row = detail.substringAfter("R.string.itemedit_detail_card_number_label", "")
            .substringBefore("displayValue")
        assertTrue("state.cardNumber," in row, "the row no longer carries the raw number as its value")
    }

    // ---- 4B-10 ------------------------------------------------------------------------------

    @Test
    fun `the identity's document rows use the editor's own labels`() {
        assertTrue("R.string.itemedit_identity_passport_field_label" in identity)
        assertTrue("R.string.itemedit_identity_license_field_label" in identity)
        assertTrue(
            "itemedit_detail_identity_passport_label" !in detail &&
                "itemedit_detail_identity_license_label" !in detail,
            "the detail-only short labels are back",
        )
    }

    @Test
    fun `and the short labels are gone from every locale, not just from English`() {
        listOf("", "-fr", "-de", "-es", "-pt").forEach { locale ->
            val strings = File("src/main/res/values$locale/strings.xml").readText()
            assertTrue(
                "itemedit_detail_identity_passport_label" !in strings &&
                    "itemedit_detail_identity_license_label" !in strings,
                "values$locale still carries the orphaned identity labels",
            )
        }
    }

    // ---- 4B-11 ------------------------------------------------------------------------------

    @Test
    fun `every identity row can be copied`() {
        // Five plain rows: name, company, email, phone — and the address, which is its own row.
        assertEquals(
            4,
            Regex("copyable = true").findAll(identity).count(),
            "an identity row lost its copy action",
        )
        assertTrue(
            "ClipboardHelper.copy(context, addressLabel, addressValue)" in identity,
            "the address, the longest thing on an identity, still has no way off the screen",
        )
        assertTrue(
            "R.string.itemedit_action_copy, addressLabel" in identity,
            "the address copy is unnamed, so TalkBack's actions menu does not offer it",
        )
    }
}

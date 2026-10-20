package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 4A-F9, 2026-09-20: the passkey detail listed `CREDENTIAL.BOUNDPACKAGE` and
 * `CREDENTIAL.BOUNDCERTSHA256` under CUSTOM FIELDS, each masked with its own Show and Copy. Those
 * are `:credential`'s anti-phishing binding keys — plumbing — and under their raw names they read
 * as a fault in the app rather than as a fact about the credential.
 *
 * The fix is display-only, and that is the part worth protecting: the stored name has to keep
 * matching what `RpIdValidator` looks up on every ceremony, so the two literals are checked against
 * that file rather than trusted. `:feature:itemedit` does not depend on `:credential` and those
 * constants are `internal` to it, so the duplication cannot be removed — only pinned.
 */
class PasskeyBindingFieldNamesMatchTest {

    @Test
    fun `the two binding keys are the ones credential actually writes`() {
        val src = File("../../credential/src/main/kotlin/com/localkeep/credential/RpIdValidator.kt")
        assertTrue(src.exists(), "RpIdValidator.kt not found at ${src.absolutePath}")
        val text = src.readText()

        assertTrue(
            """const val BOUND_PACKAGE_FIELD = "$BOUND_PACKAGE_FIELD"""" in text,
            "the display mapping keys off a name :credential no longer writes, so the passkey " +
                "detail is back to showing a raw identifier",
        )
        assertTrue(
            """const val BOUND_CERT_FIELD = "$BOUND_CERT_FIELD"""" in text,
            "same for the certificate field",
        )
    }

    @Test
    fun `each binding key resolves to its own label`() {
        assertEquals(
            R.string.itemedit_custom_field_bound_package,
            wellKnownCustomFieldLabelRes(BOUND_PACKAGE_FIELD),
        )
        assertEquals(
            R.string.itemedit_custom_field_bound_cert,
            wellKnownCustomFieldLabelRes(BOUND_CERT_FIELD),
        )
    }

    @Test
    fun `an ordinary custom field keeps the name its owner typed`() {
        // The control, and the reason this is a lookup rather than a prettifier: a user's own field
        // names must survive untouched, including ones that merely look like keys.
        assertNull(wellKnownCustomFieldLabelRes("Account number"))
        assertNull(wellKnownCustomFieldLabelRes(""))
        assertNull(wellKnownCustomFieldLabelRes("credential.boundPackage2"))
        assertNull(
            wellKnownCustomFieldLabelRes("CREDENTIAL.BOUNDPACKAGE"),
            "exact match only: the stored key is the one :credential writes, in its own case",
        )
    }

    /**
     * A source scan for the call site, for the reason `PhraseRevealColourMatchesOtherScreensTest`
     * gives: this module has no Robolectric wiring. Paired with the fallback, which was true before
     * the fix too, so a region that stopped matching fails instead of passing empty.
     */
    @Test
    fun `the detail screen resolves the label before falling back to the stored name`() {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt")
        assertTrue(src.exists(), "ItemDetailScreen.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val start = text.indexOf("val fieldLabel =")
        assertTrue(start > 0, "the custom-field label assignment not found — did it move?")
        val end = text.indexOf("if (field.type == CustomFieldType.HIDDEN)", start)
        assertTrue(end > start, "the hidden-field branch that follows it was not found")
        val body = text.substring(start, end)

        assertTrue(
            "wellKnownCustomFieldLabelRes(field.name)" in body,
            "a key the app wrote must be named for the reader before anything else; found:\n$body",
        )
        assertTrue(
            "itemedit_detail_field_fallback" in body,
            "the control: an unnamed custom field still falls back to \"Field N\"",
        )
    }
}

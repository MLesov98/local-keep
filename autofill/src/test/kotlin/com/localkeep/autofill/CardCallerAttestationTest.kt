package com.localkeep.autofill

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Card rows obey the same caller rule the login rows do.
 *
 * `FillResponseFactory` states it three lines above the login path: "A browser whose package we
 * cannot name is a caller we cannot attest, so nothing from the vault is offered and neither is a
 * suggestion row that would reach it." Card rows are from the vault by that definition — each one
 * draws the item's title, which `SaveConfirmActivity.saveCard()` builds as "<domain> ****<last4>" —
 * and they were appended after the whole attestation chain rather than inside it.
 *
 * The chain misses exactly one caller: both package and domain null. The login guard returns on
 * `webDomain != null && packageName == null`; a card form keeps that caller out of the empty-match
 * branch, and `verifyOrPin` and the domain-confirmation gate are each conditional on the field that
 * is null. Found by the F15 QA persona, 2026-08-30, which was careful to say it could not construct
 * such a request on an API 35 image — so this closes a stated invariant, not a demonstrated leak.
 *
 * Asserted on the source because `cardDatasets` is private and needs a `Context` and a
 * `PendingIntent`; the same technique, for the same reason, as `CardFillGateTest`'s note that a
 * signature can prove what a test cannot.
 */
class CardCallerAttestationTest {

    private val source =
        File("src/main/kotlin/com/localkeep/autofill/FillResponseFactory.kt").readText()

    private fun cardDatasetsBody(): String =
        source.substringAfter("private fun cardDatasets(").substringBefore("\n    }")

    @Test
    fun `an unnameable caller is offered no card rows`() {
        assertTrue(
            "cardDatasets must refuse a null packageName. Found: ${cardDatasetsBody()}",
            "if (packageName == null) return emptyList()" in cardDatasetsBody(),
        )
    }

    /**
     * Ordering, which is the half a rewrite is most likely to lose: the refusal has to come before
     * the vault is read, not after. A version that snapshotted the vault and then filtered would
     * pass the assertion above and still have loaded every card into memory for a caller it had
     * already decided not to serve.
     */
    @Test
    fun `the refusal comes before the vault is read`() {
        val body = cardDatasetsBody()
        val refusal = body.indexOf("if (packageName == null) return emptyList()")
        val read = body.indexOf("visibilitySnapshot()")
        assertTrue("both the refusal and the vault read must be present", refusal >= 0 && read >= 0)
        assertTrue(
            "the caller check must precede reading the vault; found refusal at $refusal, read at $read",
            refusal < read,
        )
    }

    /**
     * The login rule this borrows from must still be there. If that guard is ever removed the
     * comment above the card check stops describing anything, and the reasoning for the card check
     * goes with it.
     */
    @Test
    fun `the login path still refuses a browser it cannot name`() {
        assertTrue(
            "the login guard this rule is derived from has gone",
            "if (webDomain != null && packageName == null) return saveOnly(saveInfo)" in source,
        )
    }
}

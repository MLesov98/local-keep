package com.localkeep.autofill

import com.localkeep.core.vault.ProTypeQuota
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The autofill card-capture route obeys the same allowance the in-app editor does.
 *
 * `handleCard` carried a binary `if (!isPro) return null`, which was right while a free vault could
 * hold no card at all and wrong from 2026-09-03, when [ProTypeQuota.FREE_LIMIT] made the first one
 * free. Nothing gates the `SaveInfo`: `DatasetBuilder.buildSaveInfo` ORs in
 * `SAVE_DATA_TYPE_CREDIT_CARD` on both the locked and the unlocked path, so the platform raised its
 * own "save card?" prompt, the user accepted, `onSaveRequest` mapped the null to
 * `callback.onSuccess()` — the "nothing to confirm" answer — and the card was gone with no error
 * shown. In `foss`, where `isPro` is permanently false, that was every user.
 *
 * So the tier test is out of the capture route entirely and the allowance is enforced where the
 * write happens: [SaveConfirmActivity] is the only point that has an unlocked vault to count
 * against (it unlocks in place) and a screen to say no on. The last two assertions are the pair
 * that has to hold together — the first card allowed, the second still refused — because a fix that
 * only removed the gate would have turned the capture route into an uncapped way past it.
 *
 * Asserted on the source for the reason [CardCallerAttestationTest] gives one file over: a
 * `SaveRequest` carries an `AssistStructure` that cannot be constructed off a device, and this
 * activity needs an injected repository and a real lock manager.
 */
class CardCaptureAllowanceTest {

    private val saveHandler =
        File("src/main/kotlin/com/localkeep/autofill/SaveHandler.kt").readText()
    private val saveConfirm =
        File("src/main/kotlin/com/localkeep/autofill/SaveConfirmActivity.kt").readText()
    private val service =
        File("src/main/kotlin/com/localkeep/autofill/LocalAutofillService.kt").readText()

    private fun handleCardBody(): String =
        saveHandler.substringAfter("private fun handleCard(").substringBefore("\n    private fun ")

    private fun confirmBody(decl: String): String =
        saveConfirm.substringAfter(decl).substringBefore("\n    private fun ")

    @Test
    fun `the capture route applies no binary Pro test`() {
        assertTrue(
            "handleCard must not refuse on entitlement alone — the platform has already offered " +
                "the save by the time it runs, so a refusal here is a silent drop. Found: " +
                handleCardBody(),
            "isPro" !in handleCardBody(),
        )
    }

    @Test
    fun `the write path consults the allowance before it saves`() {
        assertTrue(
            "SaveConfirmActivity must apply ProTypeQuota, the same allowance the editor applies",
            "ProTypeQuota" in saveConfirm,
        )
        val direct = confirmBody("private fun save() {")
        val refusal = direct.indexOf("refuseCardNoRoom()")
        val write = direct.indexOf("performSave()")
        assertTrue("save() must still commit; found: $direct", write >= 0)
        assertTrue(
            "the allowance refusal must come before the commit; found refusal at $refusal, " +
                "commit at $write",
            refusal in 0 until write,
        )
    }

    /**
     * The unlock detour is the path a captured card actually takes most often — a checkout happens
     * while LocalKeep is locked far more often than not — and it reaches `performSave` without
     * going back through `save()`.
     */
    @Test
    fun `the post-unlock path consults the allowance too`() {
        val detour = confirmBody("private fun unlockThenSave(password: CharArray) {")
        val refusal = detour.indexOf("refuseCardNoRoom()")
        val write = detour.indexOf("performSave()")
        assertTrue("the unlock detour must still commit; found: $detour", write >= 0)
        assertTrue(
            "the allowance refusal must gate the post-unlock commit; found refusal at $refusal, " +
                "commit at $write",
            refusal in 0 until write,
        )
    }

    @Test
    fun `a free vault's first card is not refused`() {
        assertFalse(
            "the whole defect: a free vault may create one card, so the capture route must not " +
                "treat a free tier as zero cards",
            ProTypeQuota.exceedsFreeLimit(used = 0, isPro = false),
        )
    }

    @Test
    fun `a free vault's second card is still refused`() {
        assertTrue(
            "removing the Pro gate must not turn the capture route into a way past the cap",
            ProTypeQuota.exceedsFreeLimit(used = ProTypeQuota.FREE_LIMIT, isPro = false),
        )
    }

    @Test
    fun `the service no longer claims cards are Pro`() {
        assertTrue(
            "LocalAutofillService stated \"Cards are Pro.\", which stopped being true on " +
                "2026-09-03 when the free tier gained one",
            "Cards are Pro." !in service,
        )
    }
}

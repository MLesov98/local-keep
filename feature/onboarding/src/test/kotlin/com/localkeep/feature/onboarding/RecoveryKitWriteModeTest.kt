package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Both Recovery Kit exports write into a document the user picked, and both must truncate it first.
 *
 * `ContentResolver.openOutputStream(uri)` is mode `"w"`, and `"w"` does not imply `O_TRUNC` —
 * `FileUtils.translateModeStringToPosix` maps a leading `w` to `O_WRONLY | O_CREAT` and adds
 * `O_TRUNC` only for an explicit `t`, which is why the platform javadoc says `"w"` "may or may not
 * truncate". Point the picker at an existing, longer kit and the old file's tail survives past the
 * new document's `%%EOF`; a PDF reader finds the cross-reference table by scanning backwards from
 * the end of the file, so the kit either fails to open or resolves to the previous document. The
 * app meanwhile records `RECOVERY_KIT_SAVED` and says the kit was written, so the discovery comes
 * at recovery time.
 *
 * The second half is parity. `RecoveryKitViewModel` has emptied the document on a failed write
 * since 2026-08-10 ("a partial PDF looks like a Recovery Kit where an empty file does not") and
 * `OnboardingViewModel`, which performs the identical SAF write, never got the same branch — the
 * commit that added it did not touch this file. Both facts are asserted here so a third writer
 * cannot reintroduce either half.
 *
 * Structural, deliberately, and for the reason `RecoveryKitDoneActionTest` gives: both view models
 * need a real `VaultLockManager` and a `Context`, so neither can be constructed in a unit test. The
 * mode-selection behaviour itself — `"wt"` first, `"w"` only when the provider rejects it — is
 * covered behaviourally by [openRecoveryKitDocument]'s own tests below.
 */
class RecoveryKitWriteModeTest {

    private fun source(name: String): String =
        File("src/main/kotlin/com/localkeep/feature/onboarding/$name").readText()

    private fun body(source: String, from: String, until: String): String {
        val body = source.substringAfter(from, missingDelimiterValue = "").substringBefore(until)
        assertTrue(body.isNotBlank(), "could not find `$from` in the source")
        return body
    }

    @Test
    fun `the standalone kit export truncates the document it writes into`() {
        val export = body(
            source("RecoveryKitViewModel.kt"),
            "fun exportPdf(target: Uri) {",
            "fun showQr(",
        )
        assertFalse(
            "openOutputStream(target)" in export,
            "RecoveryKitViewModel.exportPdf must not open the picked document with the one-argument " +
                "openOutputStream, which is mode \"w\" and does not truncate. Overwriting a longer " +
                "existing kit that way leaves the old file's tail after the new %%EOF.",
        )
        assertTrue(
            "openRecoveryKitDocument" in export,
            "RecoveryKitViewModel.exportPdf must open the picked document through " +
                "openRecoveryKitDocument, which asks for \"wt\" and falls back to \"w\" only when the " +
                "provider rejects it. Found instead: $export",
        )
    }

    @Test
    fun `the onboarding kit export truncates the document it writes into`() {
        val export = body(
            source("OnboardingViewModel.kt"),
            "fun exportRecoveryPdf(target: Uri) {",
            "fun showRecoveryQr(",
        )
        assertFalse(
            "openOutputStream(target)" in export,
            "OnboardingViewModel.exportRecoveryPdf must not open the picked document with the " +
                "one-argument openOutputStream, which is mode \"w\" and does not truncate.",
        )
        assertTrue(
            "openRecoveryKitDocument" in export,
            "OnboardingViewModel.exportRecoveryPdf must open the picked document through " +
                "openRecoveryKitDocument, the same way RecoveryKitViewModel.exportPdf does. " +
                "Found instead: $export",
        )
    }

    @Test
    fun `a failed kit export empties the document instead of leaving a partial one`() {
        val sites = mapOf(
            "RecoveryKitViewModel.exportPdf" to body(
                source("RecoveryKitViewModel.kt"),
                "fun exportPdf(target: Uri) {",
                "fun showQr(",
            ),
            "OnboardingViewModel.exportRecoveryPdf" to body(
                source("OnboardingViewModel.kt"),
                "fun exportRecoveryPdf(target: Uri) {",
                "fun showRecoveryQr(",
            ),
        )
        for ((name, export) in sites) {
            assertTrue(
                """openOutputStream(target, "wt")""" in export,
                "$name must empty the picked document on a failed write — a half-written PDF looks " +
                    "like a Recovery Kit where an empty file does not. Reopen it with \"wt\" and " +
                    "close it, best-effort, as RecoveryKitViewModel has since 2026-08-10. " +
                    "Found instead: $export",
            )
        }
    }

    @Test
    fun `the document is asked for in truncating mode first`() {
        val asked = mutableListOf<String>()
        openRecoveryKitDocument { mode -> asked += mode }
        assertTrue(
            asked == listOf("wt"),
            "openRecoveryKitDocument must ask for \"wt\" — write plus truncate — before anything " +
                "else, or an overwritten kit keeps the previous file's tail. Asked for: $asked",
        )
    }

    @Test
    fun `a provider that rejects the truncating mode still gets the kit`() {
        for (refusal in listOf(IllegalArgumentException("bad mode"), UnsupportedOperationException())) {
            val asked = mutableListOf<String>()
            openRecoveryKitDocument { mode ->
                asked += mode
                if (mode == "wt") throw refusal
            }
            assertTrue(
                asked == listOf("wt", "w"),
                "A DocumentsProvider may reject a mode it does not implement, and losing the export " +
                    "entirely is worse than not truncating, so openRecoveryKitDocument must retry " +
                    "with \"w\" after ${refusal::class.simpleName}. Asked for: $asked",
            )
        }
    }
}

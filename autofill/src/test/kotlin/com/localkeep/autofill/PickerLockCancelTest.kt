package com.localkeep.autofill

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-search picker closes when the vault locks — and says why.
 *
 * Nothing in this Activity extends the idle timer: `extendAutoLock` has one call site,
 * `MainActivity.onUserInteraction`, so typing into the picker's search box is not interaction and a
 * user browsing for longer than one idle period is locked out mid-search. Closing is the deliberate
 * control and stays exactly as it was; closing *mutely* is the defect. The window vanishes over
 * another app's form — possibly seconds after the master password was typed into it — which is
 * indistinguishable from the autofill service having crashed or not being installed.
 *
 * Reuses `autofill_auth_error_interrupted`, already shipped in all five locales and already the
 * sentence this gate uses when the vault re-locks under it, rather than minting a sixth string
 * saying the same thing.
 *
 * Asserted on the source: driving this needs a real autofill session over a third-party form.
 */
class PickerLockCancelTest {

    private val source =
        File("src/main/kotlin/com/localkeep/autofill/PickerActivity.kt").readText()

    private fun showSearchBody(): String =
        source.substringAfter("private fun showSearch() {").substringBefore("\n    private fun ")

    @Test
    fun `the lock collector attributes the closure to the lock`() {
        val body = showSearchBody()
        val said = body.indexOf("R.string.autofill_auth_error_interrupted")
        val closed = body.indexOf("this@PickerActivity.cancel()")
        assertTrue("the picker must still close on lock; found: $body", closed >= 0)
        assertTrue(
            "the closure must be attributed before it happens; found the message at $said, the " +
                "close at $closed",
            said in 0 until closed,
        )
    }
}

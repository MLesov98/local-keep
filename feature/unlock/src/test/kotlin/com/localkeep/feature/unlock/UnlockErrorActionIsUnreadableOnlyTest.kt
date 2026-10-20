package com.localkeep.feature.unlock

import com.localkeep.core.vault.UnlockResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The pre-unlock restore action is offered for `VAULT_UNREADABLE` and for nothing else.
 *
 * Both exclusions are security decisions, not tidiness:
 *
 * - **`WRONG_CREDENTIAL`** is the failure a person holding someone else's phone produces on their
 *   first tap. A restore control that appears there is a restore control that appears for them.
 * - **`VAULT_FROM_NEWER_VERSION`** is an *intact* container from a newer build. Restoring an older
 *   backup over it destroys real data and cannot be undone — which is the entire reason
 *   `UnlockResult` splits that case out of `VAULT_UNREADABLE` in the first place, and why
 *   `unlock_error_vault_newer_version` says "do not restore an older backup over it".
 *
 * The test walks **every** [UnlockResult] rather than the three named above, so a variant added
 * later cannot quietly become actionable without this going red.
 */
class UnlockErrorActionIsUnreadableOnlyTest {

    @Test
    fun exactlyOneUnlockResultOffersAnAction() {
        val actionable = UnlockResult.entries.filter { unlockErrorFor(it) is UnlockError.Actionable }
        assertEquals(
            listOf(UnlockResult.VAULT_UNREADABLE),
            actionable,
            "exactly one unlock failure may carry an action",
        )
    }

    @Test
    fun aWrongPasswordOffersNoRestoreRoute() {
        assertIs<UnlockError.Simple>(unlockErrorFor(UnlockResult.WRONG_CREDENTIAL))
    }

    @Test
    fun aVaultFromANewerVersionOffersNoRestoreRoute() {
        assertIs<UnlockError.Simple>(unlockErrorFor(UnlockResult.VAULT_FROM_NEWER_VERSION))
    }

    @Test
    fun anUnreadableVaultCarriesBothTheMessageAndTheAction() {
        val error = unlockErrorFor(UnlockResult.VAULT_UNREADABLE)
        assertIs<UnlockError.Actionable>(error)
        assertEquals(R.string.unlock_error_vault_unreadable, error.messageRes)
        assertEquals(R.string.unlock_error_vault_unreadable_action, error.actionRes)
    }

    /** The control: the three results that opened the vault produce no error at all. */
    @Test
    fun anOpenedVaultProducesNoError() {
        listOf(UnlockResult.REAL, UnlockResult.DURESS, UnlockResult.ALREADY_UNLOCKED).forEach {
            assertEquals(null, unlockErrorFor(it), "$it must produce no error")
        }
    }

    /**
     * The one part of the gate a pure function cannot express: `openRestore` refuses unless the
     * screen is *currently* showing the actionable error. Constructing the view-model needs a
     * Context, a Keystore and a Hilt graph, so this reads the guard out of the source instead. It
     * is a weaker check than the four above and is labelled as such — it proves the line exists,
     * not that it runs.
     */
    @Test
    fun openRestoreIsGuardedOnTheActionableError() {
        val source = File("src/main/kotlin/com/localkeep/feature/unlock/UnlockViewModel.kt").readText()
        val body = source.substringAfter("    fun openRestore() {").substringBefore("\n    }")
        assertTrue(
            "if (_state.value.error !is UnlockError.Actionable) return" in body,
            "openRestore must refuse unless the actionable error is on screen; body was: $body",
        )
    }

    /**
     * The other half of the same gate, in source: `confirmRestore` refuses unless the bytes it is
     * about to write are the bytes `checkSelectedBackup` proved, and the damaged container is
     * copied aside before anything is written.
     *
     * Deliberately weaker than `PreUnlockRestoreNoWriteTest` (instrumented), which drives both
     * properties for real against a real container on a device. This one exists because it is free
     * and it names the two lines; it proves they are present, not that they run.
     */
    @Test
    fun confirmRestoreIsGuardedOnTheProvenBytesAndQuarantinesFirst() {
        val source = File("src/main/kotlin/com/localkeep/feature/unlock/UnlockViewModel.kt").readText()
        val body = source.substringAfter("    fun confirmRestore() {").substringBefore("\n    }")
        assertTrue(
            "val verified = verifiedBackup ?: return" in body && "verified.key != selected.key" in body,
            "confirmRestore must refuse an unproven backup; body was: $body",
        )
        assertTrue(
            "!sha256(bytes).contentEquals(verified.sha256)" in body,
            "confirmRestore must refuse bytes that are not the ones proved; body was: $body",
        )
        assertTrue(
            body.indexOf("preserveDamagedContainers") < body.indexOf("restoreVaultFile"),
            "the damaged container must be copied aside before anything is written",
        )
    }
}

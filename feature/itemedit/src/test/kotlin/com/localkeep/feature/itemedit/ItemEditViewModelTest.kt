package com.localkeep.feature.itemedit

import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The auto-hide that a hidden custom field used to escape.
 *
 * Revealing one from the detail screen runs `toggleFieldRevealed("custom:0")`, and `revealNow` then
 * does two things: it arms an auto-hide under the key `custom:0` that drops the key from
 * `revealedFields`, and it calls `revealCustomFieldNow`, which schedules under the *same* key.
 * `scheduleAutoHide` cancels whatever job that key already holds, so only the second body ever
 * runs — and while it cleared only `revealedCustomFieldIndices`, the detail row, which renders from
 * `revealedFields`, stayed legible long past the 30 s the UI/UX spec §5.2 promises. Every other
 * secret on that screen re-masked, because none of them takes the custom-field branch.
 *
 * The state transition is what is asserted here rather than the ViewModel driving it: this module's
 * `ItemEditViewModel` cannot be built on a JVM — its `VaultRepository` needs a `VaultLockManager`
 * whose `KeystoreManager` opens AndroidKeyStore in its constructor — which is why its own tests are
 * instrumented, and why the sibling of this bug went unnoticed there for so long.
 */
class ItemEditViewModelTest {

    private val withHiddenField = ItemEditUiState(
        customFields = listOf(
            CustomField(name = "recovery codes", value = "8213 4470", type = CustomFieldType.HIDDEN),
        ),
    )

    /**
     * The premise the defect rests on: both reveals name the field with the identical string, so
     * the second schedule destroys the first job instead of joining it.
     */
    @Test
    fun `both reveal paths name a custom field with the same key`() {
        assertEquals("custom:0", customFieldRevealKey(0))
        assertEquals(0, customFieldIndexOf(customFieldRevealKey(0)))
    }

    @Test
    fun `auto-hiding a custom field re-masks the detail row, not only the editor's`() {
        // What `revealNow("custom:0")` leaves behind: the key in `revealedFields` for the detail
        // screen's SecretReadField, the index in the set the editor's own row reads.
        val revealed = withHiddenField.copy(
            revealedFields = setOf(customFieldRevealKey(0)),
            revealedCustomFieldIndices = setOf(0),
        )

        val remasked = revealed.customFieldAutoHidden(0)

        assertFalse(
            customFieldRevealKey(0) in remasked.revealedFields,
            "the detail row is still revealed after the auto-hide fired",
        )
        assertFalse(0 in remasked.revealedCustomFieldIndices, "the editor row is still revealed")
    }

    /**
     * The editor-only entry point, where the key was never added. Subtracting an absent element is
     * a no-op, and no neighbouring secret may be re-masked as a side effect.
     */
    @Test
    fun `auto-hiding a custom field revealed only in the editor leaves other fields alone`() {
        val revealed = withHiddenField.copy(
            revealedFields = setOf("password"),
            revealedCustomFieldIndices = setOf(0),
        )

        val remasked = revealed.customFieldAutoHidden(0)

        assertEquals(setOf("password"), remasked.revealedFields)
        assertTrue(remasked.revealedCustomFieldIndices.isEmpty())
    }
}

package com.localkeep.feature.itemedit

import com.localkeep.core.location.CaptureConvergence
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * An editor holding nothing has nothing to discard.
 *
 * The unsaved-change guard compares the form against a baseline anchored when the editor opened.
 * That is right for an item being edited and wrong for one being created: the starting position
 * moves after the anchor — the add sheet presets a type, the flow can preset a folder, and the type
 * chips at the top of the form change it again — so an editor containing literally nothing could
 * differ from its own baseline and ask whether to discard changes. Reported from a phone on
 * 2026-08-18, and funny exactly because there was nothing there.
 *
 * These pin the rule the fix states: for a **new** item, dirty means "something is in the form",
 * not "something differs from where we started". Asserted on the state comparison rather than
 * through the ViewModel, which needs a repository, a lock manager and an entitlement — the same
 * split the other pure tests in this module use.
 */
class EmptyNewItemIsNotDirtyTest {

    /** What `isDirty()` computes for a new item: the form against a pristine one of the same shape. */
    private fun dirtyAsNew(state: ItemEditUiState, pristine: ItemEditUiState): Boolean {
        val current = state.editableOnly()
        return current != pristine.editableOnly()
            .copy(type = current.type, folderId = current.folderId)
    }

    /**
     * One instance, and every variant `copy`d from it.
     *
     * `ItemEditUiState.itemId` defaults to `UUID.randomUUID()`, so two separately-constructed
     * default states are never equal — writing this test with two of them made it fail for a reason
     * that has nothing to do with the fix. The real editor holds one state and copies it, which is
     * what this mirrors.
     */
    private val pristine = ItemEditUiState()

    @Test
    fun `an untouched new login is not dirty`() {
        assertEquals(false, dirtyAsNew(pristine, pristine))
    }

    @Test
    fun `changing only the type chip is not a change to discard`() {
        // The add sheet presets LOGIN and anchors the baseline there. Tapping "Secure note" at the
        // top of an empty form then made the editor differ from its own baseline — which is true,
        // and is not work the user would be sorry to lose.
        val afterChip = pristine.copy(type = ItemEditUiState.ItemType.NOTE)
        assertNotEquals(
            pristine.editableOnly(),
            afterChip.editableOnly(),
            "the states really do differ — otherwise this test proves nothing",
        )
        assertEquals(false, dirtyAsNew(afterChip, pristine))
    }

    @Test
    fun `arriving inside a folder is not a change to discard`() {
        val inFolder = pristine.copy(folderId = UUID.randomUUID())
        assertEquals(false, dirtyAsNew(inFolder, pristine))
    }

    @Test
    fun `arriving inside a nested folder is not a change to discard`() {
        // `seedFolderFromPending` used to write two fields — the folder *and* the picker's expanded
        // path — and `isDirty` forgave only the first, so pressing the FAB inside a depth-2 folder
        // raised the discard prompt on a form nobody had touched. The expanded path went with the
        // disclosure on 2026-08-19 (the comp's chooser draws the tree flat), so the seed is one
        // field now and this asserts the half that can still regress.
        val inNested = pristine.copy(folderId = UUID.randomUUID())
        assertEquals(false, dirtyAsNew(inNested, pristine))
    }

    @Test
    fun `a capture that ran and settled is not by itself a change to discard`() {
        // `coordinateConvergence` is in-flight state, exactly like `coordinateCapturing` beside it.
        // Left out of editableOnly() it would reintroduce the bug that plan §A exists to fix, one
        // door along: open a new Location item, tap capture, let the receiver settle, press Back,
        // and be asked to discard changes to a form nothing was ever typed into.
        val afterSettle = pristine.copy(
            coordinateCapturing = true,
            coordinateConvergence = CaptureConvergence.SETTLED,
            coordinateCaptureStartedElapsedMillis = 1_000L,
        )
        assertNotEquals(
            pristine,
            afterSettle,
            "the states really do differ — otherwise this test proves nothing",
        )
        assertEquals(false, dirtyAsNew(afterSettle, pristine))
    }

    @Test
    fun `a fix the capture actually produced is still a change to discard`() {
        // The control on the test above. `coordinateFix` is deliberately NOT stripped: a reading
        // the user waited forty-five seconds for is precisely the work the discard prompt exists
        // to protect, and forgiving the whole capture would silently bin it.
        val withFix = pristine.copy(
            coordinateConvergence = CaptureConvergence.SETTLED,
            coordinateFix = com.localkeep.core.model.Coordinate(
                latitude = 42.135098,
                longitude = 24.745129,
                capturedAt = java.time.Instant.parse("2026-08-19T09:14:00Z"),
                accuracyMeters = 8f,
            ),
        )
        assertEquals(true, dirtyAsNew(withFix, pristine))
    }

    @Test
    fun `a title the user typed is still a change to discard`() {
        assertEquals(true, dirtyAsNew(pristine.copy(title = "Bank"), pristine))
    }

    @Test
    fun `a password handed over by the generator is still a change to discard`() {
        // `adoptGeneratedPassword` deliberately does not re-anchor the baseline, because the
        // password exists nowhere else. That must survive this fix.
        assertEquals(true, dirtyAsNew(pristine.copy(password = "correct-horse"), pristine))
    }

    @Test
    fun `a store price arriving mid-session is not a change to discard`() {
        // `proPrice` is display copy that Play can deliver at any moment, including while an empty
        // editor is open. It was compared like a form field.
        assertEquals(false, dirtyAsNew(pristine.copy(proPrice = "€9.99"), pristine))
    }
}

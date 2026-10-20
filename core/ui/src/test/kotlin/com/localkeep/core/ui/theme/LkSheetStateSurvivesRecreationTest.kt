package com.localkeep.core.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [rememberLkSheetState]'s [SheetState] must survive a configuration change at whatever detent the
 * sheet actually reached, not reset to [SheetValue.Hidden].
 *
 * Fixed 2026-09-13: `rememberLkSheetState` built its
 * `SheetState` with a plain `remember(startExpanded, alreadyOpen)`. A configuration change discards
 * a plain `remember` outright, and `MainActivity` declares no `android:configChanges`, so rotating
 * the device really does recreate the Compose tree. Every one of this app's [LkSheet]-based sheets
 * except AddItemSheet's own nav-cancel-return path passes `alreadyOpen = false`, so the recomputed
 * `SheetState` always started at [SheetValue.Hidden] regardless of where the sheet actually was —
 * and `ModalBottomSheet` then ran its unconditional `sheetState.show()` `LaunchedEffect` against
 * that fresh `Hidden` state, replaying the sheet's ~600ms rise and dropping a half-dragged detent.
 *
 * This composes [rememberLkSheetState] directly inside a bare [ModalBottomSheet] rather than
 * through [LkSheet] itself, so the assertions below can read the actual [SheetState.currentValue]
 * -- [LkSheet] does not expose the [SheetState] it builds, by design (its KDoc: exposing the
 * experimental `SheetState` type would push `@OptIn(ExperimentalMaterial3Api::class)` onto every
 * feature-module call site). `alreadyOpen = false` throughout is deliberate: passing `true` would
 * seed `initialValue = Expanded` fresh on *every* composition, restoration included, which would
 * pass whether or not this fix exists and prove nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkSheetStateSurvivesRecreationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var sheetState: SheetState

    @Composable
    private fun Host() {
        sheetState = rememberLkSheetState(startExpanded = false, alreadyOpen = false)
        ModalBottomSheet(onDismissRequest = {}, sheetState = sheetState) {
            Text("content", modifier = Modifier.testTag(TAG))
        }
    }

    @Test
    fun `a sheet the user opened stays open across a configuration change`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }

        // The control: LkSheet's own entrance -- ModalBottomSheet's unconditional show()
        // LaunchedEffect -- actually ran and actually opened the sheet, so the assertion below is
        // about the restore losing the value, not about the sheet never having opened at all.
        compose.onNodeWithTag(TAG).assertIsDisplayed()
        val openedAt = sheetState.currentValue
        assertNotEquals(
            SheetValue.Hidden,
            openedAt,
            "control: the sheet's own entrance never finished opening it",
        )

        restoration.emulateSavedInstanceStateRestore()

        assertTrue(
            sheetState.currentValue == openedAt,
            "a configuration change reset the sheet's SheetState from $openedAt to " +
                "${sheetState.currentValue} instead of restoring it -- this is what replays the " +
                "sheet's ~600ms entrance and drops a half-dragged detent on every rotation.",
        )
    }

    /**
     * The control the verifier asked for: a genuinely fresh composition -- no restore at all -- with
     * `alreadyOpen = false` must still start at [SheetValue.Hidden]. This is what proves the
     * [rememberSaveable] + [SheetState.Saver] wiring did not quietly make every sheet start
     * pre-opened while chasing the restoration fix above.
     */
    @Test
    fun `a fresh sheet still starts Hidden when nothing says it is already open`() {
        compose.setContent {
            sheetState = rememberLkSheetState(startExpanded = false, alreadyOpen = false)
        }

        assertTrue(
            sheetState.currentValue == SheetValue.Hidden,
            "a sheet with alreadyOpen=false must still start Hidden on a fresh composition, not " +
                "${sheetState.currentValue}",
        )
    }

    private companion object {
        const val TAG = "lk-sheet-state-test-content"
    }
}

/**
 * The discriminating test this repo's own audit asked for after [LkSheetStateSurvivesRecreationTest]
 * shipped non-discriminating (2026-09-13): "assert
 * `sheetState.targetValue` right after restore, before `show()` runs, or capture the first frame."
 *
 * Neither literal suggestion turned out to work, and this class exists because I checked rather than
 * assumed: this harness's `createComposeRule()` runs on an `UnconfinedTestDispatcher` (its own
 * deprecation notice says so), which lets `ModalBottomSheet`'s `LaunchedEffect { sheetState.show() }`
 * run to completion synchronously the instant it launches. Measured directly — with
 * `rememberLkSheetState` reverted to the old plain `remember` to confirm the harness can still fail —
 * `currentValue` **and** `targetValue` both already read `Expanded` immediately after
 * `emulateSavedInstanceStateRestore()` returns, with zero frames pumped and `mainClock.autoAdvance`
 * held false. There is no window in which either property still shows the pre-`show()` value; the
 * entrance effect always wins the race before any assertion can run.
 *
 * The fix that does discriminate: read the state where nothing is racing to correct it. This host has
 * no [ModalBottomSheet] and no [androidx.compose.runtime.LaunchedEffect] of its own — [SheetState] is
 * driven directly from the test via the public suspend `expand()`, and [SheetState.currentValue] is
 * captured in the composable's own body (not inside an effect), which runs synchronously as part of
 * applying the restored composition, strictly before any effect for that composition could be
 * dispatched. With nothing left to race, the two implementations actually disagree:
 * - old `remember(startExpanded, alreadyOpen)`: a config change discards it outright, so the
 *   recomposition after restore constructs a *brand new* [SheetState] with `initialValue = Hidden`
 *   (`alreadyOpen` is `false` at every real call site but one) — measured `Hidden`.
 * - `rememberSaveable` + `SheetState.Saver(...)`: the [Saver] reconstructs the state at the detent it
 *   held before the configuration change — measured `Expanded`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkSheetStateRestoresDetentWithoutAnEntranceRaceTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var sheetState: SheetState

    // Captured synchronously in Host()'s own body -- see the class KDoc for why this, and not a
    // LaunchedEffect, is what makes the read land before ModalBottomSheet's entrance effect would.
    private var capturedAtComposition: SheetValue? = null

    @Composable
    private fun Host() {
        sheetState = rememberLkSheetState(startExpanded = false, alreadyOpen = false)
        capturedAtComposition = sheetState.currentValue
    }

    @Test
    fun `a sheet expanded before rotation is still Expanded the instant the restored composition applies`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host() }
        compose.waitForIdle()
        assertEquals(SheetValue.Hidden, capturedAtComposition, "sanity: a fresh sheet starts Hidden")

        // Driven directly, not through ModalBottomSheet's animated show() -- there is no
        // ModalBottomSheet in this Host at all, which is the whole point: nothing here launches an
        // effect that could race the restore and paper over a regression.
        runBlocking { sheetState.expand() }
        compose.waitForIdle()
        assertEquals(
            SheetValue.Expanded,
            capturedAtComposition,
            "sanity: expand() did not actually expand the sheet before the restore under test",
        )

        restoration.emulateSavedInstanceStateRestore()

        assertEquals(
            SheetValue.Expanded,
            capturedAtComposition,
            "the restored composition's first pass read $capturedAtComposition instead of Expanded " +
                "-- a configuration change reset the detent, which is exactly what replays the " +
                "sheet's ~600ms entrance and drops a half-dragged detent on every rotation",
        )
    }
}

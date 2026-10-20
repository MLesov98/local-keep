package com.localkeep.feature.unlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The deriving overlay must be stacked *over* the unlock screen's scroll region, not emitted after
 * it.
 *
 * `LkActionScaffold` hands its content slot a `Column`. The overlay was declared as a sibling of
 * the `BoxWithConstraints` that holds the scrolling body, and that body is `fillMaxSize` — so by
 * the time the Column measured the overlay there was no height left, and it was placed below the
 * bottom edge. `LkDerivingOverlay` is itself a `fillMaxSize` Box, so it drew nothing at all.
 *
 * What that cost, read frame by frame off `rec/flow01-unlock` at 20 fps (2026-09-15): submitting
 * cleared the field and dimmed the button for the ~1.3 s of the derivation with no indicator of any
 * kind — visually identical to a rejection — and the overlay's own "Opening your vault…" line was
 * first legible during the cross-fade *into* the vault, i.e. after the work it describes had
 * finished. The state was never at fault: `unlockWithPassword` sets `isWorking`, `unlockProgress`
 * and `unlockPhase` in the same synchronous `update` as the field clear.
 *
 * **Why this reads the source rather than the screen.** `UnlockScreen` takes a `UnlockViewModel`,
 * which is a Hilt view-model over a `VaultLockManager`, a `KeystoreManager`, a `BiometricUnlock`
 * and a `VaultImporter` — none of which exist off a device. What broke was not a decision the
 * screen makes, it is where one composable is emitted, and nesting is exactly what source can
 * state. The same trade `UnlockDispatcherTest` makes in `:core:vault`, and stated as plainly:
 * this proves the overlay is inside the box, not that it paints.
 *
 * Since the frame was extracted as `UnlockFrame` (2026-09-20, for 2A-F16) the two halves live in
 * different functions: the box and the `overlay()` call site are the frame's, the
 * `LkDerivingOverlay` under its `isWorking` guard is the screen's, and the slot's `BoxScope`
 * receiver is what joins them. All three are checked, because dropping any one of them restores
 * the original defect.
 */
class UnlockOverlayIsStackedTest {

    private val source = java.io.File("src/main/kotlin/com/localkeep/feature/unlock/UnlockScreen.kt")

    @Test
    fun `the deriving overlay is raised over the whole scaffold, not inside its content slot`() {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val lines = source.readLines()

        // The frame's own Box, which holds the scaffold *and* the overlay. The overlay used to be
        // raised inside the scaffold's content slot, which is only the region above the pinned action
        // bar: it covered that and nothing else (SCL-004 / S-L2). Measured by
        // `UnlockOverlayCoversTheWholeFrameTest`; this pins where the call sits.
        val box = lines.indexOfFirst { it.trim() == "Box(Modifier.fillMaxSize()) {" }
        assertTrue(box >= 0, "UnlockFrame no longer opens with Box(Modifier.fillMaxSize()) around the scaffold")
        val scaffold = lines.drop(box).indexOfFirst { it.trim() == "LkActionScaffold(" } + box
        assertTrue(scaffold > box, "UnlockFrame no longer places LkActionScaffold inside that Box")
        val call = lines.drop(box).indexOfFirst { it.trim() == "overlay()" } + box
        assertTrue(call > scaffold, "UnlockFrame no longer calls overlay() after the scaffold")
        val boxIndent = lines[box].takeWhile(Char::isWhitespace).length
        val scaffoldIndent = lines[scaffold].takeWhile(Char::isWhitespace).length
        val callIndent = lines[call].takeWhile(Char::isWhitespace).length
        assertEquals(
            scaffoldIndent,
            callIndent,
            "overlay() must be the scaffold's sibling inside the frame's Box (indent $scaffoldIndent), " +
                "not nested in its content slot (indent $callIndent) — there it is clipped to the scroll " +
                "region and the pinned buttons are drawn undimmed beneath it",
        )
        assertTrue(callIndent > boxIndent, "overlay() must be inside the frame's Box")

        // A Box receiver, so the slot cannot be moved out of one without changing this signature.
        assertTrue(
            lines.any { it.trim() == "overlay: @Composable BoxScope.() -> Unit," },
            "the overlay slot must keep its BoxScope receiver — that is what states it is raised " +
                "over the body rather than laid out beside it",
        )

        // And the screen still passes the real overlay into that slot, under its guard.
        val overlay = lines.indexOfFirst { it.contains("LkDerivingOverlay(") }
        assertTrue(overlay >= 0, "UnlockScreen no longer raises LkDerivingOverlay at all")
        val guard = lines.take(overlay).indexOfLast { it.contains("if (state.isWorking)") }
        assertTrue(
            guard >= 0,
            "LkDerivingOverlay is no longer raised under an `if (state.isWorking)` guard",
        )
    }
}

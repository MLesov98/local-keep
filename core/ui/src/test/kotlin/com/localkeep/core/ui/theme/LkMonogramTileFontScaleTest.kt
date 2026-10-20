package com.localkeep.core.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `LkMonogramTile` pinned its plate with `.size(size)` — both a floor and a ceiling — while its
 * glyph is styled at 15sp and grows with the system font slider like every other piece of type in
 * this app. Measured on device (API 35, 2026-09-02): a wide monogram ("MM") is 46.9dp across at
 * fontScale 2.0, and every monogram outgrows the 42dp plate on both axes from about 2.25. (The
 * 83×22px `Copy` overlap the fontscale walk reported was the row title, not this tile.) The fix
 * swaps `.size` for `defaultMinSize`, so the plate holds
 * its resting 42dp/36dp at fontScale 1.0 and only grows once the glyph inside it genuinely needs
 * the room — see the comment on the modifier chain in `LkContainers.kt` for why a flat
 * multiply-by-`fontScale` (the health score ring's approach) was rejected for this component
 * specifically: this tile shares a tight row with a title and a trailing action, that ring owns a
 * whole card.
 *
 * ### Why 2.5f and not 2.0f
 *
 * `FontScaleTest`'s own header names 2.0 as the value to test at — Android's accessibility ceiling.
 * This file uses 2.5f because at 2.0 the plate's HEIGHT cannot move: `LkText.rowTitle` is 15sp with
 * a 1.25 line height, and on device the glyph is 33.9dp tall at 2.0 and first clears 42dp at about
 * 2.25 (42.3dp). Swept here in 0.05 steps, Robolectric agrees — pinned at the floor through 2.2,
 * growing from 2.3 — so that is geometry, not measurement noise, and 2.5 sits clear of the
 * boundary. Text WIDTH does not grow under Robolectric at all for this glyph at any scale tried up
 * to 4.0 (a Robolectric limitation, not this component's) — which is why the assertion below reads
 * height, and why the width case a wide monogram crosses at 2.0 on a real device is covered
 * on-device in `FontScaleTest`.
 *
 * ### Why Robolectric rather than androidTest
 *
 * Same reasoning as [LkButtonTouchTargetFloorTest] and [LkDialogBoundsTest]: a measured height off
 * one layout pass is geometry, not a pixel, and needs no device — so it belongs where it runs on
 * every push (`./gradlew test`) rather than only when a phone or emulator is attached. `FontScaleTest`
 * (`core/ui/src/androidTest`) carries the same two cases as coverage for the on-device instrumented
 * pass; this is the one that actually gates a `testFossDebugUnitTest` run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkMonogramTileFontScaleTest {

    @get:Rule
    val compose = createComposeRule()

    /** Renders an [LkMonogramTile] at its default 42dp size and returns the tagged node's height in dp. */
    private fun tileHeight(): Float {
        compose.setContent {
            LocalKeepTheme {
                LkMonogramTile(title = "Bank of Ireland", modifier = Modifier.testTag(TAG))
            }
        }
        compose.waitForIdle()
        return compose.onNodeWithTag(TAG).fetchSemanticsNode().size.height / compose.density.density
    }

    @Test
    @Config(fontScale = 2.5f)
    fun growsPastItsPlateAtLargeFontScale() {
        val large = tileHeight()
        assertTrue(
            large > BASELINE_DP,
            "LkMonogramTile stayed at ${large}dp at fontScale 2.5. The plate must be a MINIMUM " +
                "(defaultMinSize), not a fixed .size(42.dp) — at a large accessibility font scale a " +
                "fixed plate lets its own glyph outgrow it and bleed into whatever the row drew next " +
                "to it.",
        )
    }

    /**
     * The control. `defaultMinSize` only raises the floor when content needs more than it — at
     * fontScale 1.0 the glyph already fits inside 42dp, so the plate must come back exactly the
     * size the comps specify, not a hair larger.
     */
    @Test
    @Config(fontScale = 1.0f)
    fun staysAtTheDesignSizeAtTheDefaultFontScale() {
        val default = tileHeight()
        assertTrue(
            default in (BASELINE_DP - 1f)..(BASELINE_DP + 1f),
            "LkMonogramTile was ${default}dp at fontScale 1.0, not the design's 42dp — this fix must " +
                "be inert at the default text size.",
        )
    }

    private companion object {
        const val TAG = "monogram-tile-font-scale-subject"

        /** [LkMonogramTile]'s own default `size` — the plate's resting diameter at fontScale 1.0. */
        const val BASELINE_DP = 42f
    }
}

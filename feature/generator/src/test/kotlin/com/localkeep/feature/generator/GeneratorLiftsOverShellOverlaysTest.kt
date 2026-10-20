package com.localkeep.feature.generator

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The generator's option list has to lift over the shell's floating overlays.
 *
 * The clipboard countdown is mounted once by the shell and floats above whatever screen is showing,
 * so it knows nothing about the content underneath — [com.localkeep.core.ui.LkShellOverlays] says so
 * and exists to answer it: the shell reports the height it actually measured and bottom-anchored
 * content lifts by exactly that. The vault list's FAB was the first casualty (2026-08-10, the FAB's
 * node left the accessibility tree entirely while the card was up) and the only call site.
 *
 * This screen was the second, and nobody had applied it here. Copying a password puts the countdown
 * card at the bottom of the shell, and this list ends near the bottom of the viewport, so the card
 * landed on the Lowercase toggle — hidden *and* untappable for the ~30 seconds the countdown runs.
 * Measured on a device 2026-09-06: `content-desc="Lowercase, enabled" bounds="[53,1897][1027,2034]"`
 * under `text="Generated password copied" bounds="[195,1906][575,1956]"`.
 *
 * A source-level guard because a JVM test cannot observe a Compose padding, let alone an overlay
 * drawn by a different composable in a different module. Same idiom as [SnapshotsRevealTest] in
 * `:feature:backup`, for the same reason.
 */
class GeneratorLiftsOverShellOverlaysTest {

    private val source: String =
        java.io.File("src/main/kotlin/com/localkeep/feature/generator/GeneratorScreen.kt").readText()

    @Test
    fun theScrollingOptionsListClearsTheShellOverlays() {
        assertTrue(
            source.contains("shellOverlayInset()"),
            "the generator's scrolling column must lift over the shell's floating overlays, or the " +
                "clipboard countdown covers the character-class toggles for the length of the countdown",
        )
    }

    @Test
    fun theListScrollsByTheOverlayHeightRatherThanOnlyPaddingForIt() {
        // Padding alone is not the fix here, and shipping it as one was the first attempt: a
        // bottom-anchored control is *positioned* by its padding, so the vault list's FAB moved.
        // A scrolling column is not — extra bottom padding lengthens the scroll range and leaves
        // every already-laid-out row exactly where it was. Measured on the device with the padding
        // in place: `content-desc="Lowercase, enabled"` still at `bounds="[53,1897][1027,2034]"`,
        // under the card. The padding buys the room; this spends it.
        val compensates = Regex(
            """LaunchedEffect\(overlayInsetPx\)\s*\{.*?animateScrollBy""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            compensates.containsMatchIn(source),
            "the options list must scroll by the overlay's height when it appears, or the toggle " +
                "the card lands on stays covered however much bottom padding the column has",
        )
    }

    @Test
    fun theLiftIsOnlyEverDownwardCompensating() {
        // When the countdown leaves, the list stays where the user last put it. Scrolling back on
        // dismissal would move the page under a reader who had since scrolled it themselves. The
        // lift is a non-negative amount and the only scroll call is guarded on it being positive.
        assertTrue(
            source.contains("if (lift > 0) optionsScroll.animateScrollBy(lift.toFloat())"),
            "the lift must not reverse itself when the overlay leaves",
        )
    }

    @Test
    fun theLiftIsAnimatedSoItArrivesWithTheCardItAvoids() {
        // The overlays animate themselves in and out. A control that lifted on its own schedule
        // would arrive at a different time than the card it is avoiding — the reason the vault
        // list's FAB animates this same value at VaultListScreen's call site.
        val animated = Regex(
            """animateDpAsState\(\s*shellOverlayInset\(\),""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            animated.containsMatchIn(source),
            "the lift must be animated, or the list snaps while the card it is avoiding slides",
        )
    }
}

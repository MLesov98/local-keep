package com.localkeep.feature.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The drawer's "Restore a snapshot" must land on the snapshots, not on their heading.
 *
 * Three separate defects have lived in this one arrival, and each is pinned below.
 *
 *  1. The reveal was attached to `LkSectionLabel`. `bringIntoView` scrolls the minimum distance
 *     that makes the requesting node visible, so that put the words ROLLING LOCAL SNAPSHOTS on the
 *     last 35px of the screen with the list and every Restore button still below the fold —
 *     landing the user on the export and import forms, which is the depth problem the row was
 *     added to remove. Measured on a 1080x1920 device 2026-07-30, in en-US and en-XA alike.
 *  2. The header scrolled away with the content (2026-09-06).
 *  3. The reveal fired against a screen that had not finished measuring, and against a request it
 *     had already spent (2026-09-11) — the two the behavioural cases below cover.
 *
 * The source-level guards are here because a scroll offset is not observable from a unit test; the
 * arithmetic that decides *where* the scroll goes now is, and it is tested as arithmetic. Same
 * idiom as `PinnedConsentStaysCompactTest` in feature/onboarding, for the same reason.
 */
class SnapshotsRevealTest {

    private val source: String =
        java.io.File("src/main/kotlin/com/localkeep/feature/backup/BackupSettingsScreen.kt").readText()

    // --- where the scroll goes -------------------------------------------------------------

    /**
     * The case the screen actually shipped broken.
     *
     * The section is the last one, so its offset into the content never moves — everything that
     * arrives late arrives below it. That makes an unclamped target a *constant*, which
     * `snapshotFlow` emits exactly once, before the sections above have finished measuring. The
     * scroller coerced it to the extent of that first frame and no second emission ever came, so
     * the screen landed 852px down whether the snapshot list was empty or held four entries — two
     * layouts 560px apart, measured on device 2026-09-11.
     *
     * Clamping inside the computation is what makes the value move with the extent.
     */
    @Test
    fun `the target grows as the screen finishes measuring`() {
        // The section's own offset into the content, unchanged between the two: 2175px, measured
        // from the landing dump. Only the extent moves, 866 -> 1426, as the four snapshot rows and
        // the sections above them finish measuring.
        val firstFrame = snapshotsRevealTarget(sectionTopInViewport = 2175f, scrollValue = 0, maxValue = 866)
        val settled = snapshotsRevealTarget(sectionTopInViewport = 2175f, scrollValue = 0, maxValue = 1426)

        assertEquals(866, firstFrame)
        assertEquals(1426, settled)
        assertTrue(settled > firstFrame, "a later, taller layout must produce a later target")
    }

    /** Never past the end: the caller hands this straight to `scrollTo`. */
    @Test
    fun `the target never exceeds the scrollable extent`() {
        assertEquals(866, snapshotsRevealTarget(sectionTopInViewport = 9_000f, scrollValue = 0, maxValue = 866))
    }

    /**
     * A scroll of the user's own must emit nothing, or the 700ms settling window would spend that
     * time dragging the screen back out from under them. `localPositionOf` moves by exactly what
     * `scrollValue` moves by, in the opposite direction, so the sum is invariant — which is what
     * makes `snapshotFlow`'s distinct-until-changed the whole guard against that.
     */
    @Test
    fun `the target does not move when only the scroll position does`() {
        val atRest = snapshotsRevealTarget(sectionTopInViewport = 1309f, scrollValue = 0, maxValue = 2400)
        val scrolledDown = snapshotsRevealTarget(sectionTopInViewport = 909f, scrollValue = 400, maxValue = 2400)

        assertEquals(atRest, scrolledDown)
    }

    /** A section already above the viewport top still resolves to a real offset, not a negative. */
    @Test
    fun `a section scrolled past resolves to its own content offset`() {
        assertEquals(1309, snapshotsRevealTarget(sectionTopInViewport = -91f, scrollValue = 1400, maxValue = 2400))
    }

    // --- how the request is held ------------------------------------------------------------

    /**
     * [BackupFocus.take] is destructive, so whichever pass calls it owns the request. Called from
     * inside the effect that acts on it, a restart spends it on a pass that is then cancelled:
     * measured 2026-09-11, the effect took `snapshots`, began the scroll, restarted 26ms later and
     * the second run found nothing pending — the screen stayed at offset 0 and nothing said why.
     */
    @Test
    fun theFocusRequestIsTakenOnceIntoRememberRatherThanFromTheEffect() {
        assertTrue(
            // One take for both sections the screen can be asked for (snapshots, and since
            // 2026-10-05 the import section the import wizard hands off to) — still once, still
            // into a remember.
            source.contains("val focusSection = remember { BackupFocus.take() }"),
            "the focus request must be consumed once into a remember, not read from the effect " +
                "that scrolls — an effect restart would spend it on a cancelled pass",
        )
        val takenInsideEffect = Regex(
            """LaunchedEffect\((?:(?!\bremember\b).)*?BackupFocus\.take\(\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertFalse(
            takenInsideEffect.containsMatchIn(source),
            "BackupFocus.take() is being called from inside a LaunchedEffect again",
        )
    }

    // --- what the scroll is measured against -------------------------------------------------

    @Test
    fun theRevealIsNotAttachedToTheSectionLabelAlone() {
        val labelCarriesIt = Regex(
            """LkSectionLabel\([^)]*onGloballyPositioned \{ snapshotsCoords""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertFalse(
            labelCarriesIt.containsMatchIn(source),
            "the reveal measures the heading again — it will scroll the label into view and leave " +
                "the snapshot list below the fold",
        )
    }

    /**
     * The wrapper used to repeat the scroller's `spacedBy(16.dp)`, and this test required it to.
     *
     * It held exactly two children — the heading and the block the heading names — so that
     * arrangement was only ever the label-to-content gap, and `LkSectionLabel` owns that gap now
     * (`LkSectionLabelGap`, measured by `LkSectionLabelGapTest` in `:core:ui`). Repeating the 16dp
     * therefore stopped supplying the gap and started adding to it: 24dp inside a section against
     * 16dp between sections, an inverted hierarchy. Nothing "gets it for free" and vanishes — the
     * wrapper's own distance from its siblings still comes from the scroller's arrangement.
     */
    @Test
    fun theRevealIsMeasuredOnAContainerAndThatContainerAddsNoGapOfItsOwn() {
        val wrapper = Regex(
            """Column\(modifier = Modifier\.onGloballyPositioned \{ snapshotsCoords = it \}\) \{\s*""" +
                """LkSectionLabel\(""",
        )
        assertTrue(
            wrapper.containsMatchIn(source),
            "the snapshots section must be wrapped in a Column that reports its coordinates and " +
                "carries no verticalArrangement of its own — LkSectionLabel supplies the only gap " +
                "that Column has, and an arrangement here is added to it rather than being it",
        )
    }

    /**
     * The viewport, not the content. `onGloballyPositioned` reports the coordinates at its own
     * point in the chain, so placed after `verticalScroll` it would report a frame that moves with
     * the scroll — and the offset computed against it would be the section's distance from itself.
     */
    @Test
    fun theScrollerIsMeasuredOutsideItsOwnScrollModifier() {
        // Anything but another `verticalScroll` may sit between the two — the viewport's own top
        // gutter does, see [theScrollerTakesTheSpacingTheHeaderUsedToGiveIt] — but the order is the
        // claim and the lookahead is what keeps it one.
        val viewportThenScroll = Regex(
            """\.onGloballyPositioned \{ scrollerCoords = it \}""" +
                """(?:(?!\.verticalScroll\().)*?\.verticalScroll\(scrollState\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            viewportThenScroll.containsMatchIn(source),
            "the scroller's coordinates must be taken before verticalScroll, or they move with the " +
                "content they are meant to be the frame for",
        )
    }

    @Test
    fun theHeaderIsPinnedOutsideTheScrollerItScrollsPast() {
        // The other half of the same journey, found 2026-09-06. Landing in the right place did not
        // stop the scroll taking the screen's title with it. The header was the scrolling Column's
        // first child, and reaching the snapshots means scrolling ~1630px, so the drawer row named
        // "Restore a snapshot" arrived at an unlabelled wall of controls with nothing on screen
        // saying where the user was: the dump for that landing contains no "Backup" text node at
        // all, only `text="Use a separate export passphrase" bounds="[236,132][933,149]"`.
        //
        // AppShell calls this row and "Duress / decoy" -> Settings "the same trick". Settings keeps
        // its title fixed while it filters; this is what makes the claim true of both.
        val headerInsideScroller = Regex(
            """verticalScroll\(scrollState\)(?:(?!LkScreenHeader|^\}).)*?LkScreenHeader\(""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        assertFalse(
            headerInsideScroller.containsMatchIn(source),
            "the screen header is inside the scrolling column again — the snapshots reveal will " +
                "scroll the title off-screen and land the user nowhere",
        )
    }

    @Test
    fun theScrollerTakesTheSpacingTheHeaderUsedToGiveIt() {
        // The header was the arrangement's first child, so the content below got its 16dp for free.
        // Padding rather than a Spacer: a Spacer inside a `spacedBy` Column is itself spaced, which
        // is a repo-level rule and would render 32dp here.
        //
        // And the gap has to be the viewport's, not the content's first 16dp. Applied after
        // `verticalScroll` it scrolled away with everything else, so the moment the screen moved
        // the first section label ran flush into the pinned header — `IMPORT A .VAULT FILE` drawn
        // touching `Encrypted copies, local snapshots, and cloud backup`, no gap at all, measured
        // on device 2026-09-15. Before `verticalScroll` it is a
        // permanent gutter instead. The bottom gutter stays inside the scroll, where it belongs:
        // that one is content and is meant to move.
        val gutterOutsideTheScroll = Regex(
            """\.padding\(top = LkSpace\.md\)(?:(?!\.verticalScroll\().)*?\.verticalScroll\(scrollState\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            gutterOutsideTheScroll.containsMatchIn(source),
            "the header's gap must be applied before verticalScroll, or it scrolls away and the " +
                "first section label collides with the pinned header",
        )
        assertTrue(
            source.contains(".padding(bottom = LkFrame.gutterPlain)"),
            "pinning the header removed the gutter the content used to get from the arrangement",
        )
    }
}

package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Three small verdict-and-placement fixes from the 2026-09-20 release sweep.
 *
 * - **4A-F4** — the custom field's Text / Hidden chips are bare words sitting between a name well
 *   and a value well, with nothing saying what is being chosen. `LkChip`'s `toggleable` already
 *   announces the on/off state; nothing named the question.
 * - **4C-14 (the colour half)** — a checksum-invalid recovery phrase was refused in **amber**,
 *   which `LkColors` reserves for "something is worth looking at but nothing is wrong". Save is
 *   disabled outright on that verdict, so the card said the opposite of what the screen did. Its
 *   two siblings, a non-BIP-39 word and a phrase that is this vault's own Recovery Kit, already
 *   refuse in coral. (Whether such a phrase should be *saveable* at all is a product decision and
 *   is left alone — see the report.)
 * - **4C-18** — on the Location detail the "Taken … · precise" line was emitted after its card
 *   closed and drawn on the page background, orphaned above FOLDER, while both editor states draw
 *   it inside the card.
 */
class PolishFixesFromTheReleaseSweepTest {

    private val screen =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
    private val detail =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt").readText()

    @Test
    fun `the custom-field type chips name the choice they are making`() {
        val chips = screen.substringAfter("offered.distinct().forEach { type ->", "")
            .substringBefore("\n                }")
        assertTrue(chips.isNotEmpty(), "the type chips moved; this test no longer reads them")
        assertTrue(
            "R.string.itemedit_custom_field_type_a11y" in chips,
            "the chips are still two bare words with nothing saying what they choose",
        )
    }

    @Test
    fun `a checksum refusal is coral, like every other phrase refusal`() {
        val banner = screen.substringAfter("SeedPhraseStatus.CHECKSUM -> Banner(", "")
            .substringBefore(")")
        assertTrue(banner.isNotEmpty(), "the phrase banner moved; this test no longer reads it")
        assertTrue("c.coralText" in banner, "a hard block is painted amber, which means nothing is wrong")
        assertTrue("c.amber" !in banner)
    }

    /**
     * And the grid under it agrees. CHECKSUM names no slot — "one of them is wrong, or two are in
     * the wrong order" — so the whole grid is what the refusal is about, and a coral banner over a
     * neutral grid would split one verdict into two colours.
     */
    @Test
    fun `the grid agrees with the banner`() {
        assertTrue(
            "SeedPhraseStatus.BADWORD, SeedPhraseStatus.VAULTKIT, SeedPhraseStatus.CHECKSUM ->" in screen,
            "the phrase grid still reads a checksum failure as neutral",
        )
    }

    @Test
    fun `the location's capture caption is inside its card`() {
        val coordinate = detail.substringAfter("private fun CoordinateDetail(", "")
            .substringBefore("\n@Composable")
        val group = coordinate.substringAfter("LkGroup {", "").substringBefore("\n    }")
        assertTrue(group.isNotEmpty(), "CoordinateDetail moved; this test no longer reads it")
        assertTrue(
            "coordinateCapturedCaption(fix)" in group,
            "the capture caption is drawn on the page background again, outside the card it describes",
        )
    }

    /**
     * Inside the card is not inside the *gate*: the gate is `SecretReadField`'s, per field. A user
     * must not have to authenticate to find out how much to trust a value they cannot see.
     */
    @Test
    fun `and still outside the reveal gate`() {
        val coordinate = detail.substringAfter("private fun CoordinateDetail(", "")
            .substringBefore("\n@Composable")
        val caption = coordinate.substringAfter("coordinateCapturedCaption(fix)", "")
        assertTrue(
            "SecretReadField" !in caption,
            "the caption moved inside the gated field rather than beside it",
        )
    }
}

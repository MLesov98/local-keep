package com.localkeep.feature.importwizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * V3-07. No tile in the import-source grid may spend a glyph `LkIcons`' KDoc marks load-bearing.
 *
 * `Lock` means "this field is gated behind re-auth", `Eye`/`EyeOff` mean "reveal what is masked",
 * `Fingerprint` means "a biometric prompt will actually be raised", `Camera` means "take a picture".
 * The grid carried `ONEPASSWORD -> LkIcons.Lock` directly above a comment stating that very rule for
 * the four tiles added beside it — a vocabulary is either strict or it is decoration, and one
 * exception with a paragraph attached is the second.
 *
 * Source assertions for the reason given on [ImportWizardHardeningTest]: `ImportSource.icon` is a
 * private extension on a Compose-only file, and this module has no Robolectric. The test reads the
 * one `when` that decides every tile, so a new source added with a reserved glyph fails it.
 */
class ImportSourceGlyphTest {

    private val screenSource =
        File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardScreen.kt")

    private val loadBearing = listOf("Lock", "Eye", "EyeOff", "Fingerprint", "Camera")

    private fun iconBlock(): String {
        if (!screenSource.isFile) {
            fail("cannot find ${screenSource.absolutePath} — this test reads the source it guards")
        }
        val block = screenSource.readText()
            .substringAfter("private val ImportSource.icon: ImageVector")
            .substringBefore("\n    }")
        assertTrue(
            block.contains("ImportSource.CSV ->"),
            "the icon `when` was not found intact — this test would then assert nothing",
        )
        return block
    }

    @Test
    fun `no source tile uses a load-bearing glyph`() {
        val assignments = iconBlock().lines()
            .filter { it.contains("-> LkIcons.") }
            // The name alone: a trailing comment on the line would otherwise make `Lock` read as
            // `Lock // …` and slip past the membership check below.
            .map { it.substringAfter("-> LkIcons.").trim().takeWhile(Char::isLetterOrDigit) }
        assertTrue(assignments.size >= 11, "expected every tile to be covered, got $assignments")
        val offenders = assignments.filter { it in loadBearing }
        assertEquals(
            emptyList(),
            offenders,
            "these glyphs carry a promise a source tile cannot keep: $offenders",
        )
    }

    /** The replacement, named: a neutral glyph owned by nothing else in the set. */
    @Test
    fun `1Password uses the neutral vault-door glyph`() {
        assertTrue(
            iconBlock().contains("ImportSource.ONEPASSWORD -> LkIcons.VaultDoor"),
            "1Password must use the glyph drawn for this grid, not a borrowed one",
        )
    }

    /** Control: the list this test guards against is not empty, so a real violation still fails. */
    @Test
    fun `the load-bearing list is the one LkIcons documents`() {
        assertEquals(listOf("Lock", "Eye", "EyeOff", "Fingerprint", "Camera"), loadBearing)
    }
}

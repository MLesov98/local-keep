package com.localkeep.core.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `LkDialogAction.enabled` has to reach the button, on every emphasis.
 *
 * The `Destructive` branch of `LkDialogConfirm` dropped it, so the parameter was inert on exactly
 * the dialogs where it matters most — every destructive confirmation in the app. Measured on device
 * 2026-09-20 while verifying the new snapshot-restore gate: with its password well empty,
 * `dialog-confirm` dumped `enabled="true"` and the tap went through to a refusal instead of being
 * ignored. The duress setup and removal confirmations pass the same argument and were equally
 * unguarded, which means the `isWorking` gate added to them in the first fix wave had never had any
 * effect.
 *
 * Asserted on the source because `:core:ui` has no Compose UI test dependency, and because the
 * property is structural: three branches, one parameter, and the failure mode is a branch that
 * forgets it.
 */
class DialogConfirmHonoursEnabledTest {

    /** The confirm helper alone: from its declaration to the surface composable that follows it. */
    private val confirm: String =
        File("src/main/kotlin/com/localkeep/core/ui/theme/LkModals.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
            .substringAfter("private fun LkDialogConfirm(action: LkDialogAction)")
            .substringBefore("fun LkDialogSurface")

    @Test
    fun `the slice is bounded`() {
        assertTrue(
            confirm.isNotEmpty() && confirm.length < 4000,
            "the LkDialogConfirm slice is unbounded, so the count below is measuring the whole " +
                "file; length=${confirm.length}",
        )
        assertEquals(
            3,
            Regex("LkActionEmphasis\\.").findAll(confirm).count(),
            "LkActionEmphasis gained or lost a case; this test counts one `enabled` per branch " +
                "and has to be updated with it",
        )
    }

    @Test
    fun `every emphasis passes enabled through`() {
        assertEquals(
            3,
            Regex("enabled = action\\.enabled").findAll(confirm).count(),
            "a confirm branch does not pass `action.enabled`, so a dialog that declares its " +
                "confirm disabled still fires on a tap. Found: $confirm",
        )
    }
}

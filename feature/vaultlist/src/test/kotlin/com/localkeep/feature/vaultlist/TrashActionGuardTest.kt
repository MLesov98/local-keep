package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test for 8-2 (`verdicts/8-2.json`, CONFIRMED medium).
 *
 * "Empty trash" was the one action control on this screen with no `enabled` argument, so it stayed
 * tappable while a per-row Restore or Delete-forever was still in flight. `confirmPurge` clears
 * `pendingPurge` unconditionally and then calls `mutateReporting`, whose re-entrancy guard
 * (`if (_state.value.isWorking) return`) makes the purge a silent no-op; the confirmation dialog's
 * `onConfirm` closes the dialog either way. The user answers a dialog that promised to permanently
 * delete everything in the trash, the dialog closes, and nothing happens — no announcement, no
 * error, and `pendingPurge` already discarded.
 *
 * `LkTintButton`'s own KDoc on `enabled` names this exact failure class as the reason the parameter
 * exists (`BackupSettingsScreen`'s Replace button dropping a confirmed tap at an in-flight
 * `isWorking` guard with nothing written to the screen).
 *
 * Asserted on the source, for the same reason `EmptyPanelGuardTest` is: the defect is an argument
 * missing from a composable call, and driving the screen would need the whole vault-list ambient
 * plus a mutation held open long enough to race.
 */
class TrashActionGuardTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/TrashScreen.kt")

    /**
     * Comments explain why the guard is there and name the failure it prevents; they must not
     * satisfy the checks, or the explanation would be what keeps the test green. Same idiom as
     * `EmptyPanelGuardTest.code`.
     */
    private fun code(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    /** The Empty-trash button's own argument list, from its call through to its closing paren. */
    private fun emptyTrashCall(): String =
        code().substringAfter("LkTintButton(", missingDelimiterValue = "")
            .substringBefore("\n                )")
            .ifEmpty { fail("the Empty-trash LkTintButton is no longer where this test looks") }

    /**
     * Production line under test: `enabled = !state.isWorking` on the Empty-trash `LkTintButton`.
     * Delete that one line and this test goes red.
     */
    @Test
    fun `empty trash is disabled while another trash mutation is in flight`() {
        val call = emptyTrashCall()
        assertTrue(
            "lkTag(\"trash-empty\")" in call,
            "the Empty-trash button must still be the one tagged trash-empty; found: $call",
        )
        assertTrue(
            "enabled = !state.isWorking" in call,
            "Empty trash must carry enabled = !state.isWorking: without it the confirmation " +
                "dialog can be answered while a row mutation is in flight, and confirmPurge then " +
                "clears pendingPurge and returns at mutateReporting's re-entrancy guard, leaving " +
                "the user believing a purge they confirmed has happened; found: $call",
        )
    }

    /**
     * Control assertion: the guard this fix copies was already on every other action control on
     * this screen, and held before the fix. If a later edit strips them, this test says so rather
     * than passing on the strength of the one line it just added.
     */
    @Test
    fun `every trash action control is guarded the same way`() {
        val code = code()
        val guards = Regex("enabled = !state\\.isWorking").findAll(code).count()
        assertTrue(
            // 4, not 5, since 2026-10-12: the item row's Restore and Delete forever are one
            // `TrashRowActions` call now, so the pair carries its single guard once. Nothing was
            // unguarded — the two buttons read the one `enabled` — and the folder row's pair is
            // guarded by the `enabled` its caller hands down.
            guards >= 4,
            "expected the row actions, the folder row, the preview dialog and Empty trash to " +
                "share one guard; found $guards call sites carrying it",
        )
    }
}

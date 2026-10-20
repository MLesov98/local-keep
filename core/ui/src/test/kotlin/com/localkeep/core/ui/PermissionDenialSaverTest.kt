package com.localkeep.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression test: a permission denial must survive a configuration change.
 *
 * Both camera-permission callbacks handled only the granted branch, so a denial produced a screen
 * that did nothing and said nothing. The notice that now covers them is held in `rememberSaveable`,
 * because a notice lost to a rotation puts the user back in front of a button that appears broken —
 * which is the state the branch exists to end.
 *
 * `rememberSaveable` cannot store a data class on its own, so [PermissionDenialSaver] is what makes
 * that work, and a saver that silently fails to round-trip would reintroduce the bug in a way no
 * compiler catches. This is a pure JVM test: it exercises the saver's own `save`/`restore` lambdas
 * with no Android runtime involved.
 *
 * `PermissionDenial.after` itself is not covered here — it needs an `Activity` for
 * `shouldShowRequestPermissionRationale`, so the two-message decision belongs to an instrumented
 * test, recorded as owed rather than pretended at.
 */
class PermissionDenialSaverTest {

    /** Drives the saver the way `rememberSaveable` does: save to a value, restore from that value. */
    private fun roundTrip(denial: PermissionDenial?): PermissionDenial? {
        var stored: Any? = null
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        with(PermissionDenialSaver) { stored = scope.save(denial) }
        @Suppress("UNCHECKED_CAST")
        return PermissionDenialSaver.restore(stored as Any)
    }

    @Test
    fun `a denial that can be retried round-trips`() {
        val denial = PermissionDenial(
            message = "LocalKeep can't scan a QR code without camera access. You can tap it again to allow access.",
            settingsActionLabel = null,
        )
        val restored = roundTrip(denial)
        assertEquals(denial.message, restored?.message)
        assertNull(
            restored?.settingsActionLabel,
            "a retryable denial must not come back offering a settings link — that is the other " +
                "state, and it is the one the user cannot act on from inside the app",
        )
    }

    @Test
    fun `a permanently denied permission round-trips with its settings action`() {
        val denial = PermissionDenial(
            message = "LocalKeep can't take a photo without camera access. Android won't ask again, " +
                "so it has to be granted in Settings.",
            settingsActionLabel = "Open settings",
        )
        val restored = roundTrip(denial)
        assertEquals(denial.message, restored?.message)
        assertEquals(
            "Open settings",
            restored?.settingsActionLabel,
            "losing the settings label on rotation would leave the user with a message naming a " +
                "remedy and no way to reach it",
        )
    }

    @Test
    fun `no denial round-trips as no denial`() {
        // The list must still have two slots when the state is null, or restore reads past its end.
        assertNull(roundTrip(null))
    }
}

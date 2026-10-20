package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opening a trashed entry's file must raise the same challenge the live route raises.
 *
 * A **File** item keeps its bytes in its own payload, and `FileItemViewerScreen` will not mount a
 * decrypting renderer over them without a passed [com.localkeep.core.ui.RevealAuth] challenge.
 * Trash's read-only preview mounted the same `SecureMediaViewer` straight off an untokened
 * `sessionKeyOrNull()`, so the trashed copy of a scanned passport opened with no challenge anywhere
 * on the route. The precondition costs an attacker holding the unlocked phone nothing to create:
 * moving an item to the trash from its row raises no challenge either, and Trash offers Restore — so
 * trash, look, restore was a general way around the file reveal gate, not a leak confined to items
 * their owner had already deleted. Found 2026-09-07 (upgraded to high on
 * verification).
 *
 * The rule being enforced is `FileItemEditScreen`'s own: *a gate one route enforces and another does
 * not is a gate nobody can rely on, so the cheaper route is raised to the stricter one rather than
 * the other way round.*
 *
 * Asserted on the source rather than by driving the composable. `TrashScreen` takes a `TrashViewModel`
 * built from `VaultRepository`, `VaultLockManager` and `FileBlobStore`, none of which this module can
 * stand up on the JVM, and the property at stake is structural anyway: that **no route from a tap to
 * the viewer skips the challenge**. `feature/backup`'s `ExportPassphraseGateTest` reads its own
 * screen for the same reason.
 */
class TrashPreviewRevealGateTest {

    private val screen =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/TrashScreen.kt").readText()
    private val viewModel =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/TrashViewModel.kt").readText()

    /** Control: the two files are the ones this test thinks it is reading. */
    @Test
    fun readsTheTrashSurface() {
        assertTrue("SecureMediaViewer(" in screen, "TrashScreen no longer mounts the viewer at all")
        assertTrue("class TrashViewModel" in viewModel, "TrashViewModel.kt did not load")
    }

    /**
     * The untokened accessor is the bypass itself: anything holding a `TrashViewModel` could take a
     * live key with no challenge in front of it. Its absence is what forces every caller through
     * [TrashViewModel.completeReveal].
     */
    @Test
    fun theViewModelExposesNoUngatedSessionKey() {
        assertFalse(
            "fun sessionKeyOrNull(" in viewModel,
            "TrashViewModel still exposes an untokened session key — the preview can decrypt a " +
                "trashed file with no reveal challenge, while the live route always challenges",
        )
        assertFalse(
            "lockManager.sessionKeyOrNull()" in viewModel,
            "an untokened read remains: the epoch must be carried so a relock-and-unlock cannot " +
                "inherit a challenge passed in an earlier session",
        )
    }

    /** The key the viewer decrypts with is the one a passed challenge released, and no other. */
    @Test
    fun theViewerMountsOnlyOnTheRevealedKey() {
        val mount = screen.substringAfter("if (previewItem != null && viewingAttachment != null)")
            .substringBefore("LkScreen(")
        assertTrue(
            "viewModel.previewSessionKeyOrNull()" in mount,
            "the viewer's key must come from the gated accessor; found: $mount",
        )
        assertFalse(
            "viewModel.sessionKeyOrNull()" in mount,
            "the viewer still reads a plain session key; found: $mount",
        )
    }

    /** A tap asks for a challenge. It does not open anything. */
    @Test
    fun tappingAnAttachmentRequestsAChallengeRatherThanOpeningIt() {
        assertTrue(
            "onOpenAttachment = viewModel::requestPreviewReveal" in screen,
            "the preview dialog's file row must route through the challenge",
        )
        assertFalse(
            Regex("""viewingAttachmentId\s*=[^=]""").containsMatchIn(screen),
            "TrashScreen writes the viewer's target itself; only a passed challenge may open it",
        )
    }

    /** The challenge is actually raised, and it is the reveal gate rather than something weaker. */
    @Test
    fun theScreenRaisesTheRevealPrompt() {
        assertTrue("RevealAuth.authenticate(" in screen, "no challenge is ever raised")
        assertTrue(
            "viewModel.beginReveal()" in screen && "viewModel.completeReveal(token, outcome)" in screen,
            "the session token must be taken before the prompt and carried back into the grant",
        )
    }

    /** Success alone is not enough: the session it was granted in has to still be the live one. */
    @Test
    fun onlyASuccessAgainstALiveSessionReleasesTheKey() {
        val grant = viewModel.substringAfter("fun completeReveal(").substringBefore("fun closePreviewViewer(")
        assertTrue(
            "RevealAuth.RevealOutcome.Success" in grant &&
                "lockManager.sessionKeyOrNull(token) != null" in grant,
            "the grant must check both the outcome and that the challenged session is still live; " +
                "found: $grant",
        )
        assertTrue(
            "previewRevealToken = token" in grant,
            "the epoch the user was challenged in has to be recorded; found: $grant",
        )
    }

    /**
     * A device with no screen lock and no enrolled biometric cannot be challenged, so the gate
     * refuses — and must say so. Refusing in silence leaves a tappable row that does nothing, which
     * is the dead tap `AttachmentPickerScreen`'s sibling gate was already fixed for.
     */
    @Test
    fun aGateThatCannotChallengeFailsClosedOutLoud() {
        assertTrue(
            "!RevealAuth.isAvailable(context) -> RevealAuth.RevealOutcome.Unavailable" in screen,
            "a lock-less device must resolve to Unavailable rather than falling through",
        )
        val dialog = screen.substringAfter("private fun TrashPreviewDialog(")
        assertTrue(
            "gateError?.let" in dialog && "LkInlineError(" in dialog,
            "the refusal has to be visible in the surface the tap came from; found: $dialog",
        )
    }

    /**
     * Closing the viewer drops the reveal with it. A challenge that outlives the viewer would make
     * the second file free, which is most of the gate given the viewer pages across an item's whole
     * file set.
     */
    @Test
    fun closingTheViewerDropsTheReveal() {
        val close = viewModel.substringAfter("fun closePreviewViewer(").substringBefore("fun clearPreviewGateError(")
        assertTrue(
            "previewRevealToken = null" in close,
            "the reveal must not survive the viewer; found: $close",
        )
    }
}

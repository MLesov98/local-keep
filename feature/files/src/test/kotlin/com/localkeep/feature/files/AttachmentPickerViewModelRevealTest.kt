package com.localkeep.feature.files

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.Card
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.RevealAuth
import com.localkeep.core.vault.VaultVisibilitySnapshot
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultStorageInventory
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression test for 8-2: the in-flight biometric reveal challenge for a reprompt-gated
 * attachment moved from Composable `remember` (lost on process death and, more importantly, not
 * inspectable as state) into `AttachmentPickerViewModel` — `pendingViewerReveal`/`pendingViewId`,
 * driven by `requestViewReveal`/`onViewerRevealAuthenticated`/`onViewerRevealDenied`/
 * `clearPendingViewId`. The security-relevant property is fail-closed: requesting a reveal must
 * never itself open anything, only `onViewerRevealAuthenticated` may, and a denial must leave
 * nothing pending.
 *
 * `VaultRepository`/`VaultLockManager`/`Entitlement` are mocked (see
 * SettingsViewModelBiometricLoadTest for why `VaultLockManager`'s real construction chain is not
 * usable under Robolectric) — none of these four methods touch them, so the mocks are never
 * stubbed beyond what the constructor needs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentPickerViewModelRevealTest {

    private fun buildViewModel(repository: VaultRepository = mock<VaultRepository>()): AttachmentPickerViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        return AttachmentPickerViewModel(
            repository = repository,
            lockManager = mock<VaultLockManager>(),
            fileBlobStore = mock<FileBlobStore>(),
            photoPickerImport = mock<PhotoPickerImport>(),
            entitlement = entitlement,
            context = context,
        )
    }

    @Test
    fun `requesting a reveal does not open the attachment, only parks the challenge`() {
        val viewModel = buildViewModel()

        viewModel.requestViewReveal("attachment-1")

        val state = viewModel.state.value
        assertTrue(state.pendingViewerReveal, "the challenge must be recorded as pending")
        assertEquals("attachment-1", state.pendingViewId)
        // Fail-closed: nothing in this ViewModel's state represents "open" independent of
        // pendingViewerReveal being cleared by an explicit authenticate/deny call below — a
        // request alone never flips it.
    }

    @Test
    fun `only authentication clears the pending flag while keeping the target id resolvable`() {
        val viewModel = buildViewModel()
        viewModel.requestViewReveal("attachment-1")

        viewModel.onViewerRevealAuthenticated()

        val state = viewModel.state.value
        assertFalse(state.pendingViewerReveal, "authenticated reveal must clear the pending flag")
        // pendingViewId is left for the caller to resolve which attachment just cleared, then the
        // caller is expected to call clearPendingViewId() itself (see below) — authenticating does
        // not silently forget which attachment was being revealed.
        assertEquals("attachment-1", state.pendingViewId)
    }

    @Test
    fun `denial clears both the pending flag and the target id, leaving nothing revealed`() {
        val viewModel = buildViewModel()
        viewModel.requestViewReveal("attachment-1")

        viewModel.onViewerRevealDenied()

        val state = viewModel.state.value
        assertFalse(state.pendingViewerReveal)
        assertNull(state.pendingViewId, "a denied challenge must not leave a target attachment id behind")
    }

    @Test
    fun `clearPendingViewId only clears the id, not an unrelated pending flag`() {
        val viewModel = buildViewModel()
        viewModel.requestViewReveal("attachment-1")

        viewModel.clearPendingViewId()

        val state = viewModel.state.value
        assertNull(state.pendingViewId)
        // Deliberately not asserted false: clearPendingViewId's contract (per AttachmentPicker.kt)
        // is narrower than onViewerRevealDenied's — it is the post-authentication cleanup call,
        // not a second way to deny. It only clears pendingViewId.
    }

    // ---------------------------------------------------------------------------------------
    // The gate is type-scoped as well as item-scoped (2026-08-19).
    //
    // `reprompt` defaults to false, so before `highValue` existed a photographed passport on an
    // ordinary Identity opened with no challenge at all — while `ALWAYS_REAUTH_FIELDS` re-asked
    // before releasing the *number* typed off that same document, on the same item, unconditionally.
    // ---------------------------------------------------------------------------------------

    private fun repositoryHolding(item: VaultItem): VaultRepository {
        val repository = mock<VaultRepository>()
        whenever(repository.visibilitySnapshot()).thenReturn(
            VaultVisibilitySnapshot(
                liveItems = listOf(item),
                trashItems = emptyList(),
                liveFolders = emptyList(),
                trashedFolderRows = emptyList(),
                concealedItemIds = emptySet(),
                concealedFolderIds = emptySet(),
                directlyHiddenItemIds = emptySet(),
                directlyHiddenFolderIds = emptySet(),
                nextExpiry = null,
                // Required rather than defaulted: this count is what an Empty Trash confirmation
                // states before destroying that many items, and a silent 0 is the shape of the bug
                // it exists to prevent. This fixture holds no trash.
                purgeableTrashItemCount = 0,
            ),
        )
        // `load()` now also asks AttachmentQuota.exceedsFreeLimit (see 3-2), which reads the
        // complete storage inventory rather than the visibility projection above.
        whenever(repository.getCompleteStorageInventory()).thenReturn(
            VaultStorageInventory(allItems = listOf(item), allFolders = emptyList()),
        )
        return repository
    }

    private fun item(content: ItemContent, reprompt: Boolean = false): VaultItem {
        val now = Instant.EPOCH
        return VaultItem(
            id = UUID.randomUUID(),
            title = "t",
            createdAt = now,
            updatedAt = now,
            reprompt = reprompt,
            content = content,
        )
    }

    @Test
    fun `an Identity's attachments are high-value even with reprompt off`() {
        val identity = item(Identity(passport = "X1234567"))
        val viewModel = buildViewModel(repositoryHolding(identity))

        viewModel.load(identity.id.toString())

        val state = viewModel.state.value
        assertFalse(state.reprompt, "the item's own flag is off — that is the case this covers")
        assertTrue(state.highValue, "an Identity's attachments must be gated by type")
    }

    // The widened half (2026-08-27): `isHighValueByType` read `content is Identity` only, so the
    // argument above — a photographed secret is gated because the typed field beside it is — held
    // for one type and not the others that carry an `ALWAYS_REAUTH_FIELDS` member. A Card's CVV
    // always re-asks; before this widening a photograph of the card front, showing the same CVV,
    // opened with nothing. Coordinate and SeedPhrase have the identical shape.
    @Test
    fun `a Card's attachments are high-value, because its CVV always re-asks`() {
        val card = item(Card(cvv = "123"))
        val viewModel = buildViewModel(repositoryHolding(card))

        viewModel.load(card.id.toString())

        assertTrue(viewModel.state.value.highValue, "a photographed card front costs what its CVV costs")
    }

    @Test
    fun `a Coordinate's attachments are high-value, because its position always re-asks`() {
        val coordinate = item(Coordinate(capturedAt = Instant.EPOCH))
        val viewModel = buildViewModel(repositoryHolding(coordinate))

        viewModel.load(coordinate.id.toString())

        assertTrue(viewModel.state.value.highValue)
    }

    @Test
    fun `a SeedPhrase's attachments are high-value, because its words always re-ask`() {
        val seed = item(SeedPhrase(words = listOf("abandon")))
        val viewModel = buildViewModel(repositoryHolding(seed))

        viewModel.load(seed.id.toString())

        assertTrue(viewModel.state.value.highValue)
    }

    @Test
    fun `an ordinary item's attachments are not high-value`() {
        val login = item(Login(username = "u"))
        val viewModel = buildViewModel(repositoryHolding(login))

        viewModel.load(login.id.toString())

        // The negative half. Without it, a change that made everything high-value would pass the
        // test above while turning every attachment on every item into a biometric prompt.
        assertFalse(viewModel.state.value.highValue)
        assertFalse(viewModel.state.value.reprompt)
    }

    /**
     * Regression for 34-1, and the one case the type test alone could not reach. A Login carrying
     * an embedded second factor holds `totpSecret` exactly as a standalone Totp does, and
     * `ItemDetailScreen` gates that field unconditionally on both — "an always-reprompt field (5.2/
     * A2) even though the code above is not". Its attachments opened with nothing.
     *
     * **Delete the `content is Login && content.totp != null` arm from `isHighValueByType` and this
     * goes red**, while the test directly above — a Login with no `totp` — stays green either way,
     * which is why it was not already covering this.
     */
    @Test
    fun `a Login carrying a second factor is high-value, because its seed always re-asks`() {
        val login = item(Login(username = "u", totp = Totp(secretBase32 = "JBSWY3DPEHPK3PXP")))
        val viewModel = buildViewModel(repositoryHolding(login))

        viewModel.load(login.id.toString())

        assertFalse(viewModel.state.value.reprompt, "the item's own flag is off — that is the case this covers")
        assertTrue(
            viewModel.state.value.highValue,
            "a photograph of the setup QR code costs what the seed beside it costs",
        )
    }

    // ---------------------------------------------------------------------------------------
    // 43-1: a refused challenge has to say so.
    //
    // `onViewerRevealDenied` cleared the pending flag and wrote nothing else, and the screen's only
    // two error surfaces are unreachable for a Cancelled/Denied/Error outcome — so the row simply
    // stopped responding, which reads as a broken app rather than a declined challenge. The file
    // editor's identical gate has always shown a mapped sentence. No new strings: the four
    // `files_viewer_auth_*` resources and `viewerRevealMessage` are already in this module.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a refused challenge names which refusal it was`() {
        val viewModel = buildViewModel()
        viewModel.requestViewReveal("attachment-1")

        viewModel.onViewerRevealDenied(viewerRevealMessage(RevealAuth.RevealOutcome.UserCancelled))

        val state = viewModel.state.value
        assertEquals(
            R.string.files_viewer_auth_cancelled,
            state.viewerAuthMessage,
            "cancelling a prompt is not being rejected by one, and silence says neither",
        )
        // Nothing is released by saying so — the fail-closed half above still holds.
        assertFalse(state.pendingViewerReveal)
        assertNull(state.pendingViewId)
        assertNull(state.error, "and it must not land in the field the quota refusal formats with arguments")
    }

    @Test
    fun `trying again clears the previous refusal`() {
        // The control. A message that outlived the attempt it described would sit under a prompt
        // that is on screen right now, reporting the wrong one.
        val viewModel = buildViewModel()
        viewModel.requestViewReveal("attachment-1")
        viewModel.onViewerRevealDenied(viewerRevealMessage(RevealAuth.RevealOutcome.Denied))
        assertEquals(R.string.files_viewer_auth_denied, viewModel.state.value.viewerAuthMessage)

        viewModel.requestViewReveal("attachment-1")

        assertNull(viewModel.state.value.viewerAuthMessage)
        assertTrue(viewModel.state.value.pendingViewerReveal)
    }

    @Test
    fun `the screen maps the outcome and leaves the no-credential case to its own sentence`() {
        // The wiring, which the ViewModel cannot assert about itself: a mapped message the screen
        // never passes is a fix that is not applied. Source-scanning for the reason
        // PlaybackScreenAwakeTest gives — this module's unit-test classpath has no Compose UI test
        // artifact, and the claim is about which argument one call site passes.
        //
        // The `canChallenge` half matters: a device with no screen lock already gets
        // `files_attachments_reveal_unavailable`, which says what to do about it, and two messages
        // for one refusal would be worse than the silence being replaced.
        val screen = java.io.File("src/main/kotlin/com/localkeep/feature/files/AttachmentPicker.kt").readText()
        assertTrue(
            "viewModel.onViewerRevealDenied(if (canChallenge) viewerRevealMessage(outcome) else null)" in screen,
            "the reveal effect must hand the outcome's own sentence to the ViewModel",
        )
        assertTrue(
            "stringResource(state.viewerAuthMessage)" in screen,
            "and the screen has to render it",
        )
    }

    @Test
    fun `the challenge is held as ViewModel state, not Composable-local, so it survives recreation`() {
        // A configuration change recreates the Composable but not the ViewModel (same
        // viewModelScope survives); state living on the ViewModel's own StateFlow, rather than in
        // `remember`, is what makes that survive. Re-reading state.value through a second
        // reference to the same instance is the strongest assertion available without an actual
        // Activity recreation, which this module's test setup does not drive.
        val viewModel = buildViewModel()
        viewModel.requestViewReveal("attachment-1")

        val stateAfterSimulatedRecreation = viewModel.state.value

        assertTrue(stateAfterSimulatedRecreation.pendingViewerReveal)
        assertEquals("attachment-1", stateAfterSimulatedRecreation.pendingViewId)
    }
}

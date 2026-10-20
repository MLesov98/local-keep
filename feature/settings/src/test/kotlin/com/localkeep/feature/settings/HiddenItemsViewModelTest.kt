package com.localkeep.feature.settings

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Folder
import com.localkeep.core.model.HiddenLease
import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.ForegroundVisibilityCoordinator
import com.localkeep.core.vault.HiddenEntries
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.HideOutcome
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultSessionEndedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The authenticated Hidden-items gate and management list (make-invisible plan, Task 6).
 *
 * What these pin is the gate's fail-closed shape, which is the security-relevant half: content is
 * never composed before an approved path succeeds, a cancelled biometric authorizes nothing, a lock
 * that lands while a prompt is up cannot be authorized by a late success, and a lock discards both
 * the authorization and every cached entry id.
 *
 * The *slot* the knowledge factor verifies is `:core:vault`'s contract and is tested there
 * (`reauthenticateForCurrentVault` → slot 0 normally, slot 1 in duress). What is testable here, and
 * what `decoy password succeeds while the slot-0 password is rejected` covers, is that this screen
 * asks only that current-slot question and reports both outcomes with the same resource ids in both
 * kinds of session — so nothing in the decoy UI names, requests or distinguishes the real password.
 *
 * `VaultRepository`/`VaultLockManager`/`KeystoreManager` are mocked: their real construction chains
 * touch AndroidKeyStore and the vault file, neither of which exists under Robolectric (see
 * `SettingsViewModelBiometricLoadTest` for the same note).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenItemsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val now: Instant = Instant.parse("2026-08-18T12:00:00Z")

    private lateinit var repository: VaultRepository
    private lateinit var lockManager: VaultLockManager
    private lateinit var keystoreManager: KeystoreManager
    private lateinit var lockState: MutableStateFlow<LockState>
    private lateinit var presentation: MutableStateFlow<VaultPresentationState>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mock()
        lockManager = mock()
        keystoreManager = mock()
        lockState = MutableStateFlow(LockState.Locked)
        presentation = MutableStateFlow(VaultPresentationState.Locked)
        whenever(lockManager.lockState).thenReturn(lockState)
        whenever(repository.presentationState).thenReturn(presentation)
        whenever(repository.getHiddenEntriesForManagement()).thenReturn(HiddenEntries(emptyList(), emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): HiddenItemsViewModel = HiddenItemsViewModel(
        repository = repository,
        lockManager = lockManager,
        keystoreManager = keystoreManager,
        visibilityCoordinator = mock<ForegroundVisibilityCoordinator>(),
        context = ApplicationProvider.getApplicationContext(),
    )

    /** An unlocked session, without building the real `LockState.Unlocked` (it needs a live DEK). */
    private fun unlock() {
        presentation.value = VaultPresentationState.Unlocked(mock())
    }

    private fun lock() {
        presentation.value = VaultPresentationState.Locked
        lockState.value = LockState.Locked
    }

    private fun item(title: String, until: Instant) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = now,
        updatedAt = now,
        content = Note(),
        hiddenUntil = until,
    )

    private fun folder(name: String, until: Instant) =
        Folder(id = UUID.randomUUID(), name = name, hiddenUntil = until)

    @Test
    fun `nothing is listed before an approved path succeeds`() = runTest(dispatcher) {
        whenever(repository.getHiddenEntriesForManagement())
            .thenReturn(HiddenEntries(listOf(item("Offshore", now.plus(Duration.ofHours(1)))), emptyList()))
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.authorized, "the gate must start closed")
        assertTrue(viewModel.state.value.entries.isEmpty(), "no entry may be fetched before auth")
        verify(repository, never()).getHiddenEntriesForManagement()
    }

    @Test
    fun `a cancelled biometric renders no content`() = runTest(dispatcher) {
        unlock()
        val viewModel = buildViewModel()
        advanceUntilIdle()

        val generation = viewModel.beginAuthChallenge()
        viewModel.applyBiometricOutcome(granted = false, generation = generation)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.authorized, "cancel or back must authorize nothing")
        assertTrue(viewModel.state.value.entries.isEmpty())
    }

    @Test
    fun `a lock while the prompt is up defeats a late biometric success`() = runTest(dispatcher) {
        unlock()
        val viewModel = buildViewModel()
        advanceUntilIdle()

        val generation = viewModel.beginAuthChallenge()
        lock()
        advanceUntilIdle()
        viewModel.applyBiometricOutcome(granted = true, generation = generation)
        advanceUntilIdle()

        assertFalse(
            viewModel.state.value.authorized,
            "a success carrying a generation the auto-lock invalidated must not open the list",
        )
    }

    @Test
    fun `the current vault password lists only explicitly leased entries`() = runTest(dispatcher) {
        val hiddenItem = item("Offshore login", now.plus(Duration.ofHours(2)))
        val hiddenFolder = folder("Offshore", HiddenLease.INDEFINITE)
        whenever(repository.getHiddenEntriesForManagement())
            .thenReturn(HiddenEntries(listOf(hiddenItem), listOf(hiddenFolder)))
        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(true)
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.authorizeWithPassword("correct horse".toCharArray())
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.authorized)
        assertEquals(
            setOf(HiddenTarget.Item(hiddenItem.id), HiddenTarget.Folder(hiddenFolder.id)),
            state.entries.map { it.target }.toSet(),
            "exactly the directly-leased records get a row — a derived descendant has no lease to stop",
        )
        assertEquals(setOf("Offshore login", "Offshore"), state.entries.map { it.name }.toSet())
    }

    @Test
    fun `a hidden note's body never reaches the peek`() = runTest(dispatcher) {
        // The peek exists to answer "what is this record" without un-hiding it, and every other
        // type has a label field separate from its secret. A note does not: its body *is* the
        // record, and in a password manager that body is where recovery codes, PINs and security
        // answers go when they fit no structured field. Showing it turned the peek into a detail
        // screen for the one type whose content is arbitrary.
        val secret = "Recovery codes: 8812-4471 / 2290-0038"
        val note = VaultItem(
            id = UUID.randomUUID(),
            title = "Bank recovery",
            createdAt = now,
            updatedAt = now,
            content = Note(body = secret),
            hiddenUntil = now.plus(Duration.ofHours(2)),
        )
        whenever(repository.getHiddenEntriesForManagement())
            .thenReturn(HiddenEntries(listOf(note), emptyList()))
        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(true)
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.authorizeWithPassword("correct horse".toCharArray())
        advanceUntilIdle()

        val entry = viewModel.state.value.entries.single()
        assertEquals("Bank recovery", entry.name, "the title still identifies the row")
        assertTrue(
            entry.peekLines.isEmpty(),
            "a note peeks as nothing at all — anything else leaks the body: ${entry.peekLines}",
        )
    }

    @Test
    fun `a wrong current vault password reports a resource id and opens nothing`() = runTest(dispatcher) {
        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(false)
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.authorizeWithPassword("wrong".toCharArray())
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.authorized)
        assertEquals(SettingsMessage.Simple(R.string.settings_hidden_error_password), state.error)
    }

    @Test
    fun `a lock mid-verification reports the session-ended id, never an exception string`() =
        runTest(dispatcher) {
            whenever(lockManager.reauthenticateForCurrentVault(any()))
                .thenThrow(VaultSessionEndedException("reauthenticate"))
            unlock()

            val viewModel = buildViewModel()
            advanceUntilIdle()
            viewModel.authorizeWithPassword("anything".toCharArray())
            advanceUntilIdle()

            assertEquals(
                SettingsMessage.Simple(R.string.settings_hidden_error_session_ended),
                viewModel.state.value.error,
            )
            assertFalse(viewModel.state.value.authorized)
        }

    /**
     * The decoy half of Decision 15. In a duress session the current vault password is the decoy's,
     * so it opens this screen; the real slot-0 password is not the current slot's secret and
     * `reauthenticateForCurrentVault` rejects it. Both answers arrive here as the same boolean and
     * are reported with the same resource id as in a normal session — the ViewModel has no branch on
     * `isDuress` at all, which is what makes the two indistinguishable from the UI.
     */
    @Test
    fun `in duress the decoy password opens the screen and the real one is rejected`() = runTest(dispatcher) {
        val indefinite = item("Decoy note", HiddenLease.INDEFINITE)
        whenever(repository.getHiddenEntriesForManagement())
            .thenReturn(HiddenEntries(listOf(indefinite), emptyList()))
        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(false)
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.authorizeWithPassword("the real slot-0 password".toCharArray())
        advanceUntilIdle()
        assertFalse(viewModel.state.value.authorized, "a decoy UI must never accept the real password")
        assertEquals(SettingsMessage.Simple(R.string.settings_hidden_error_password), viewModel.state.value.error)

        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(true)
        viewModel.authorizeWithPassword("the decoy password".toCharArray())
        advanceUntilIdle()

        assertTrue(viewModel.state.value.authorized)
        // An indefinite hide made inside the decoy is still recoverable from the decoy's own screen.
        val row = viewModel.state.value.entries.single()
        assertEquals(HiddenLease.INDEFINITE, row.until)
        viewModel.stopHiding(row)
        advanceUntilIdle()
        verify(repository).stopHiding(HiddenTarget.Item(indefinite.id))
    }

    @Test
    fun `with nothing leased the list is empty, and says nothing about why`() = runTest(dispatcher) {
        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(true)
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.authorizeWithPassword("correct".toCharArray())
        advanceUntilIdle()

        assertTrue(viewModel.state.value.authorized)
        assertTrue(viewModel.state.value.entries.isEmpty())
    }

    @Test
    fun `a lock discards the authorization and every cached entry id`() = runTest(dispatcher) {
        whenever(repository.getHiddenEntriesForManagement())
            .thenReturn(HiddenEntries(listOf(item("Offshore", now.plus(Duration.ofHours(1)))), emptyList()))
        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(true)
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.authorizeWithPassword("correct".toCharArray())
        advanceUntilIdle()
        assertTrue(viewModel.state.value.entries.isNotEmpty())

        lock()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.authorized, "auto-lock clears the authorization")
        assertTrue(viewModel.state.value.entries.isEmpty(), "and the entry ids it cached")
    }

    @Test
    fun `the shared projection refreshes the rows without a screen-local timer`() = runTest(dispatcher) {
        val first = item("Offshore", now.plus(Duration.ofMinutes(5)))
        whenever(repository.getHiddenEntriesForManagement())
            .thenReturn(HiddenEntries(listOf(first), emptyList()))
        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(true)
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.authorizeWithPassword("correct".toCharArray())
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.entries.size)

        // The lease expired: the projection re-emits, and the repository now reports no active lease.
        whenever(repository.getHiddenEntriesForManagement()).thenReturn(HiddenEntries(emptyList(), emptyList()))
        presentation.value = VaultPresentationState.Unlocked(mock())
        advanceUntilIdle()

        assertTrue(
            viewModel.state.value.entries.isEmpty(),
            "an expiry the shared presentation flow announced must clear the row",
        )
    }

    @Test
    fun `changing the duration re-leases the target`() = runTest(dispatcher) {
        val hidden = item("Offshore", now.plus(Duration.ofMinutes(5)))
        whenever(repository.getHiddenEntriesForManagement())
            .thenReturn(HiddenEntries(listOf(hidden), emptyList()))
        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(true)
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.authorizeWithPassword("correct".toCharArray())
        advanceUntilIdle()

        val target = now.plus(Duration.ofDays(1))
        viewModel.changeDuration(viewModel.state.value.entries.single(), target)
        advanceUntilIdle()

        verify(repository).changeHiddenUntil(HiddenTarget.Item(hidden.id), target)
    }

    @Test
    fun `a mutation after the vault locks reports the session-ended state`() = runTest(dispatcher) {
        val hidden = item("Offshore", now.plus(Duration.ofMinutes(5)))
        whenever(repository.getHiddenEntriesForManagement())
            .thenReturn(HiddenEntries(listOf(hidden), emptyList()))
        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(true)
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.authorizeWithPassword("correct".toCharArray())
        advanceUntilIdle()

        whenever(repository.stopHiding(HiddenTarget.Item(hidden.id)))
            .thenThrow(IllegalStateException("vault is locked"))

        viewModel.stopHiding(viewModel.state.value.entries.single())
        advanceUntilIdle()

        assertEquals(
            SettingsMessage.Simple(R.string.settings_hidden_error_session_ended),
            viewModel.state.value.error,
        )
        assertFalse(viewModel.state.value.isWorking)
    }

    @Test
    fun `rotating the phrase is one repository call and reports success by resource id`() =
        runTest(dispatcher) {
            whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(true)
            whenever(repository.changeRevealPhrase(any())).thenReturn(HideOutcome.Hidden)
            unlock()

            val viewModel = buildViewModel()
            advanceUntilIdle()
            viewModel.authorizeWithPassword("correct".toCharArray())
            advanceUntilIdle()

            viewModel.changeRevealPhrase("a new phrase".toCharArray())
            advanceUntilIdle()

            verify(repository).changeRevealPhrase(any())
            assertEquals(
                SettingsMessage.Simple(R.string.settings_hidden_phrase_changed),
                viewModel.state.value.message,
            )
        }

    @Test
    fun `a colliding new phrase is refused with its own resource id`() = runTest(dispatcher) {
        whenever(lockManager.reauthenticateForCurrentVault(any())).thenReturn(true)
        whenever(repository.changeRevealPhrase(any())).thenReturn(HideOutcome.PhraseCollision)
        unlock()

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.authorizeWithPassword("correct".toCharArray())
        advanceUntilIdle()

        viewModel.changeRevealPhrase("offshore".toCharArray())
        advanceUntilIdle()

        assertEquals(
            SettingsMessage.Simple(R.string.settings_hidden_phrase_collision),
            viewModel.state.value.error,
        )
    }

    @Test
    fun `an unauthorized caller cannot mutate a lease`() = runTest(dispatcher) {
        unlock()
        val viewModel = buildViewModel()
        advanceUntilIdle()

        val row = HiddenEntryRow(
            target = HiddenTarget.Item(UUID.randomUUID()),
            name = "Offshore",
            isFolder = false,
            until = HiddenLease.INDEFINITE,
        )
        viewModel.stopHiding(row)
        viewModel.changeDuration(row, now.plus(Duration.ofDays(1)))
        viewModel.changeRevealPhrase("phrase".toCharArray())
        advanceUntilIdle()

        verify(repository, never()).stopHiding(any())
        verify(repository, never()).changeHiddenUntil(any(), any())
        verify(repository, never()).changeRevealPhrase(any())
    }
}

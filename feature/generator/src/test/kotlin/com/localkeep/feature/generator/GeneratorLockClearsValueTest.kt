package com.localkeep.feature.generator

import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The generated password does not survive a lock.
 *
 * `GeneratorUiState.generatedValue` is a candidate password in plaintext, and this ViewModel can
 * outlive the vault being shut: GENERATOR is one of the routes pinned to `saveAndRestore = true`,
 * so the back-stack entry holding it is not necessarily destroyed when the lock lands. Before this
 * collector existed nothing under `feature/generator` referenced the lock state at all.
 *
 * `VaultLockManager` is mocked because its real constructor opens AndroidKeyStore; only its
 * `lockState` flow is exercised here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorLockClearsValueTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var lockManager: VaultLockManager
    private lateinit var lockState: MutableStateFlow<LockState>

    private val unlocked = LockState.Unlocked(
        dek = ByteArray(32),
        vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lockManager = mock()
        lockState = MutableStateFlow(unlocked)
        whenever(lockManager.lockState).thenReturn(lockState)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a lock drops the generated value and its entropy readout`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.generatedValue, "precondition: a value is on screen")

        lockState.value = LockState.Locked
        advanceUntilIdle()

        assertNull(
            viewModel.state.value.generatedValue,
            "a candidate password must not survive the vault being locked",
        )
        assertEquals(
            0.0,
            viewModel.state.value.entropyBits,
            "the entropy readout describes a value that no longer exists",
        )
    }

    /** The screen must not be left permanently blank by the clear above. */
    @Test
    fun `unlocking again refills the screen with a different value`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        advanceUntilIdle()
        val before = viewModel.state.value.generatedValue

        lockState.value = LockState.Locked
        advanceUntilIdle()
        lockState.value = unlocked
        advanceUntilIdle()

        val after = viewModel.state.value.generatedValue
        assertNotNull(after, "the generator must have a value again once the vault is open")
        assertNotEquals(before, after, "the refill must be a fresh draw, not the pre-lock value")
    }
}

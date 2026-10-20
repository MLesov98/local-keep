package com.localkeep.feature.generator

import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A failed generation does not leave the previous password on screen.
 *
 * Turning off the last enabled character class makes `PasswordGenerator.RandomConfig`'s
 * `require(uppercase || lowercase || digits || symbols)` throw. Before this fix the failure branch
 * recorded only `error`, so `generatedValue` and `entropyBits` kept the values produced by the
 * *previous* configuration: the card went on showing a password containing character classes the
 * now-visible switches say are off, above an entropy figure computed for a draw that no longer
 * matches the settings. Copying it is the obvious next action and the value is not what the screen
 * claims it is.
 *
 * Mirrors `GeneratorLockClearsValueTest`, including mocking `VaultLockManager` because its real
 * constructor opens AndroidKeyStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorFailureClearsValueTest {

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
    fun `turning off the last character class clears the value it no longer describes`() =
        runTest(dispatcher) {
            val viewModel = GeneratorViewModel(lockManager)
            viewModel.setMode(GeneratorUiState.Mode.RANDOM)
            advanceUntilIdle()
            assertNotNull(
                viewModel.state.value.generatedValue,
                "precondition: a password is on screen before anything is switched off",
            )

            viewModel.onUppercaseToggled(false)
            viewModel.onLowercaseToggled(false)
            viewModel.onDigitsToggled(false)
            viewModel.onSymbolsToggled(false)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(
                R.string.generator_error_no_options,
                state.error,
                "precondition: the generator must be reporting the no-options failure",
            )
            assertNull(
                state.generatedValue,
                "a password drawn under the previous settings must not stay on screen beside an " +
                    "error saying no character class is enabled",
            )
            assertEquals(
                0.0,
                state.entropyBits,
                "the entropy readout describes a value that is no longer shown",
            )
        }

    /** The control: a generation that succeeds must still fill the card. */
    @Test
    fun `re-enabling a class produces a value again`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        viewModel.setMode(GeneratorUiState.Mode.RANDOM)
        advanceUntilIdle()

        viewModel.onUppercaseToggled(false)
        viewModel.onLowercaseToggled(false)
        viewModel.onDigitsToggled(false)
        viewModel.onSymbolsToggled(false)
        advanceUntilIdle()

        viewModel.onLowercaseToggled(true)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertNull(state.error, "the failure must clear once a class is enabled again")
        assertNotNull(state.generatedValue, "the card must refill on a successful draw")
    }
}

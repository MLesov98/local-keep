package com.localkeep.feature.generator

import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
 * A tap that changes no setting must not replace the password on screen.
 *
 * Every setting change regenerates - that live feedback loop is deliberate - but the already
 * selected mode segment and the length slider at its current value both report a "change" to the
 * same value. The password was replaced with no announcement, after it had been copied and pasted
 * into a sign-up form, so "Save as a new login" filed a value that opened nothing.
 *
 * `VaultLockManager` is mocked because its real constructor opens AndroidKeyStore; only its
 * `lockState` flow is exercised, as in [GeneratorLockClearsValueTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorUnchangedSettingKeepsValueTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var lockManager: VaultLockManager

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        lockManager = mock()
        whenever(lockManager.lockState).thenReturn(
            MutableStateFlow(
                LockState.Unlocked(
                    dek = ByteArray(32),
                    vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
                ),
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selecting the mode that is already selected keeps the password`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        advanceUntilIdle()
        val before = viewModel.state.value

        viewModel.setMode(before.mode)

        assertEquals(before.generatedValue, viewModel.state.value.generatedValue)
        assertEquals(before.entropyBits, viewModel.state.value.entropyBits)
    }

    @Test
    fun `the slider at its current value keeps the password`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        advanceUntilIdle()
        val before = viewModel.state.value

        viewModel.onLengthChanged(before.length)

        assertEquals(before.generatedValue, viewModel.state.value.generatedValue)
    }

    @Test
    fun `a toggle set to the value it already has keeps the password`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        advanceUntilIdle()
        val before = viewModel.state.value

        viewModel.onUppercaseToggled(before.uppercase)
        viewModel.onNoAmbiguousToggled(before.noAmbiguous)

        assertEquals(before.generatedValue, viewModel.state.value.generatedValue)
    }

    /** The control: a real change still regenerates, or the guard just froze the screen. */
    @Test
    fun `a real change still draws a new password`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        advanceUntilIdle()
        val before = viewModel.state.value

        viewModel.onLengthChanged(before.length + 9)

        val after = viewModel.state.value
        assertEquals(before.length + 9, after.length)
        assertNotNull(after.generatedValue)
        assertEquals(before.length + 9, after.generatedValue!!.length, "the new value follows the new length")
        assertNotEquals(before.generatedValue, after.generatedValue)
    }
}

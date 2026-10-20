package com.localkeep.feature.generator

import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Regression for the 2026-09-09 functional sweep finding: a passphrase generated before
 * `LocalKeepApplication.onCreate`'s off-main-thread `PasswordGenerator.setWordlist` call finishes
 * hits `generatePassphrase`'s own `wordlist ?: error("EFF wordlist has not been loaded")`, and
 * before this fix that failure was reported with `generator_error_no_options` — "Choose at least
 * one valid option", the RANDOM-mode sentence for every character class being switched off, which
 * names nothing a passphrase screen has to choose and gives no reason to try again.
 *
 * `PasswordGenerator` is a singleton `object`, and nothing in this module's test suite ever calls
 * `setWordlist` (only `:core:generator`'s own tests do, in a separate module/test JVM), so
 * `generatePassphrase` is guaranteed to hit that exact failure here — this test needs no fake and
 * no reflection to reach it, which is itself evidence the race `LocalKeepApplication`'s comment
 * calls "cannot realistically happen" is trivially reachable from a cold interpreter state.
 *
 * Mirrors `GeneratorFailureClearsValueTest`'s harness: `VaultLockManager` is mocked because its
 * real constructor opens AndroidKeyStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorWordlistErrorMessageTest {

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

    // FAILS WITHOUT THE FIX: before GeneratorViewModel picked the error by mode, every generation
    // failure — RANDOM or PASSPHRASE — reported generator_error_no_options.
    @Test
    fun `an unloaded word list reports itself, not the no-options sentence`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        viewModel.setMode(GeneratorUiState.Mode.PASSPHRASE)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(
            R.string.generator_error_wordlist_unavailable,
            state.error,
            "a passphrase-mode failure with the word list unloaded must name the word list, not " +
                "the RANDOM-mode 'choose an option' sentence — found error=${state.error}",
        )
        assertNull(
            state.generatedValue,
            "no password should be on screen once the draw has failed",
        )
    }

    /** The control: RANDOM mode's real failure — every character class off — must keep its own text. */
    @Test
    fun `random mode's no-options failure keeps its own message`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        viewModel.setMode(GeneratorUiState.Mode.RANDOM)
        advanceUntilIdle()

        viewModel.onUppercaseToggled(false)
        viewModel.onLowercaseToggled(false)
        viewModel.onDigitsToggled(false)
        viewModel.onSymbolsToggled(false)
        advanceUntilIdle()

        assertEquals(
            R.string.generator_error_no_options,
            viewModel.state.value.error,
            "RANDOM mode's own failure must not be swept into the word-list message",
        )
    }
}

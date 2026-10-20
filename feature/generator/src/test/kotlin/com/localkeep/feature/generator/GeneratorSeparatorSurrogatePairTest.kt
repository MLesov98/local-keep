package com.localkeep.feature.generator

import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * `onSeparatorChanged` used to truncate with `value.take(MAX_SEPARATOR_LENGTH)` — a `Char`
 * (UTF-16 code unit) count. Typing an emoji from the IME's emoji picker into a separator field
 * that already holds one character appends a surrogate *pair*, and `take(2)` lands exactly
 * between its two halves: the kept `separator` is a lone high surrogate, which is not valid text
 * on its own and is joined into every word of every generated passphrase from that point on
 * (`PasswordGenerator.generatePassphrase`'s own `require(separator.length <= 2)` does not catch
 * it — a truncated char plus lone surrogate is still length 2). Same defect class as
 * `lkMonogram` (`core/ui`) and `TitleSuggestion.kt`'s title cap, both fixed the same way before.
 *
 * Mirrors `GeneratorFailureClearsValueTest`'s harness: `VaultLockManager` is mocked because its
 * real constructor opens AndroidKeyStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorSeparatorSurrogatePairTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var lockManager: VaultLockManager
    private lateinit var lockState: MutableStateFlow<LockState>

    private val unlocked = LockState.Unlocked(
        dek = ByteArray(32),
        vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
    )

    /** U+1F600 GRINNING FACE — a surrogate pair, high then low. */
    private val emoji = "😀"

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
    fun `a separator cap that would split a surrogate pair backs off instead`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        viewModel.setMode(GeneratorUiState.Mode.PASSPHRASE)
        advanceUntilIdle()

        // "X" + the emoji is 3 UTF-16 units; the 2-unit cap falls exactly between the pair's halves.
        viewModel.onSeparatorChanged("X$emoji")
        advanceUntilIdle()

        val separator = viewModel.state.value.separator
        assertEquals(
            "X",
            separator,
            "the cap must back off to the last whole code point (\"X\") rather than keep a lone " +
                "high surrogate; got a separator of length ${separator.length}",
        )
        assertFalse(
            separator.any { it.isHighSurrogate() || it.isLowSurrogate() },
            "the separator must never contain an unpaired surrogate: $separator",
        )
    }

    /** The control: a separator that is exactly one whole emoji must not be touched or corrupted. */
    @Test
    fun `a separator that is exactly one whole surrogate pair is kept intact`() = runTest(dispatcher) {
        val viewModel = GeneratorViewModel(lockManager)
        viewModel.setMode(GeneratorUiState.Mode.PASSPHRASE)
        advanceUntilIdle()

        viewModel.onSeparatorChanged(emoji)
        advanceUntilIdle()

        assertEquals(emoji, viewModel.state.value.separator, "a whole surrogate pair at the cap must survive intact")
    }
}

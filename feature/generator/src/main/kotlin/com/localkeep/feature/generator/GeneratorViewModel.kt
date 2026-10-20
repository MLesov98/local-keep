package com.localkeep.feature.generator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.generator.PasswordGenerator
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GeneratorUiState(
    val mode: Mode = Mode.RANDOM,
    val generatedValue: String? = null,
    val entropyBits: Double = 0.0,
    /** Mirrors `PasswordGenerator.RandomConfig.length`, which says why it is 21 and not 20. */
    val length: Int = 21,
    val uppercase: Boolean = true,
    val lowercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val noAmbiguous: Boolean = true,
    val wordCount: Int = 6,
    val separator: String = "-",
    val capitalize: Boolean = false,
    val includeNumber: Boolean = false,
    /** A string resource id, not a string: a ViewModel has no Context to resolve one,
     *  and must not gain one for this (the i18n plan's ViewModel rule). */
    @param:androidx.annotation.StringRes val error: Int? = null,
) {
    enum class Mode {
        RANDOM,
        PASSPHRASE,
    }
}

@HiltViewModel
class GeneratorViewModel @Inject constructor(
    private val lockManager: VaultLockManager,
) : ViewModel() {
    private val _state = MutableStateFlow(GeneratorUiState())
    val state: StateFlow<GeneratorUiState> = _state.asStateFlow()

    init {
        generate()
        viewModelScope.launch {
            lockManager.lockState.collect { lockState ->
                if (lockState !is LockState.Unlocked) {
                    // A generated value is a candidate password in plaintext, and this ViewModel can
                    // outlive a lock: GENERATOR is a saved-and-restored top-level route, so the entry
                    // holding it is not necessarily torn down when the vault shuts. Don't keep it —
                    // the next person to unlock must not inherit the last one's candidate secret.
                    // The entropy readout goes with it; it describes a value that no longer exists.
                    _state.update { it.copy(generatedValue = null, entropyBits = 0.0) }
                } else if (_state.value.generatedValue == null) {
                    // Refill only when the screen is empty. Every vault save re-emits Unlocked with a
                    // new Vault, so regenerating unconditionally here would replace the value the user
                    // is reading whenever anything else writes to the vault.
                    generate()
                }
            }
        }
    }

    fun generate() {
        val current = _state.value
        runCatching {
            if (current.mode == GeneratorUiState.Mode.RANDOM) {
                PasswordGenerator.generateRandom(
                    PasswordGenerator.RandomConfig(
                        length = current.length,
                        uppercase = current.uppercase,
                        lowercase = current.lowercase,
                        digits = current.digits,
                        symbols = current.symbols,
                        minUppercase = current.uppercase.minimum(),
                        minLowercase = current.lowercase.minimum(),
                        minDigits = current.digits.minimum(),
                        minSymbols = current.symbols.minimum(),
                        noAmbiguous = current.noAmbiguous,
                    ),
                )
            } else {
                PasswordGenerator.generatePassphrase(
                    PasswordGenerator.PassphraseConfig(
                        wordCount = current.wordCount,
                        separator = current.separator,
                        capitalize = current.capitalize,
                        includeNumber = current.includeNumber,
                    ),
                )
            }
        }.onSuccess { generated ->
            _state.update {
                it.copy(
                    generatedValue = generated.value,
                    entropyBits = generated.entropyBits,
                    error = null,
                )
            }
        }.onFailure {
            // Clear the value with the error, not just the error. The password still on screen was
            // drawn from the *previous* configuration — so leaving it there shows a value the
            // settings below it no longer describe, next to an entropy figure that was never true of
            // it. The user can copy it and believe it honours the boxes they can see.
            //
            // The message is picked by mode, not by the caught exception, because in RANDOM mode the
            // only failure `PasswordGenerator.generateRandom`'s `RandomConfig` can throw is "every
            // character class is off" (`generator_error_no_options` names exactly that), while in
            // PASSPHRASE mode this ViewModel already clamps both inputs `PassphraseConfig.init`
            // validates — `onWordCountChanged` coerces into 3..10, itself inside the 3..20 the config
            // requires, and `onSeparatorChanged` caps the separator at 2 code units via
            // `takeWholeCodePoints` — so `generatePassphrase`'s only reachable failure is its own
            // `wordlist ?: error("EFF wordlist has not been loaded")`. That is real, not
            // hypothetical: `LocalKeepApplication.onCreate` installs the bundled EFF list off the
            // main thread precisely because this screen (or onboarding's Suggest link) can be
            // reached before that load finishes. Before this fix a hit of that race surfaced the
            // RANDOM-mode sentence "Choose at least one valid option" over a passphrase screen with
            // nothing to choose — true of no passphrase setting and no help toward the fix, which is
            // waiting a moment and generating again.
            _state.update {
                it.copy(
                    generatedValue = null,
                    entropyBits = 0.0,
                    error = if (current.mode == GeneratorUiState.Mode.PASSPHRASE) {
                        R.string.generator_error_wordlist_unavailable
                    } else {
                        R.string.generator_error_no_options
                    },
                )
            }
        }
    }

    fun setMode(value: GeneratorUiState.Mode) = edit { copy(mode = value) }
    fun onLengthChanged(value: Int) = edit { copy(length = value.coerceIn(8, 64)) }
    fun onUppercaseToggled(value: Boolean) = edit { copy(uppercase = value) }
    fun onLowercaseToggled(value: Boolean) = edit { copy(lowercase = value) }
    fun onDigitsToggled(value: Boolean) = edit { copy(digits = value) }
    fun onSymbolsToggled(value: Boolean) = edit { copy(symbols = value) }
    fun onNoAmbiguousToggled(value: Boolean) = edit { copy(noAmbiguous = value) }
    fun onWordCountChanged(value: Int) = edit { copy(wordCount = value.coerceIn(3, 10)) }
    fun onSeparatorChanged(value: String) = edit {
        copy(separator = value.takeWholeCodePoints(MAX_SEPARATOR_LENGTH))
    }
    fun onCapitalizeToggled(value: Boolean) = edit { copy(capitalize = value) }
    fun onIncludeNumberToggled(value: Boolean) = edit { copy(includeNumber = value) }

    /**
     * Applies a settings change and immediately produces a new value.
     *
     * It used to blank the output instead, which meant every adjustment emptied the one part of
     * the screen you were adjusting *for*, and the entropy readout only ever described a password
     * you had already stopped looking at. Regenerating turns the length slider and the character
     * classes into a live feedback loop against the bit count. Generation is a handful of CSPRNG
     * draws and costs nothing, so doing it per keystroke is fine.
     *
     * But only when something changed. Tapping the mode that is already selected, or the slider at
     * the value it already has, used to replace the password on screen with no change of setting and
     * no announcement - after the old one had been copied and pasted into a sign-up form, so
     * "Save as a new login" filed a password that opened nothing (2026-10-02). A value on screen
     * always belongs to the settings shown; only an empty screen (after a lock, or a failed draw)
     * still wants a draw from unchanged settings.
     */
    private fun edit(transform: GeneratorUiState.() -> GeneratorUiState) {
        val before = _state.value
        if (before.generatedValue != null && before.transform() == before) return
        _state.update { it.transform().copy(error = null) }
        generate()
    }

    private fun Boolean.minimum(): Int = if (this) 1 else 0

    private companion object {
        const val MAX_SEPARATOR_LENGTH = 2
    }
}

/**
 * [String.take], except it never cuts a surrogate pair in half.
 *
 * A separator that already holds one character plus an emoji typed from the IME's emoji picker
 * (a UTF-16 surrogate pair) lands the cap exactly between that emoji's two halves — `take` keeps
 * only the lone high surrogate, which is not valid text on its own, and `PassphraseConfig`'s own
 * `require(separator.length <= 2)` (`:core:generator`'s `PasswordGenerator.kt`) does not catch it:
 * a truncated `'X'` + lone-high-surrogate string is still length 2. The corrupted separator then
 * joins every word of every generated passphrase from that point on.
 *
 * Same defect, same fix, as [com.localkeep.feature.itemedit.rememberSuggestedTitle]'s private
 * `takeWholeCodePoints` (`TitleSuggestion.kt`) and `lkMonogram`'s (`core/ui`'s `LkColors.kt`) — both
 * file-private, so this is a local copy rather than a shared dependency across modules.
 */
private fun String.takeWholeCodePoints(max: Int): String {
    if (length <= max) return this
    return substring(0, if (max > 0 && this[max - 1].isHighSurrogate()) max - 1 else max)
}

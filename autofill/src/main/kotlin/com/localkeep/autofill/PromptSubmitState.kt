package com.localkeep.autofill

import androidx.annotation.StringRes

/**
 * Idle/pending state for [MasterPasswordPrompt]'s submit button.
 *
 * Kept as a resource id rather than a resolved string, the same reason [AuthPromptText] is: this
 * module's unit tests run on the plain JVM with no Robolectric dependency, so nothing here can
 * call `Context.getString`. Split out of [MasterPasswordPrompt] so the pending transition — shown
 * during `VaultLockManager.unlock`'s multi-second Argon2id derivation, which previously left the
 * button merely disabled with no explanation — is unit-testable without a `View`.
 */
internal class PromptSubmitState {
    var isPending: Boolean = false
        private set

    @get:StringRes
    val labelRes: Int
        get() = if (isPending) {
            R.string.autofill_password_prompt_unlocking
        } else {
            R.string.autofill_password_prompt_continue
        }

    /** Enters the pending state — call right before the key derivation starts. */
    fun beginSubmit() {
        isPending = true
    }

    /** Returns to idle, e.g. after a failed unlock attempt. */
    fun reset() {
        isPending = false
    }
}

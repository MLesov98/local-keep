package com.localkeep.autofill

import android.content.Context
import com.localkeep.core.vault.UnlockResult

/**
 * What one attempt at the autofill password gate ended as.
 *
 * It used to be a `Boolean`. `runCatching { … }.getOrDefault(false)` collapsed a throttled attempt,
 * an unreadable vault file and a lock landing inside Argon2 into the same `false`, which the prompt
 * rendered as "Couldn't authenticate" — the wrong-password string. `UnlockResult` has carried this
 * distinction since the unlock screen learned it; this gate is where it was being thrown away, and
 * it is the one people meet inside another app, with no vault UI on screen to contradict it.
 */
internal sealed interface AuthOutcome {
    data object Success : AuthOutcome

    /** The backoff is still running; [retryAfterMs] is how much of it is left. */
    data class Throttled(val retryAfterMs: Long) : AuthOutcome

    data class Failed(val result: UnlockResult) : AuthOutcome
}

/**
 * What [authPromptMessage] chose to say, before it is resolved to text.
 *
 * A string resource id rather than a resolved `String` — same rule `GeneratorViewModel.error`
 * follows: the mapping that picks *which* message is the thing worth testing, and testing it
 * should not require a `Context`. [Throttled] carries the second count both as the plural
 * selector and the value substituted into it, same as `pluralStringResource`.
 */
internal sealed interface AuthPromptText {
    data object None : AuthPromptText

    data class Plain(@androidx.annotation.StringRes val resId: Int) : AuthPromptText

    data class Throttled(val seconds: Int) : AuthPromptText
}

/** Resolves an [AuthPromptText] to the text `MasterPasswordPrompt.showError` displays. */
internal fun AuthPromptText.resolve(context: Context): String = when (this) {
    is AuthPromptText.None -> ""
    is AuthPromptText.Plain -> context.getString(resId)
    is AuthPromptText.Throttled ->
        context.resources.getQuantityString(R.plurals.autofill_auth_throttled, seconds, seconds)
}

/**
 * The message the user sees, in the wording the unlock screen already uses.
 *
 * Nothing here is derived from an exception's message, a path or a URI. A gate that renders
 * `e.message` inside another app's window is a gate that can be made to say anything.
 *
 * Top-level rather than a method on the activity so it can be tested — the mapping is the whole
 * defect, and a rule that only holds inside an `Activity` cannot be held to.
 */
internal fun authPromptMessage(outcome: AuthOutcome): AuthPromptText = when (outcome) {
    is AuthOutcome.Success -> AuthPromptText.None
    is AuthOutcome.Throttled ->
        AuthPromptText.Throttled(((outcome.retryAfterMs + 999) / 1_000).toInt())
    is AuthOutcome.Failed -> when (outcome.result) {
        UnlockResult.VAULT_UNREADABLE ->
            AuthPromptText.Plain(R.string.autofill_auth_error_vault_unreadable)
        UnlockResult.VAULT_FROM_NEWER_VERSION ->
            AuthPromptText.Plain(R.string.autofill_auth_error_vault_newer_version)
        UnlockResult.INTERRUPTED -> AuthPromptText.Plain(R.string.autofill_auth_error_interrupted)
        // Unreachable from this gate today, and deliberately kept anyway (2026-09-13). Every
        // producer of BIOMETRIC_UNAVAILABLE sits inside `VaultLockManager.unlockWithBiometric`,
        // which `AuthActivity` never calls — see the note there for why this gate is
        // master-password-only while `CredentialActivity` raises a real prompt. The arm stays
        // mapped to its own string rather than folded into the group below, because the group
        // below answers with the wrong-password wording: if biometric unlock ever does reach this
        // gate, the silent failure mode would be this file accusing the user of mistyping, which
        // is the exact bug its own comments were written to stop.
        UnlockResult.BIOMETRIC_UNAVAILABLE ->
            AuthPromptText.Plain(R.string.autofill_auth_error_biometric_unavailable)
        UnlockResult.VAULT_NEEDS_MORE_MEMORY ->
            AuthPromptText.Plain(R.string.autofill_auth_error_needs_more_memory)
        // The vault fits this device; the process did not. Naming the wrong remedy here is worse
        // than in the app — the user is mid-login in someone else's app and will not go hunting.
        UnlockResult.VAULT_MEMORY_TRANSIENT ->
            AuthPromptText.Plain(R.string.autofill_auth_error_memory_transient)
        UnlockResult.WRONG_CREDENTIAL ->
            AuthPromptText.Plain(R.string.autofill_auth_error_wrong_password)
        // Cannot reach a `Failed`: these three are the ways an unlock *succeeded* or found the
        // vault already open. Listed rather than swept into an `else` on purpose — the `else` that
        // used to be here read "anything I have not thought about is a mistyped password", and it
        // silently claimed exactly that about VAULT_NEEDS_MORE_MEMORY the moment that case was
        // added. An exhaustive `when` makes the next addition a build failure instead.
        UnlockResult.REAL,
        UnlockResult.DURESS,
        UnlockResult.ALREADY_UNLOCKED,
        -> AuthPromptText.Plain(R.string.autofill_auth_error_wrong_password)
    }
}

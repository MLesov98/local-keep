package com.localkeep.credential

import android.content.Context
import androidx.annotation.StringRes
import com.localkeep.core.vault.UnlockResult

/** What one attempt at the in-ceremony master-password unlock ended as. */
internal sealed interface CredentialUnlockOutcome {
    data object Success : CredentialUnlockOutcome

    /** The knowledge-factor backoff is still running; [retryAfterMs] is how much of it is left. */
    data class Throttled(val retryAfterMs: Long) : CredentialUnlockOutcome

    data class Failed(val result: UnlockResult) : CredentialUnlockOutcome
}

/**
 * What [credentialUnlockMessage] chose to say, before it is resolved to text.
 *
 * A string resource id rather than a resolved `String`, so the mapping — which is the whole thing
 * worth testing — can be tested without a `Context`.
 */
internal sealed interface CredentialUnlockText {
    data object None : CredentialUnlockText

    data class Plain(@get:StringRes val resId: Int) : CredentialUnlockText

    data class Throttled(val seconds: Int) : CredentialUnlockText
}

/** Resolves to the text the dialog shows; [CredentialUnlockText.None] is no error at all. */
internal fun CredentialUnlockText.resolve(context: Context): String? = when (this) {
    is CredentialUnlockText.None -> null
    is CredentialUnlockText.Plain -> context.getString(resId)
    is CredentialUnlockText.Throttled ->
        context.resources.getQuantityString(R.plurals.credential_unlock_throttled, seconds, seconds)
}

/**
 * The message this gate shows, in the wording the unlock screen and the autofill gate already use.
 *
 * A deliberate twin of `AuthPromptMessage.authPromptMessage` in `:autofill`, which cannot be shared
 * because `:credential` does not depend on that module and must not: this one is reached from
 * Android's Credential Manager, that one from the Autofill framework, and neither module may pull
 * the other's manifest components in. The copy is the same for the same reason it was written —
 * "wrong password" for a throttled attempt, an unreadable vault file or a lock landing inside
 * Argon2 accuses the user of mistyping something they typed correctly.
 *
 * Nothing here is derived from an exception's message, a path or a URI. This dialog is raised over
 * another app's window, and one that renders `e.message` there can be made to say anything.
 */
internal fun credentialUnlockMessage(outcome: CredentialUnlockOutcome): CredentialUnlockText =
    when (outcome) {
        is CredentialUnlockOutcome.Success -> CredentialUnlockText.None
        is CredentialUnlockOutcome.Throttled ->
            CredentialUnlockText.Throttled(((outcome.retryAfterMs + 999) / 1_000).toInt())
        is CredentialUnlockOutcome.Failed -> when (outcome.result) {
            UnlockResult.VAULT_UNREADABLE ->
                CredentialUnlockText.Plain(R.string.credential_unlock_error_vault_unreadable)
            UnlockResult.VAULT_FROM_NEWER_VERSION ->
                CredentialUnlockText.Plain(R.string.credential_unlock_error_vault_newer_version)
            UnlockResult.INTERRUPTED ->
                CredentialUnlockText.Plain(R.string.credential_unlock_error_interrupted)
            // Unreachable from this dialog — it is what sends the ceremony here in the first place,
            // and this path calls `VaultLockManager.unlock`, which never produces it. Mapped to its
            // own string rather than folded into the wrong-password group below for the reason the
            // autofill twin gives: if it ever does arrive, the silent failure mode would be this
            // file accusing the user of mistyping, which is the bug the split exists to stop.
            UnlockResult.BIOMETRIC_UNAVAILABLE ->
                CredentialUnlockText.Plain(R.string.credential_unlock_error_biometric_unavailable)
            UnlockResult.VAULT_NEEDS_MORE_MEMORY ->
                CredentialUnlockText.Plain(R.string.credential_unlock_error_needs_more_memory)
            // The vault fits this device; the process did not. Naming the wrong remedy matters more
            // here than in the app — the user is mid-ceremony in someone else's app.
            UnlockResult.VAULT_MEMORY_TRANSIENT ->
                CredentialUnlockText.Plain(R.string.credential_unlock_error_memory_transient)
            UnlockResult.WRONG_CREDENTIAL ->
                CredentialUnlockText.Plain(R.string.credential_unlock_error_wrong_password)
            // Cannot reach a `Failed`: these three are the ways an unlock succeeded or found the
            // vault already open. Listed rather than swept into an `else`, so the next enum
            // addition is a build failure instead of a silent "you mistyped it".
            UnlockResult.REAL,
            UnlockResult.DURESS,
            UnlockResult.ALREADY_UNLOCKED,
            -> CredentialUnlockText.Plain(R.string.credential_unlock_error_wrong_password)
        }
    }

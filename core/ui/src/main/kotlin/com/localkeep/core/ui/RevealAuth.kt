package com.localkeep.core.ui

import android.content.Context
import android.os.Build
import androidx.annotation.MainThread
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Re-authentication gate for revealing a high-value secret (UI/UX spec §5.2: card CVV/PIN, TOTP
 * secret, and anything on an item the user marked "reprompt").
 *
 * Distinct from [com.localkeep.core.crypto.BiometricUnlock], which is `CryptoObject`-bound because
 * it unwraps the DEK. Here the vault is already open and no key material is involved — this only
 * answers "is the person holding the phone still the owner?", so it accepts the device credential
 * (PIN/pattern/password) alongside a Class 3 biometric. That keeps the guarantee real on devices
 * with no fingerprint sensor instead of silently degrading to no protection at all.
 *
 * On API 28 and 29 that pairing is not available — androidx.biometric rejects it outright — so
 * there the gate is a Class 3 biometric alone and a sensor-less phone gets no reveal gate rather
 * than a weaker one. See [allowedAuthenticators].
 */
object RevealAuth {

    private const val ALLOWED = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /**
     * What to ask the platform for on the API this phone is running.
     *
     * androidx.biometric refuses `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` on API 28 and 29 —
     * `AuthenticatorUtils.isSupportedCombination` answers false there, so `canAuthenticate` returns
     * `BIOMETRIC_ERROR_UNSUPPORTED` without consulting the device and `PromptInfo.Builder.build()`
     * throws. `minSdk` is 28, so asking for it unconditionally made every reveal- and copy-gated
     * secret unreachable on two supported releases, on phones that could in fact challenge the user.
     *
     * The fallback is Class 3 alone, not `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`. That is the other
     * combination 28/29 accepts and it is the one that would keep the PIN fallback, but it admits a
     * Class 2 biometric where this gate demands Class 3 — a relaxation bought to preserve a
     * convenience, which is the wrong trade for a reveal gate. The cost is stated honestly in the
     * class KDoc above.
     *
     * Both [isAvailable] and [promptInfo] read this, so the query and the prompt cannot disagree.
     */
    internal fun allowedAuthenticators(sdkInt: Int = Build.VERSION.SDK_INT): Int =
        if (sdkInt < Build.VERSION_CODES.R) BIOMETRIC_STRONG else ALLOWED

    /** True when the device can actually challenge the user (enrolled biometric or a device lock). */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(allowedAuthenticators()) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * The prompt [authenticate] shows, built from [allowedAuthenticators] so the two halves of the
     * gate stay in step.
     *
     * `build()` has a negative-button rule in both directions: no text is permitted when
     * `DEVICE_CREDENTIAL` is allowed (the credential screen supplies its own way out), and text is
     * *required* when it is not, or it throws "Negative text must be set and non-empty."
     */
    internal fun promptInfo(
        title: String,
        subtitle: String,
        negativeButtonText: String,
    ): BiometricPrompt.PromptInfo {
        val allowed = allowedAuthenticators()
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowed)
        if (allowed and DEVICE_CREDENTIAL == 0) builder.setNegativeButtonText(negativeButtonText)
        return builder.build()
    }

    /** The reason a reveal challenge ended. No UI text crosses this boundary. */
    sealed interface RevealOutcome {
        data object Success : RevealOutcome
        data object UserCancelled : RevealOutcome
        data object Denied : RevealOutcome
        data object Unavailable : RevealOutcome
        data class Error(val code: Int) : RevealOutcome
    }

    /**
     * Shows the prompt and suspends until the user passes, cancels, or fails. Callers must treat
     * every outcome other than [RevealOutcome.Success] as "do not reveal".
     */
    @MainThread
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
    ): RevealOutcome = suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(RevealOutcome.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(revealOutcomeFor(errorCode))
                }

                // onAuthenticationFailed (a non-matching finger) is deliberately not terminal —
                // the prompt stays up and lets the user retry until it errors or succeeds.
            },
        )
        val info = promptInfo(title, subtitle, activity.getString(R.string.core_ui_dialog_cancel))
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(info)
    }
}

/**
 * Which outcome a biometric error code means, pulled out of the callback so it can be tested.
 *
 * `ERROR_CANCELED` used to sit in the [RevealAuth.RevealOutcome.UserCancelled] arm, and it is not a
 * user action: androidx documents it as "the operation was canceled because the biometric sensor is
 * unavailable ... when the user is switched, the device is locked, or another pending operation
 * prevents it", and `BiometricFragment` only forwards it for internal or client-initiated
 * cancellation, above the comment that user-initiated cancellations are already handled elsewhere.
 * A user who backs out is delivered `ERROR_USER_CANCELED` or `ERROR_NEGATIVE_BUTTON` instead — the
 * same two codes, and only those two, that [com.localkeep.feature.unlock] treats as a choice.
 *
 * Nothing was revealed either way; what it cost was the truth of one sentence. The file viewer is
 * the one surface that tells the two apart, and it said "Authentication was cancelled" for a sensor
 * that had gone away underneath the user. Found 2026-08-30.
 */
internal fun revealOutcomeFor(errorCode: Int): RevealAuth.RevealOutcome = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    -> RevealAuth.RevealOutcome.UserCancelled
    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_HW_NOT_PRESENT,
    BiometricPrompt.ERROR_HW_UNAVAILABLE,
    -> RevealAuth.RevealOutcome.Unavailable
    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
    -> RevealAuth.RevealOutcome.Denied
    else -> RevealAuth.RevealOutcome.Error(errorCode)
}

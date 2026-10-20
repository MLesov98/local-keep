package com.localkeep.credential

import androidx.biometric.BiometricPrompt

/**
 * Why a verification attempt did not pass, in the words the calling app will show.
 *
 * Everything here used to collapse into one boolean and one message. "You cancelled the
 * fingerprint prompt" and "the Keystore key this vault's biometric unlock is wrapped under no
 * longer exists" both arrived at the other app as "user verification failed", so the one case
 * the user can actually do something about was told to try again — which cannot work, because
 * nothing about a retry re-creates that key. [com.localkeep.core.vault.UnlockResult.BIOMETRIC_UNAVAILABLE]
 * exists in the vault layer for exactly this distinction ("says nothing about the master password,
 * and must never be reported as if it did"); this surface was throwing it away on arrival.
 *
 * Top-level rather than nested in [CredentialActivity] so the routing below can be tested on the
 * JVM: the activity is API 34 and needs Credential Manager intents to construct.
 */
internal enum class Verification(val message: String?) {
    OK(null),

    /** The prompt failed for a reason other than the user declining it. Retrying may work. */
    FAILED("user verification failed"),

    /**
     * The device key could not be used at all: biometric unlock was never enabled here, or a
     * fingerprint enrolment invalidated the key. Retrying cannot help — opening LocalKeep and
     * unlocking with the master password is what re-establishes it.
     *
     * Also the state a panic lock leaves behind: it destroys the device-wrapped key outright, so
     * `DeviceKeyStore.load` returns null and this is what the calling app is told.
     */
    UNAVAILABLE(
        "biometric unlock is unavailable — open LocalKeep and unlock with your master password",
    ),

    /**
     * The verification itself was fine, but the vault did not end up open: a lock landed inside
     * the derivation ([com.localkeep.core.vault.UnlockResult.INTERRUPTED]), or the failed-attempt
     * throttle refused the attempt before it started. Neither is a verification failure, and the
     * Credential Manager API has its own answer for "try again later" — the calling app is handed
     * `CreateCredentialInterruptedException`/`GetCredentialInterruptedException` rather than being
     * told the user's finger did not match. 38-2.
     */
    INTERRUPTED(LOCKED_MESSAGE),

    /**
     * The user declined: Back or a tap outside a prompt, the dismiss button on the master-password
     * dialog. Reported as the API's cancellation, which a relying party treats as a choice rather
     * than as an error to retry or report.
     */
    CANCELLED("user cancelled"),

    /**
     * "Use master password" on the locked-vault prompt. Routing only: [openLockedVault] answers it
     * with the master-password dialog, so it never reaches a calling app.
     */
    PASSWORD_REQUESTED(null),
}

/**
 * Opens a locked vault from inside the ceremony: the `CryptoObject`-bound Class 3 biometric
 * first, the master password when that cannot run at all.
 *
 * The biometric is unchanged and still tried first — it is the fast path, and on a passkey request
 * it is also the user verification the assertion is signed behind. [Verification.UNAVAILABLE] is
 * the one outcome it returns that a retry can never fix and a password always can: there is no
 * device-wrapped key (biometric unlock was never enabled here, or a panic lock destroyed it) or an
 * enrolment change invalidated it. That used to end the request — the calling app was told to go
 * and open LocalKeep, which means abandoning the ceremony and starting over, while the Autofill side
 * has offered an in-flow master password all along (2C-F14). [Verification.FAILED] and
 * [Verification.CANCELLED] are deliberately not retried this way: a failed or dismissed prompt is an
 * answer, not an unavailable mechanism.
 *
 * [Verification.PASSWORD_REQUESTED] is the other way in, and the one the prompt itself offers: its
 * negative button reads "Use master password". It used to land in the same catch as every other
 * prompt error and end the request as "user verification failed", so the button named the one
 * route a user without a usable finger had and then refused it (2026-10-04).
 *
 * Unlocking here relaxes nothing. It verifies the same master password against the same slot,
 * shares the same knowledge-factor backoff, and re-enters the duress-aware session that keeps a
 * concealed item out of every entry list — the same argument `autofillGateMustUnlock` makes.
 */
internal suspend fun openLockedVault(
    biometric: suspend () -> Verification,
    password: suspend () -> Verification,
): Verification {
    val outcome = biometric()
    return when (outcome) {
        Verification.UNAVAILABLE, Verification.PASSWORD_REQUESTED -> password()
        else -> outcome
    }
}

/**
 * What a biometric prompt that ended without a result means, by its error code.
 *
 * [passwordOffered] is whether the prompt's negative button reads "Use master password" — true on
 * the locked-vault unlock, false on the freshness check, which allows the device credential and
 * so has no negative button of its own.
 *
 * The two user choices are the ones `isUserChoice` in `core/crypto` names for every other prompt in
 * the app. `ERROR_CANCELED` is left out for its reason: androidx documents it as the sensor going
 * away — another operation, a user switch — not as something the person did, so it stays a
 * failure. Everything else (lockout, hardware, timeout) is a failure as before.
 */
internal fun biometricRefusal(code: Int, passwordOffered: Boolean): Verification = when (code) {
    BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
        if (passwordOffered) Verification.PASSWORD_REQUESTED else Verification.CANCELLED
    BiometricPrompt.ERROR_USER_CANCELED -> Verification.CANCELLED
    else -> Verification.FAILED
}


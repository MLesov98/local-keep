package com.localkeep.core.crypto

import androidx.annotation.MainThread
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class BiometricAuthException(
    message: String,
    val code: Int,
) : Exception(message)

/**
 * Whether this failure was the user choosing another way in rather than anything going wrong.
 *
 * [BiometricUnlock.authenticate] resumes with a [BiometricAuthException] for *every* non-success
 * code, including the two that mean the person in front of the phone declined: the prompt's
 * negative button, and dismissing the prompt. Neither is a failure, and reporting them as one
 * paints a coral banner over a deliberate, offered choice — which `LkColors`' own test for coral
 * ("is the user's data less safe than they believe") does not describe.
 *
 * `ERROR_CANCELED` is deliberately absent, for the reason `core/ui`'s `revealOutcomeFor` states at
 * length: androidx documents it as the sensor becoming unavailable — a user switch, the device
 * locking, another pending operation — not as something the user did. It stays reportable.
 *
 * Lives here, beside the exception it classifies, because the same two codes are read on three
 * screens and a fourth private copy of the test is how the three would drift apart.
 */
fun BiometricAuthException.isUserChoice(): Boolean =
    code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_USER_CANCELED

/**
 * Creates CryptoObject-bound ciphers and runs a Class-3-only biometric prompt.
 *
 * `open`, with [decryptCipher] the only open member, for one test:
 * `UnlockViewModelBiometricInvalidationTest` in `:feature:unlock`. What it covers is how the unlock
 * screen answers a biometric key that is gone for good — and the two ways that surfaces are both
 * thrown from inside [decryptCipher]: `KeyPermanentlyInvalidatedException` from `Cipher.init` after
 * an enrolment change, `IllegalStateException` from [KeystoreManager.getOrCreateBioKey] once the
 * device has no lock or no strong biometric. Neither can be produced on demand on a real device:
 * the first needs a fingerprint enrolled or removed, the second needs the lock screen or every
 * enrolment taken away — and the instrumented suite *requires* the emulator's PIN. The test used to
 * rely on the emulator happening to have no fingerprint; on one that had one, a real prompt opened
 * and waited for a finger (2026-10-04). Nothing in the app subclasses this.
 */
open class BiometricUnlock(
    private val keystoreManager: KeystoreManager,
) {
    fun encryptCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.ENCRYPT_MODE, keystoreManager.getOrCreateBioKey())
    }

    open fun decryptCipher(iv: ByteArray): Cipher {
        require(iv.size == CryptoConstants.AEAD_NONCE_LEN) { "biometric wrap IV must be 96 bits" }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                keystoreManager.getOrCreateBioKey(),
                GCMParameterSpec(CryptoConstants.AEAD_TAG_BITS, iv),
            )
        }
    }

    @MainThread
    suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeButtonText: String,
    ): Cipher {
        require(title.isNotBlank()) { "biometric prompt title must not be blank" }
        require(negativeButtonText.isNotBlank()) { "biometric prompt negative button text must not be blank" }
        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (!continuation.isActive) {
                            return
                        }
                        val authenticatedCipher = result.cryptoObject?.cipher
                        if (authenticatedCipher == null) {
                            continuation.resumeWithException(
                                BiometricAuthException("biometric result contained no cipher", -1),
                            )
                        } else {
                            continuation.resume(authenticatedCipher)
                        }
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                BiometricAuthException(errString.toString(), errorCode),
                            )
                        }
                    }
                },
            )
            continuation.invokeOnCancellation {
                executor.execute(prompt::cancelAuthentication)
            }
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply {
                    if (subtitle.isNotBlank()) {
                        setSubtitle(subtitle)
                    }
                }
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText(negativeButtonText)
                .build()
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

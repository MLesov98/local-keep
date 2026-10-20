package com.localkeep.core.crypto

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.biometric.BiometricManager
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Owns AndroidKeyStore AES keys; wrapped DEK bytes remain outside this class. */
class KeystoreManager(context: Context) {
    private val context = context.applicationContext ?: context
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun isDeviceSecure(): Boolean =
        checkNotNull(context.getSystemService(KeyguardManager::class.java)) {
            "KeyguardManager unavailable"
        }.isDeviceSecure

    /**
     * `BiometricManager`'s own verdict on BIOMETRIC_STRONG, unreduced.
     *
     * [isStrongBiometricAvailable] collapses this to a boolean, which is the right shape for every
     * caller that has to *decide* something. It is the wrong shape for the one that has to
     * *explain* something: "unavailable" covers a phone with no sensor, a phone whose owner has
     * simply not enrolled a fingerprint yet, and a sensor that is busy this second, and only the
     * first of those is a property of the device. Settings told all three users the same untrue
     * sentence and offered none of them the one action that would help.
     */
    fun strongBiometricStatus(): Int =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
        )

    fun isStrongBiometricAvailable(): Boolean =
        strongBiometricStatus() == BiometricManager.BIOMETRIC_SUCCESS

    @Synchronized
    fun getOrCreateBioKey(): SecretKey {
        check(isDeviceSecure()) { "device lock required for biometric key" }
        check(isStrongBiometricAvailable()) {
            "BIOMETRIC_STRONG unavailable; use master password"
        }
        existingKey(BIO_DEK_ALIAS)?.let { logHardwareBacking(BIO_DEK_ALIAS, it); return it }
        return generateKey(
            alias = BIO_DEK_ALIAS,
            authentication = Authentication.PerUseBiometric,
        ).also { logHardwareBacking(BIO_DEK_ALIAS, it) }
    }

    @Synchronized
    fun getOrCreateSensitiveOpKey(
        alias: String = SENSITIVE_OP_ALIAS,
        validitySeconds: Int = DEFAULT_SENSITIVE_OP_VALIDITY_SECONDS,
    ): SecretKey {
        require(alias.isNotBlank()) { "key alias must not be blank" }
        require(alias != BIO_DEK_ALIAS) { "sensitive-op alias must differ from biometric alias" }
        require(validitySeconds > 0) { "authentication validity must be positive" }
        check(isDeviceSecure()) { "device lock required for sensitive-operation key" }
        existingKey(alias)?.let { logHardwareBacking(alias, it); return it }
        return generateKey(
            alias = alias,
            authentication = Authentication.Timed(validitySeconds),
        ).also { logHardwareBacking(alias, it) }
    }

    /** Non-secret KeyInfo disclosure (§D — attestation via KeyInfo only, no cert chain). Surfaced
     *  at first use of each bio/sensitive-op key so hardware-backing status is discoverable in logs. */
    private fun logHardwareBacking(alias: String, key: SecretKey) {
        runCatching { HardwareBacking.describe(key) }
            .onSuccess { Log.i(LOG_TAG, "KeyInfo[$alias]: $it") }
    }

    @Synchronized
    fun deleteKey(alias: String) {
        require(alias.isNotBlank()) { "key alias must not be blank" }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun existingKey(alias: String): SecretKey? =
        keyStore.getKey(alias, null) as? SecretKey

    private fun generateKey(
        alias: String,
        authentication: Authentication,
    ): SecretKey {
        val supportsStrongBox =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (supportsStrongBox) {
            try {
                return generateKey(buildSpec(alias, authentication, strongBox = true))
            } catch (_: StrongBoxUnavailableException) {
                // Advertised StrongBox can be temporarily unavailable or exhausted; use TEE.
            }
        }
        return generateKey(buildSpec(alias, authentication, strongBox = false))
    }

    private fun generateKey(spec: KeyGenParameterSpec): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }

    private fun buildSpec(
        alias: String,
        authentication: Authentication,
        strongBox: Boolean,
    ): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(CryptoConstants.KEY_LEN * Byte.SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)

        when (authentication) {
            Authentication.PerUseBiometric -> {
                builder.setInvalidatedByBiometricEnrollment(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    builder.setUserAuthenticationValidityDurationSeconds(-1)
                }
            }

            is Authentication.Timed -> {
                builder.setInvalidatedByBiometricEnrollment(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setUserAuthenticationParameters(
                        authentication.validitySeconds,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or
                            KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    builder.setUserAuthenticationValidityDurationSeconds(
                        authentication.validitySeconds,
                    )
                }
            }
        }

        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    private sealed interface Authentication {
        data object PerUseBiometric : Authentication
        data class Timed(val validitySeconds: Int) : Authentication
    }

    companion object {
        const val BIO_DEK_ALIAS: String = "localkeep.bio.dek"
        const val SENSITIVE_OP_ALIAS: String = "localkeep.sensitive.op"
        const val DEFAULT_SENSITIVE_OP_VALIDITY_SECONDS: Int = 20
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val LOG_TAG = "LocalKeepCrypto"
    }
}

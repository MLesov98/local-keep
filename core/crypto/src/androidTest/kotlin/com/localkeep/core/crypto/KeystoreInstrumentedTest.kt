package com.localkeep.core.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

@RunWith(AndroidJUnit4::class)
class KeystoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keyStore = KeystoreManager(context)

    @After
    fun cleanup() {
        keyStore.deleteKey(KeystoreManager.BIO_DEK_ALIAS)
        keyStore.deleteKey(KeystoreManager.SENSITIVE_OP_ALIAS)
        keyStore.deleteKey(CUSTOM_ALIAS)
    }

    @Test
    fun insecureDevice_refusesAuthenticationBoundKeys() {
        assumeFalse("requires emulator/device without secure lock", keyStore.isDeviceSecure())

        assertFailsWith<IllegalStateException> { keyStore.getOrCreateBioKey() }
        assertFailsWith<IllegalStateException> { keyStore.getOrCreateSensitiveOpKey() }
    }

    @Test
    fun strongBiometricGate_matchesKeyCreation() {
        val eligible = keyStore.isDeviceSecure() && keyStore.isStrongBiometricAvailable()

        if (eligible) {
            assertNotNull(keyStore.getOrCreateBioKey())
        } else {
            assertFailsWith<IllegalStateException> { keyStore.getOrCreateBioKey() }
        }
    }

    @Test
    fun biometricKey_hasPerUseStrongPolicy() {
        assumeTrue("requires secure lock", keyStore.isDeviceSecure())
        assumeTrue("requires enrolled BIOMETRIC_STRONG", keyStore.isStrongBiometricAvailable())

        val key = keyStore.getOrCreateBioKey()
        val info = HardwareBacking.keyInfo(key)

        assertCommonAesPolicy(info)
        assertTrue(info.isInvalidatedByBiometricEnrollment)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(0, info.userAuthenticationValidityDurationSeconds)
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, info.userAuthenticationType)
        } else {
            @Suppress("DEPRECATION")
            assertEquals(-1, info.userAuthenticationValidityDurationSeconds)
        }

        val cipher = BiometricUnlock(keyStore).encryptCipher()
        assertEquals(CryptoConstants.AEAD_NONCE_LEN, cipher.iv.size)
    }

    @Test
    fun biometricPrompt_authenticatesCryptoObject() {
        assumeTrue(
            "interactive biometric smoke test disabled",
            InstrumentationRegistry.getArguments().getString(INTERACTIVE_ARGUMENT) == "true",
        )
        assumeTrue("requires secure lock", keyStore.isDeviceSecure())
        assumeTrue("requires enrolled BIOMETRIC_STRONG", keyStore.isStrongBiometricAvailable())
        val biometricUnlock = BiometricUnlock(keyStore)
        val cipher = biometricUnlock.encryptCipher()
        val result = AtomicReference<Result<Cipher>>()
        val completed = CountDownLatch(1)

        ActivityScenario.launch(BiometricTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                suspend {
                    biometricUnlock.authenticate(
                        activity = activity,
                        cipher = cipher,
                        title = "LocalKeep test",
                        subtitle = "Authenticate test cipher",
                        negativeButtonText = "Use master password",
                    )
                }.startCoroutine(
                    object : Continuation<Cipher> {
                        override val context = EmptyCoroutineContext

                        override fun resumeWith(resumeResult: Result<Cipher>) {
                            result.set(resumeResult)
                            completed.countDown()
                        }
                    },
                )
            }

            assertTrue(completed.await(30, TimeUnit.SECONDS), "biometric prompt timed out")
        }

        val authenticatedCipher = checkNotNull(result.get()).getOrThrow()
        assertEquals(
            CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
            authenticatedCipher.doFinal(ByteArray(CryptoConstants.KEY_LEN)).size,
        )
    }

    @Test
    fun sensitiveOperationKey_hasTimedPolicyAndStrongBoxFallback() {
        assumeTrue("requires secure lock", keyStore.isDeviceSecure())
        val validitySeconds = 20

        val key = keyStore.getOrCreateSensitiveOpKey(
            alias = CUSTOM_ALIAS,
            validitySeconds = validitySeconds,
        )
        val info = HardwareBacking.keyInfo(key)

        assertCommonAesPolicy(info)
        assertFalse(info.isInvalidatedByBiometricEnrollment)
        @Suppress("DEPRECATION")
        assertEquals(validitySeconds, info.userAuthenticationValidityDurationSeconds)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                info.userAuthenticationType,
            )
        }
        assertContains(HardwareBacking.describe(key), "hardwareBacked=")

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            assertNotNull(key)
        }
    }

    @Test
    fun existingAlias_isReusedAndDeleteIsIdempotent() {
        assumeTrue("requires secure lock", keyStore.isDeviceSecure())

        val first = keyStore.getOrCreateSensitiveOpKey()
        val second = keyStore.getOrCreateSensitiveOpKey()

        assertEquals(
            HardwareBacking.keyInfo(first).keystoreAlias,
            HardwareBacking.keyInfo(second).keystoreAlias,
        )
        keyStore.deleteKey(KeystoreManager.SENSITIVE_OP_ALIAS)
        keyStore.deleteKey(KeystoreManager.SENSITIVE_OP_ALIAS)
    }

    @Test
    fun publicInputBounds_areEnforcedBeforeKeystoreUse() {
        assertFailsWith<IllegalArgumentException> {
            keyStore.getOrCreateSensitiveOpKey(alias = "")
        }
        assertFailsWith<IllegalArgumentException> {
            keyStore.getOrCreateSensitiveOpKey(alias = KeystoreManager.BIO_DEK_ALIAS)
        }
        assertFailsWith<IllegalArgumentException> {
            keyStore.getOrCreateSensitiveOpKey(validitySeconds = 0)
        }
        assertFailsWith<IllegalArgumentException> { keyStore.deleteKey("") }
        assertFailsWith<IllegalArgumentException> {
            BiometricUnlock(keyStore).decryptCipher(ByteArray(11))
        }
    }

    @Suppress("DEPRECATION")
    private fun assertCommonAesPolicy(info: KeyInfo) {
        assertEquals(CryptoConstants.KEY_LEN * Byte.SIZE_BITS, info.keySize)
        assertTrue(info.isUserAuthenticationRequired)
        assertTrue(info.blockModes.contentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM)))
        assertTrue(
            info.encryptionPaddings.contentEquals(
                arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE),
            ),
        )
        assertEquals(
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            info.purposes,
        )
    }

    private companion object {
        const val CUSTOM_ALIAS = "localkeep.test.sensitive"
        const val INTERACTIVE_ARGUMENT = "runBiometricPrompt"
    }
}

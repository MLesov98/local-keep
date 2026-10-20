package com.localkeep.core.vault

import android.content.Context
import com.localkeep.core.crypto.Argon2Calibrator
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One-shot vault creation with overwrite protection and complete key cleanup. */
object VaultInitializer {
    private val creationMutex = Mutex()

    /** Consumes and wipes [password]. */
    suspend fun create(
        context: Context,
        password: CharArray,
    ): RecoveryKitData = try {
        creationMutex.withLock {
            val appContext = context.applicationContext ?: context
            check(!VaultLoader.hasVaultFiles(appContext)) {
                "vault storage already exists"
            }
            var dek: ByteArray? = null
            var salt: ByteArray? = null
            var kek: ByteArray? = null
            try {
                val kdfParams = withContext(Dispatchers.Default) {
                    Argon2Calibrator.calibrate(targetMillisPerSlot = 250L)
                }
                dek = EnvelopeCrypto.generateDek()
                salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
                kek = withContext(Dispatchers.Default) {
                    EnvelopeCrypto.deriveKek(password, salt, kdfParams)
                }
                val fileBytes = VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = kdfParams,
                    wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
                    commitment = EnvelopeCrypto.slotCommitment(dek),
                )
                val recoveryKit = RecoveryKit.generate(dek)
                // The kit's wrapped key goes into the container as the vault is written. Without
                // this the words handed to the user on the next screen would open nothing — which
                // is exactly what they did before the container had somewhere to keep it.
                val withRecovery = VaultFileCodec.withRecoveryBlock(
                    fileBytes,
                    recoveryKit.wrappedDek,
                )
                withContext(Dispatchers.IO) {
                    check(!VaultLoader.hasVaultFiles(appContext)) {
                        "vault storage appeared during creation"
                    }
                    AtomicVaultWriter.write(appContext, withRecovery)
                    // A vault that has just been created has never been exported anywhere, so any
                    // export clock still in the prefs came from another device via Auto Backup —
                    // see LocalKeepSettings.forgetExportReminderClock.
                    LocalKeepSettings.forgetExportReminderClock(appContext)
                    // Same reasoning, same route: a restored prefs file can carry the previous
                    // device's recovery_kit_saved, and this vault's kit is the one handed over on
                    // the next screen — nothing has been saved yet. Left standing, deferring the
                    // kit step would clear the Settings row's standing no-kit debt.
                    LocalKeepSettings.forgetRecoveryKitSaved(appContext)
                }
                recoveryKit
            } finally {
                Zeroizer.wipe(dek)
                Zeroizer.wipe(salt)
                Zeroizer.wipe(kek)
            }
        }
    } finally {
        Zeroizer.wipe(password)
    }

}

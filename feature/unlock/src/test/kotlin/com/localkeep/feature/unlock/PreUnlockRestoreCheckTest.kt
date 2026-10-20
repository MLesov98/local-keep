package com.localkeep.feature.unlock

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate that makes a pre-unlock restore safe: the user proves they can open the candidate before
 * anything is written.
 *
 * Real containers and real Argon2id here, at [KdfParams.FLOOR] so the derivations cost tenths of a
 * second rather than tens. Faking the crypto would leave the one claim this class makes — "the
 * password opens this file, payload and all" — untested.
 */
class PreUnlockRestoreCheckTest {

    /** What `Zeroizer.wipe(CharArray)` fills with. Built, never typed as a literal: repo law keeps
     * a raw NUL byte out of source files, and editing tools have written one into this codebase
     * three times. */
    private val NUL: Char = 0.toChar()

    private val password = "correct horse battery staple"
    private val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
    private val params = KdfParams.FLOOR

    private fun container(): ByteArray {
        val kek = EnvelopeCrypto.deriveKek(password.toCharArray(), salt, params)
        val dek = EnvelopeCrypto.generateDek()
        val header = VaultHeaderCodec.create(
            salt = salt,
            kdfParams = params,
            wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
            commitment = EnvelopeCrypto.slotCommitment(dek),
        )
        return VaultFileCodec.encrypt(
            sampleVault(),
            VaultSubkeys.encKey(dek),
            header,
            VaultFileCodec.revokedRecoveryBlock(),
        )
    }

    private fun sampleVault(): Vault {
        val timestamp = Instant.parse("2026-09-13T00:00:00Z")
        return Vault(
            items = listOf(
                VaultItem(
                    id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
                    title = "sample",
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    content = Note("secret note"),
                ),
            ),
            createdAt = timestamp,
            modifiedAt = timestamp,
        )
    }

    @Test
    fun theRightPasswordOpensTheBackup() {
        assertEquals(BackupCheck.OPENS, PreUnlockRestore.check(container(), password.toCharArray()))
    }

    @Test
    fun theWrongPasswordIsReportedAsAWrongPassword() {
        assertEquals(
            BackupCheck.WRONG_PASSWORD,
            PreUnlockRestore.check(container(), "not the password".toCharArray()),
        )
    }

    /**
     * The case the check exists for, and the one a key-only check gets wrong.
     *
     * A container whose ciphertext was damaged in place keeps a perfectly valid header and a
     * perfectly valid slot, so the DEK unwraps and the commitment matches: a check that stopped
     * there would call this backup good and restore it over the user's vault, and the vault they
     * ended up with would not open either. `VaultLockManager.SlotTrial.PayloadUndecryptable` splits
     * the same case out at unlock for the same reason.
     */
    @Test
    fun aRightPasswordAgainstADamagedPayloadIsUnreadableNotWrongPassword() {
        val bytes = container()
        val region = VaultHeaderCodec.decode(bytes).regions[0]
        bytes[region.offset + 1] = (bytes[region.offset + 1].toInt() xor 0xFF).toByte()

        assertEquals(BackupCheck.UNREADABLE, PreUnlockRestore.check(bytes, password.toCharArray()))
    }

    @Test
    fun somethingThatIsNotAContainerIsUnreadable() {
        assertEquals(
            BackupCheck.UNREADABLE,
            PreUnlockRestore.check(ByteArray(4_096) { it.toByte() }, password.toCharArray()),
        )
    }

    @Test
    fun anEmptyFileIsUnreadable() {
        assertEquals(BackupCheck.UNREADABLE, PreUnlockRestore.check(ByteArray(0), password.toCharArray()))
    }

    /**
     * The typed password is wiped on every path, right or wrong.
     *
     * A pre-unlock screen holds the master password of a vault that will not open, on a device
     * whoever is holding it can already reach — leaving it in a live `CharArray` after the check is
     * exactly the residue the rest of this codebase zeroes.
     */
    @Test
    fun theTypedPasswordIsWipedWhicheverWayTheCheckGoes() {
        val right = password.toCharArray()
        PreUnlockRestore.check(container(), right)
        assertTrue(right.all { it == NUL }, "a correct password was left in memory")

        val wrong = "not the password".toCharArray()
        PreUnlockRestore.check(container(), wrong)
        assertTrue(wrong.all { it == NUL }, "a wrong password was left in memory")

        val garbage = password.toCharArray()
        PreUnlockRestore.check(ByteArray(16), garbage)
        assertTrue(garbage.all { it == NUL }, "a password tried against junk was left in memory")
    }
}

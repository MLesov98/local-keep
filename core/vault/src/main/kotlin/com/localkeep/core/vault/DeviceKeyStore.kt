package com.localkeep.core.vault

import android.content.Context
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.WrappedDek
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Stores device-bound biometric-wrapped DEK bytes.
 *
 * ### This file is withheld from backup by an exclusion list, not by backup being off
 *
 * This line used to say app backup was off, pending the phase that would install rules excluding
 * this file. That phase landed, and the sentence became the opposite of the truth: `AndroidManifest`
 * ships `allowBackup="true"` with `fullBackupOnly`, a `backupAgent`, and both `dataExtractionRules`
 * and `fullBackupContent`. Auto Backup and device transfer are **on**.
 *
 * What keeps the device-wrapped DEK out of Google's backup set is therefore an *allowlist with
 * holes in it*, and the holes are named one filename at a time. Both XML rule files open with
 * `<include domain="file" path="."/>` — everything in `filesDir` is backed up unless something
 * excludes it — and `device_keys.bin` plus `device_keys.bin.tmp` are excluded by name in
 * `data_extraction_rules.xml` (both the `cloud-backup` and `device-transfer` blocks),
 * `full_backup_content.xml`, and `LocalKeepBackupAgent.EXCLUDED_PATHS`. `BackupInclusionSetTest`
 * fails the build when those three disagree.
 *
 * The consequence for anyone editing this file: **a new secret written to `filesDir` leaves on the
 * next cloud backup unless it is added to all three places.** The test catches adding it to one or
 * two of them; nothing catches adding it to none. `device_keys.bin.tmp` and
 * `vault.enc.bak.sweep.tmp` are both here because that gap was real twice — see the commentary on
 * `EXCLUDED_PATHS` for what each one leaked.
 */
object DeviceKeyStore {
    internal const val FILE_NAME: String = "device_keys.bin"
    internal const val TEMP_FILE_NAME: String = "device_keys.bin.tmp"
    private const val FILE_SIZE =
        CryptoConstants.AEAD_NONCE_LEN + CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN

    fun save(
        context: Context,
        wrappedDek: WrappedDek,
    ) {
        val temporary = File(context.filesDir, TEMP_FILE_NAME)
        try {
            require(wrappedDek.nonce.size == CryptoConstants.AEAD_NONCE_LEN) {
                "device-wrapped DEK nonce must be 12 bytes"
            }
            require(
                wrappedDek.ciphertext.size ==
                    CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
            ) { "device-wrapped DEK ciphertext must be 48 bytes" }

            FileOutputStream(temporary).use {
                it.write(wrappedDek.nonce)
                it.write(wrappedDek.ciphertext)
                it.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                File(context.filesDir, FILE_NAME).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    fun load(context: Context): WrappedDek? = try {
        val bytes = File(context.filesDir, FILE_NAME).readBytes()
        if (bytes.size != FILE_SIZE) {
            null
        } else {
            WrappedDek(
                nonce = bytes.copyOfRange(0, CryptoConstants.AEAD_NONCE_LEN),
                ciphertext = bytes.copyOfRange(CryptoConstants.AEAD_NONCE_LEN, FILE_SIZE),
            )
        }
    } catch (_: IOException) {
        null
    }

    fun delete(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}

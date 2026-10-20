package com.localkeep.core.vault

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Whole-file atomic writer retaining the prior committed vault as `.bak`. */
object AtomicVaultWriter {
    internal const val VAULT_FILE: String = "vault.enc"
    internal const val TEMP_FILE: String = "vault.enc.tmp"
    internal const val BACKUP_FILE: String = "vault.enc.bak"

    @Synchronized
    fun write(
        context: Context,
        bytes: ByteArray,
    ) {
        val directory = context.filesDir
        val temporary = File(directory, TEMP_FILE)
        val vault = File(directory, VAULT_FILE)
        val backup = File(directory, BACKUP_FILE)

        FileOutputStream(temporary).use {
            it.write(bytes)
            it.fd.sync()
        }

        if (vault.exists()) {
            Files.move(
                vault.toPath(),
                backup.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        Files.move(
            temporary.toPath(),
            vault.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        syncDirectory(directory)
    }

    private fun syncDirectory(directory: File) {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use {
                it.force(true)
            }
        } catch (_: IOException) {
            // Some Android filesystems reject directory channels; atomic moves still apply.
        } catch (_: UnsupportedOperationException) {
            // Same best-effort limitation as above.
        }
    }
}

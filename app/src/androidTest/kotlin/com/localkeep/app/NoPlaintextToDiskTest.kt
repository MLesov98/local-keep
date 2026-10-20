package com.localkeep.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * No-plaintext-to-disk guardrail (§I). Creates a vault, adds a Login whose password IS a unique
 * marker and a File item whose blob content IS the same marker, exercises the real
 * view/decrypt path, then scans every file under app-private storage for the marker as a raw
 * byte substring. The marker must exist ONLY as ciphertext (vault.enc / the encrypted blob) —
 * never as the literal string, anywhere on disk.
 */
@RunWith(AndroidJUnit4::class)
class NoPlaintextToDiskTest {
    private val marker = "PLAINTEXT_MARKER_a3f19c72e5"

    @Test fun no_secret_plaintext_lands_on_disk() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        VaultTestDriver.createVaultAndAddSecret(ctx, password = "test-master-pw".toCharArray(), secret = marker)
        VaultTestDriver.viewFirstItemAndAnyBlob(ctx)

        val roots = listOf(ctx.filesDir, ctx.cacheDir, ctx.codeCacheDir, ctx.noBackupFilesDir)
        val hits = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile }.filter { f ->
                // ISO-8859-1 preserves every byte 1:1 so a raw-byte substring search works.
                String(f.readBytes(), Charsets.ISO_8859_1).contains(marker)
            }.map { it.absolutePath }.toList()
        }
        assertTrue(hits.isEmpty(), "plaintext secret found on disk at: $hits")
    }
}

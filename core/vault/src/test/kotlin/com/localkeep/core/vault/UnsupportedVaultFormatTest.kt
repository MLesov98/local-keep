package com.localkeep.core.vault

import com.localkeep.core.crypto.CryptoConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A vault from a newer LocalKeep must not be reported as a damaged one.
 *
 * The two look identical from here — neither opens — but the advice is opposite. "Unreadable" sends
 * the user to restore a backup, and restoring an older backup over a vault that was merely *newer*
 * replaces good data with old data, permanently. So the header codec throws a distinct type and the
 * loader keeps it, instead of the old `IllegalArgumentException` that every `catch` flattened into
 * the same silence.
 */
class UnsupportedVaultFormatTest {

    /**
     * A header-sized buffer carrying the real container magic and a declared format of [version].
     *
     * The magic has to be right or `VersionedHeader.decode` rejects the buffer before the version
     * is ever looked at — which is how the first draft of this test passed for the wrong reason.
     * Everything after the version stays zeroed: the point is to be rejected *for the version*, and
     * nothing beyond it is reached.
     */
    private fun headerWithFormatVersion(version: Int): ByteArray =
        ByteArray(VaultHeaderCodec.ENCODED_SIZE).also {
            "VLT1".toByteArray(Charsets.US_ASCII).copyInto(it)
            it[4] = version.toByte()
            it[5] = CryptoConstants.KDF_ARGON2ID.toByte()
            it[6] = CryptoConstants.AEAD_AES256_GCM_SIV.toByte()
        }

    @Test
    fun `a future format version throws the typed exception, carrying the version`() {
        val failure = assertFailsWith<UnsupportedVaultFormatException> {
            VaultHeaderCodec.decode(headerWithFormatVersion(CryptoConstants.FORMAT_VERSION_1 + 1))
        }

        assertEquals(CryptoConstants.FORMAT_VERSION_1 + 1, failure.formatVersion)
    }

    /**
     * Every existing `catch (_: IllegalArgumentException)` in the loader has to keep working — the
     * new type is a subclass precisely so introducing it could not change what any of them does.
     */
    @Test
    fun `the typed exception is still an IllegalArgumentException`() {
        assertTrue(
            UnsupportedVaultFormatException(9) is IllegalArgumentException,
            "narrowing this type would change every existing catch in the loader",
        )
    }

    /**
     * The counter-case, and the one that keeps the new branch honest: a container that is simply
     * damaged must NOT be reported as "from a newer version", or the user is told to update an app
     * that is already current while their actual problem — a corrupt file — goes unaddressed.
     */
    @Test
    fun `a damaged container is not mistaken for a newer format`() {
        val truncated = ByteArray(8)

        val failure = assertFailsWith<IllegalArgumentException> { VaultHeaderCodec.decode(truncated) }

        assertTrue(
            failure !is UnsupportedVaultFormatException,
            "a truncated header is corruption, not a version this build predates",
        )
    }

    @Test
    fun `the version this build writes is not itself rejected`() {
        // Not a tautology: it asserts the guard is keyed to the constant the writer uses, so
        // bumping FORMAT_VERSION_1 without bumping the reader fails here rather than on a device.
        val failure = runCatching {
            VaultHeaderCodec.decode(headerWithFormatVersion(CryptoConstants.FORMAT_VERSION_1))
        }.exceptionOrNull()

        assertTrue(
            failure !is UnsupportedVaultFormatException,
            "this build must never call its own format version unsupported",
        )
    }
}

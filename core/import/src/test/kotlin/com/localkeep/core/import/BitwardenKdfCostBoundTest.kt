package com.localkeep.core.import

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A password-protected Bitwarden export's KDF cost is bounded before anything is derived (S2-12,
 * 2026-10-02).
 *
 * The iteration count and the Argon2 parameters come out of the file, and were handed to the
 * derivation as they stood: an envelope asking for `Int.MAX_VALUE` PBKDF2 rounds pinned a core for
 * hours in a call Back cannot interrupt. Each case below sits just past Bitwarden's own range, so a
 * build without the bound pays a few seconds and then reports a *wrong password* — which is how the
 * absence shows — where the bound refuses at once and says the file is not a valid export.
 */
class BitwardenKdfCostBoundTest {

    /** Well-formed EncStrings that authenticate under no key: reaching them means the KDF already ran. */
    private val junk = "2.AAAAAAAAAAAAAAAAAAAAAA==|AAAAAAAAAAAAAAAAAAAAAA==|AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun envelope(kdfType: Int, iterations: Int, memory: Int? = null, parallelism: Int? = null) =
        BitwardenEncryptedEnvelope(
            encrypted = true,
            passwordProtected = true,
            salt = "c2FsdHNhbHRzYWx0c2FsdA==",
            kdfType = kdfType,
            kdfIterations = iterations,
            kdfMemory = memory,
            kdfParallelism = parallelism,
            encKeyValidation = junk,
            data = junk,
        )

    private fun reasonFor(envelope: BitwardenEncryptedEnvelope): ImportFailureReason =
        assertFailsWith<ImportRejection> {
            BitwardenEncryptedExport.decrypt(envelope, "a".toCharArray())
        }.reason

    @Test
    fun `a PBKDF2 count past Bitwarden's range is refused before deriving`() {
        assertEquals(
            ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN),
            reasonFor(envelope(kdfType = 0, iterations = 2_000_001)),
        )
    }

    @Test
    fun `an Argon2 time cost past Bitwarden's range is refused before deriving`() {
        assertEquals(
            ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN),
            reasonFor(envelope(kdfType = 1, iterations = 11, memory = 1, parallelism = 1)),
        )
    }

    /** The control: a cost inside the range is derived, and a wrong password is still named as one. */
    @Test
    fun `a cost inside the range still derives and reports a wrong password`() {
        assertEquals(
            ImportFailureReason.WrongExportPassword(SourceFormat.BITWARDEN),
            reasonFor(envelope(kdfType = 0, iterations = 1_000)),
        )
    }
}

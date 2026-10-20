package com.localkeep.core.vault

import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.model.RevealPhraseVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RevealPhraseGateTest {

    @Test
    fun `a phrase verifies against its own freshly created verifier`() {
        val verifier = RevealPhraseGate.create("open sesame".toCharArray())
        assertTrue(RevealPhraseGate.verify("open sesame".toCharArray(), verifier))
    }

    @Test
    fun `a wrong phrase does not verify`() {
        val verifier = RevealPhraseGate.create("correct horse".toCharArray())
        assertFalse(RevealPhraseGate.verify("battery staple".toCharArray(), verifier))
    }

    @Test
    fun `a created verifier uses a fresh 16-byte salt, 32-byte hash and the floor parameters`() {
        val a = RevealPhraseGate.create("phrase".toCharArray())
        val b = RevealPhraseGate.create("phrase".toCharArray())
        assertEquals(RevealPhraseVerifier.SALT_SIZE, a.salt.size)
        assertEquals(RevealPhraseVerifier.HASH_SIZE, a.hash.size)
        assertEquals(KdfParams.FLOOR.memoryKib, a.memoryKib)
        assertEquals(KdfParams.FLOOR.iterations, a.iterations)
        assertEquals(KdfParams.FLOOR.parallelism, a.parallelism)
        assertTrue(a.isValid)
        // Same phrase, different salt → different hash, so no static rainbow target.
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.hash.contentEquals(b.hash))
    }

    @Test
    fun `an invalid verifier is rejected before any derivation`() {
        // A hostile verifier claiming a huge memory would OOM the KDF if it reached it; isValid must
        // stop it first, so verify returns false rather than allocating.
        val hostile = RevealPhraseVerifier(
            salt = ByteArray(16),
            memoryKib = 4_000_000,
            iterations = 2,
            parallelism = 1,
            hash = ByteArray(32),
        )
        assertFalse(hostile.isValid)
        assertFalse(RevealPhraseGate.verify("anything".toCharArray(), hostile))
    }
}

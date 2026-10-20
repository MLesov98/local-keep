package com.localkeep.core.vault

import com.localkeep.core.crypto.Argon2
import com.localkeep.core.crypto.ConstantTime
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.RevealPhraseVerifier

/**
 * The one place a reveal phrase is turned into, or checked against, a [RevealPhraseVerifier]. Pure
 * and stateless: it holds no session, does no throttling and owns no locks — the lock-manager attempt
 * gate wraps it with epoch/generation checks, serialized allocation and candidate wiping (see
 * `VaultLockManager.attemptHiddenReveal`). Kept separate so the KDF math is unit-testable without the
 * concurrency machinery around it.
 *
 * Uses the same Argon2id floor the vault password uses ([KdfParams.FLOOR]); a reveal phrase is a
 * second secret guarding the same data, so it earns the same derivation cost.
 */
object RevealPhraseGate {

    /**
     * Derives a fresh verifier for [phrase]: a new 16-byte random salt, the 32-byte Argon2id hash at
     * the floor, and the floor parameters. The caller owns wiping [phrase]; the derived hash and salt
     * are the stored verifier and are not wiped.
     */
    fun create(phrase: CharArray): RevealPhraseVerifier {
        val salt = SecureRandomProvider.nextBytes(RevealPhraseVerifier.SALT_SIZE)
        val hash = Argon2.deriveKey(phrase, salt, KdfParams.FLOOR)
        return RevealPhraseVerifier(
            salt = salt,
            memoryKib = KdfParams.FLOOR.memoryKib,
            iterations = KdfParams.FLOOR.iterations,
            parallelism = KdfParams.FLOOR.parallelism,
            hash = hash,
        )
    }

    /**
     * Whether [candidate] derives to [verifier]'s stored hash.
     *
     * [RevealPhraseVerifier.isValid] is checked *before* any Argon2 allocation, so a malformed or
     * hostile verifier is rejected as data rather than fed to the KDF as parameters — an invalid
     * verifier returns false, never a wrong-size comparison or an OOM. The computed hash is wiped in
     * `finally`; the comparison is constant-time ([ConstantTime.equals] → `MessageDigest.isEqual`).
     * The caller owns wiping [candidate].
     */
    fun verify(candidate: CharArray, verifier: RevealPhraseVerifier): Boolean {
        if (!verifier.isValid) return false
        val computed = Argon2.deriveKey(
            candidate,
            verifier.salt,
            KdfParams(verifier.memoryKib, verifier.iterations, verifier.parallelism),
        )
        return try {
            ConstantTime.equals(computed, verifier.hash)
        } finally {
            Zeroizer.wipe(computed)
        }
    }
}

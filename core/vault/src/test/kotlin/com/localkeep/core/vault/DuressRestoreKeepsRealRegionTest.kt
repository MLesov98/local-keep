package com.localkeep.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A decoy session restoring a snapshot must splice its own region, never rewrite the container.
 *
 * Red before the fix. `AtomicVaultWriter.write(context, fileBytes)` replaces the whole file, and a
 * snapshot is a byte copy of a whole container — both payload regions — so from a duress session
 * the ordinary path rewrote region 0 too, rolling the *real* vault back to whatever generation the
 * chosen snapshot held and detaching every item saved since. It was the one control in the app
 * through which a decoy session could reach the real vault's bytes.
 *
 * Integrity, not confidentiality: the duress session never holds the real DEK, so this could only
 * ever overwrite region 0, never read it. And the sharp case is not the coercer the duress vault
 * exists for — `THREAT-MODEL.md` puts a coerced unlock out of scope by name — but somebody handed
 * the decoy password who taps Restore innocently and silently rolls back a vault they do not know
 * is there.
 *
 * The isolation itself is already proven one layer down: `VaultFileCodecTest` asserts that
 * `replaceRegion(..., regionIndex = 1)` leaves region 0's ciphertext byte-identical. What that test
 * cannot say is whether `restoreVaultFile` *calls* it, which is the whole of this defect and the
 * whole of what is asserted here.
 *
 * Why the duress branch splices instead of refusing: refusing would be a repeatable decoy-only
 * outcome, and a decoy session that behaves differently from a real one is a tell — the exact
 * property the duress save path is shaped around. A snapshot whose region 1 does not authenticate
 * answers `CORRUPT`, which a real session can also produce, so that path is not a tell either.
 *
 * Asserted against the source, following [RestoreForgetsStaleRecoveryKitFlagTest]: this module's
 * unit tests have no Android runtime, and `restoreVaultFile` needs a live unlocked manager.
 */
class DuressRestoreKeepsRealRegionTest {

    private val source = File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")

    private val body: String
        get() {
            if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
            val declaration = "suspend fun restoreVaultFile(fileBytes: ByteArray): RestoreOutcome"
            val text = source.readText()
            assertTrue(
                declaration in text,
                "cannot find `$declaration` in VaultLockManager.kt — this test is no longer checking anything",
            )
            return text.substringAfter(declaration).substringBefore("\n    /**")
        }

    @Test
    fun `restore splices the decoy region instead of writing the whole container`() {
        val restore = body
        val wholeFileWrite = restore.indexOf("AtomicVaultWriter.write(context, fileBytes)")
        assertTrue(
            wholeFileWrite >= 0,
            "restoreVaultFile no longer writes the container at all — this test is stale",
        )
        val duressBranch = restore.indexOf("isDuress")
        assertTrue(
            duressBranch in 0 until wholeFileWrite,
            "restoreVaultFile must decide whether the live session is a decoy BEFORE it reaches " +
                "the whole-container write, or a decoy restore rolls the real vault back",
        )
        assertTrue(
            "regionIndex = 1" in restore.substring(0, wholeFileWrite),
            "the duress branch must splice region 1 with replaceRegion, the way the duress SAVE " +
                "path already does — a whole-file write from a decoy session replaces region 0 too",
        )
    }

    @Test
    fun `the decoy branch returns before the whole-container write`() {
        val restore = body
        val duressReturn = restore.indexOf("return@withLock RestoreOutcome.OK")
        val wholeFileWrite = restore.indexOf("AtomicVaultWriter.write(context, fileBytes)")
        assertTrue(
            duressReturn in 0 until wholeFileWrite,
            "the decoy path must return its own OK before the whole-container write is reached; " +
                "falling through would splice region 1 and THEN clobber both regions anyway",
        )
    }

    /**
     * The control, and it must stay green while the two above go red.
     *
     * The over-broad "fix" is to refuse restore outright in a decoy session. That closes the
     * rollback and opens a worse hole: a decoy session that cannot do something a real one can is
     * distinguishable, and distinguishability is the only property the duress vault actually sells.
     * So the real session's own path — its slot-0 commitment check and its AEAD verification before
     * overwriting — has to survive this change untouched.
     */
    @Test
    fun `the real session keeps its pre-overwrite corruption check`() {
        val restore = body
        assertTrue(
            "commitmentMatches" in restore && "authenticatesRegion" in restore,
            "the real-session path must still refuse to overwrite a good vault with a snapshot " +
                "that fails AEAD under the same DEK",
        )
        assertTrue(
            "takeUnless { it.isDuress }" in restore,
            "the real-DEK read must still exclude a duress session, or the decoy branch and the " +
                "real branch would both claim the same state",
        )
    }
}

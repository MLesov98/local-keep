package com.localkeep.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test: a snapshot from a newer LocalKeep must reach [RestoreOutcome.NEWER_VERSION],
 * never [RestoreOutcome.INVALID_FILE] or [RestoreOutcome.CORRUPT] — found 2026-09-09.
 *
 * `RestoreOutcome` had no case for "this build predates the snapshot", so both places
 * `restoreVaultFile` can see that fact folded it into a false claim instead:
 *
 * - The header decode at the top of the function is reachable from every restore (locked, real,
 *   duress). A container whose format is [UnsupportedVaultFormatException] — a well-formed header
 *   this build does not understand — used to fall into the blanket `catch (_: Exception)` and answer
 *   INVALID_FILE: "that snapshot is not a valid vault file". False; the snapshot is fine.
 * - The duress branch is the one restore path that fully decodes a region rather than only
 *   AEAD-authenticating it (see [VaultFileCodec.decryptRegion] vs. [VaultFileCodec.authenticatesRegion]
 *   in the real branch below it), so a decoy payload stamped with a [VaultSchemaTooNewException]
 *   schema is reachable there and used to answer CORRUPT: "that snapshot is corrupted and was not
 *   restored". Also false, and also the wrong next step — both wrongly point at deleting or
 *   replacing a snapshot that only needs a newer app to open.
 *
 * [VaultFromNewerVersionUnlockTest] already pins the equivalent fix on the *unlock* side
 * (`SlotTrial.PayloadTooNew` / `UnlockResult.VAULT_FROM_NEWER_VERSION`); this is the restore side it
 * names as still missing.
 *
 * Asserted against the source, following [RestoreForgetsStaleRecoveryKitFlagTest] and
 * [DuressRestoreKeepsRealRegionTest]: this module's unit tests have no Android runtime, and
 * `restoreVaultFile` needs a live unlocked manager.
 */
class RestoreFromNewerVersionTest {

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
    fun `a newer container format is caught apart from a damaged header`() {
        val restore = body
        assertTrue(
            "VaultHeaderCodec.decode(fileBytes)" in restore,
            "restoreVaultFile no longer decodes the header up front — this test is stale",
        )
        val headerDecode = restore.substringAfter("VaultHeaderCodec.decode(fileBytes)")
        assertTrue(
            "catch (_: UnsupportedVaultFormatException)" in headerDecode,
            "the header decode must catch UnsupportedVaultFormatException BEFORE its blanket " +
                "catch (_: Exception). Without it a well-formed header from a newer build is " +
                "indistinguishable from a truncated/garbage one and the user is told the snapshot " +
                "is not a valid vault file.",
        )
        assertTrue(
            headerDecode.indexOf("catch (_: UnsupportedVaultFormatException)") <
                headerDecode.indexOf("catch (_: Exception)"),
            "the blanket catch is first, so it swallows the newer-format refusal before the " +
                "specific catch can see it",
        )
        val newerVersionCatch = headerDecode.substringAfter("catch (_: UnsupportedVaultFormatException)")
            .substringBefore("catch (_: Exception)")
        assertTrue(
            "RestoreOutcome.NEWER_VERSION" in newerVersionCatch,
            "a caught UnsupportedVaultFormatException must return RestoreOutcome.NEWER_VERSION, " +
                "not fall through to INVALID_FILE",
        )
    }

    @Test
    fun `a too-new duress payload is caught apart from a corrupt one`() {
        val restore = body
        assertTrue(
            "decryptRegion(fileBytes, encKey, regionIndex = 1)" in restore,
            "the duress branch no longer fully decodes region 1 — this test is stale",
        )
        val duressBranch = restore.substringAfter("decryptRegion(fileBytes, encKey, regionIndex = 1)")
        assertTrue(
            "catch (_: VaultSchemaTooNewException)" in duressBranch,
            "the duress branch must catch VaultSchemaTooNewException BEFORE its blanket " +
                "catch (_: Exception). Without it a decoy payload from a newer build is " +
                "indistinguishable from a corrupt one and the user is told the snapshot was damaged.",
        )
        assertTrue(
            duressBranch.indexOf("catch (_: VaultSchemaTooNewException)") <
                duressBranch.indexOf("catch (_: Exception)"),
            "the blanket catch is first, so it swallows the too-new schema refusal before the " +
                "specific catch can see it",
        )
        val newerVersionCatch = duressBranch.substringAfter("catch (_: VaultSchemaTooNewException)")
            .substringBefore("catch (_: Exception)")
        assertTrue(
            "RestoreOutcome.NEWER_VERSION" in newerVersionCatch,
            "a caught VaultSchemaTooNewException in the duress branch must return " +
                "RestoreOutcome.NEWER_VERSION, not fall through to CORRUPT",
        )
    }
}

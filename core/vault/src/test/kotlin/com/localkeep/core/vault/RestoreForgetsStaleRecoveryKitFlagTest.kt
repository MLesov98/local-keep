package com.localkeep.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test: restoring a snapshot that carries a different Recovery Kit block must forget the
 * device-local "kit already saved" flag.
 *
 * `recovery_kit_saved` is a `SharedPreferences` boolean, not a fact about the container. There is
 * exactly one recovery block per vault file and `commitPreparedRecoveryKit` revokes the previous kit
 * when it splices a new one in, so a container rolled back by [VaultLockManager.restoreVaultFile] to
 * a generation from before that commit no longer unwraps under the words the user printed. The flag
 * kept asserting they did, which turns the Settings row that carries the no-kit debt into a claim
 * that the only forgotten-master-password route still works — discovered at the moment it does not.
 *
 * Cleared conditionally rather than unconditionally: snapshots are taken on `onSaved`, and
 * `commitPreparedRecoveryKit` emits it, so the newest snapshot does hold the current kit and
 * restoring *that* one must not manufacture a no-kit debt. Comparing the wrapped DEK before and
 * after is exact and costs no new string.
 *
 * `VaultInitializer.create` and `SettingsViewModel.runPasswordChange` are the other two container/DEK
 * replacing paths and both already call `forgetRecoveryKitSaved`; this is the third.
 *
 * Asserted against the source rather than against SharedPreferences because this module's unit tests
 * have no Android runtime — same reasoning as [VaultInitializerRecoveryKitFlagTest].
 */
class RestoreForgetsStaleRecoveryKitFlagTest {

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
    fun `restore compares the recovery block it is replacing`() {
        val restore = body
        val write = restore.indexOf("AtomicVaultWriter.write(context, fileBytes)")
        assertTrue(write >= 0, "restoreVaultFile no longer writes the container — this test is stale")
        assertTrue(
            "VaultFileCodec.recoveryWrappedDek" in restore.substring(0, write),
            "restoreVaultFile must read the live container's Recovery Kit block BEFORE overwriting " +
                "it, or it cannot tell whether the snapshot carries the same kit. Read after the " +
                "write and the comparison is against the snapshot itself, which always matches.",
        )
    }

    @Test
    fun `restore forgets a recovery-kit flag the snapshot invalidates`() {
        val restore = body
        val write = restore.indexOf("AtomicVaultWriter.write(context, fileBytes)")
        assertTrue(write >= 0, "restoreVaultFile no longer writes the container — this test is stale")
        assertTrue(
            "LocalKeepSettings.forgetRecoveryKitSaved(context)" in restore.substring(write),
            "restoreVaultFile must clear recovery_kit_saved after writing a snapshot whose recovery " +
                "block differs from the one it replaced. The 24 words the user printed wrap a key " +
                "that container no longer holds, but the Settings row keeps claiming a working kit — " +
                "and the Recovery Kit is the only forgotten-master-password route there is.",
        )
    }
}

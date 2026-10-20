package com.localkeep.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test: every unlock that publishes a session must promote the container it opened when
 * `vault.enc` is not the file it came from.
 *
 * [AtomicVaultWriter] commits a save with two renames — `vault.enc` to `.bak`, then `.tmp` to
 * `vault.enc` — and a process death in the gap leaves no `vault.enc` at all. [VaultLoader] is built
 * to survive that: `loadFileBytes` returns the fsynced `.tmp` as candidate 0, so the vault opens and
 * looks entirely healthy. What is not healthy is the backup set. `vault.enc` is the only container
 * Auto Backup carries — `app/src/main/res/xml/data_extraction_rules.xml` and
 * `full_backup_content.xml` both exclude `vault.enc.tmp` and `vault.enc.bak` by name — so until
 * something writes `vault.enc` back, every backup taken contains no vault whatsoever, and a second
 * crash during the next save destroys the only current copy.
 *
 * The password path repairs this (`if (index > 0 || !VaultLoader.hasValidPrimary(context))`), and
 * `VaultLoader.hasValidPrimary`'s own KDoc describes the harm as a shipped bug. The biometric and
 * Recovery-Kit paths reach `loadFileBytes` the same way and did not, so the repair was skipped on
 * the default daily unlock route.
 *
 * Asserted against the source rather than against the filesystem because this module's unit tests
 * have no Android runtime — same reasoning as [VaultInitializerRecoveryKitFlagTest]. The behavioural
 * twin belongs beside `AtomicVaultWriterInstrumentedTest`, which already pins that `loadFileBytes`
 * *prefers* the `.tmp` in this state but never that an unlock repairs it.
 */
class UnlockPromotesRenameWindowSurvivorTest {

    private val source = File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")

    /** The text of one member, from its declaration up to the next member's KDoc. */
    private fun bodyOf(declaration: String): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val text = source.readText()
        assertTrue(
            declaration in text,
            "cannot find `$declaration` in VaultLockManager.kt — this test is no longer checking anything",
        )
        return text.substringAfter(declaration).substringBefore("\n    /**")
    }

    private fun assertPromotes(what: String, body: String) {
        assertTrue(
            "!VaultLoader.hasValidPrimary(context)" in body,
            "$what publishes a session from whatever container `loadFileBytes` returned without " +
                "asking whether `vault.enc` is that container. After a process death in " +
                "AtomicVaultWriter's rename gap it opens the `.tmp`, and `vault.enc` — the only " +
                "vault file Auto Backup includes — stays absent while the app looks healthy.",
        )
        assertTrue(
            "runCatching { AtomicVaultWriter.write(context, fileBytes) }" in body,
            "$what must write the bytes it just opened back through AtomicVaultWriter when there " +
                "is no valid primary, exactly as the password path does. `runCatching` is not " +
                "optional: a failed repair must still leave the user with an open vault.",
        )
    }

    @Test
    fun `biometric unlock promotes a container opened without a valid primary`() {
        assertPromotes(
            "unlockWithBiometric",
            bodyOf("suspend fun unlockWithBiometric(cipher: Cipher): UnlockResult"),
        )
    }

    @Test
    fun `recovery-kit unlock promotes a container opened without a valid primary`() {
        assertPromotes(
            "unlockWithRecoveryKit",
            bodyOf("suspend fun unlockWithRecoveryKit("),
        )
    }
}

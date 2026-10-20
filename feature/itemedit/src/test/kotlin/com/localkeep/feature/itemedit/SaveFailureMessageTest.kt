package com.localkeep.feature.itemedit

import com.localkeep.core.vault.VaultBucketExhaustedException
import com.localkeep.core.vault.VaultSessionEndedException
import com.localkeep.core.vault.VaultTooLargeForDeviceException
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * "Try again" has to be true.
 *
 * Every save failure the editor could produce used to get one message, and that message tells the
 * user their changes are still there and to press Save again. For a vault that has outgrown this
 * device's heap that is the opposite of the truth: the next attempt serializes the same vault into
 * the same heap and dies in the same place. Measured 2026-07-29 — at the 48 MiB API 28 cap, a
 * 10,000-item vault dies growing an 8 MiB buffer with 2.6 MiB free.
 *
 * The failure it replaces is a crash, not a message: `commit` let the `OutOfMemoryError` escape and
 * the save handler went down with it.
 *
 * `ACTION_ERROR`/`VAULT_TOO_LARGE_ERROR`/`saveFailureMessage()` moved from `String` to
 * `@StringRes Int` in the 2026-07-30 i18n extraction (ItemEditViewModel.kt, strings.xml). The
 * identity assertions below still compare directly — an Int resource id is just as comparable as
 * the String it replaced. The *content* assertions ("says safe and unchanged", "names no
 * internals") cannot resolve a resource id without an Android Context, which this pure JVM
 * `kotlin.test` does not have — the same problem `UnlockSubtitleTest`
 * (feature/unlock) hit first, solved the same way: read the base (English) resource straight out
 * of strings.xml. It guards English only; see that test's kdoc for why that is the correct amount
 * of guarding for a unit test.
 */
class SaveFailureMessageTest {

    private val vaultTooLargeMessage: String = run {
        val xml = File("src/main/res/values/strings.xml").readText()
        val match = Regex("""<string name="itemedit_error_vault_too_large">(.*?)</string>""")
            .find(xml)
            ?: error("itemedit_error_vault_too_large not found in strings.xml")
        match.groupValues[1]
    }

    private val bucketExhaustedMessage: String = run {
        val xml = File("src/main/res/values/strings.xml").readText()
        val match = Regex("""<string name="itemedit_error_bucket_exhausted">(.*?)</string>""")
            .find(xml)
            ?: error("itemedit_error_bucket_exhausted not found in strings.xml")
        match.groupValues[1]
    }

    @Test
    fun `an ordinary failure keeps the retry advice`() {
        assertEquals(
            ItemEditViewModel.ACTION_ERROR,
            saveFailureMessage(IOException("storage hiccup")),
        )
        assertEquals(
            ItemEditViewModel.ACTION_ERROR,
            saveFailureMessage(VaultSessionEndedException("save your changes")),
        )
    }

    /**
     * An out-of-space save fails identically on every retry, exactly like the too-large-vault
     * case above — AtomicVaultWriter's FileOutputStream/Files.move surface ENOSPC as a plain
     * IOException whose message names the reason, since the platform gives it no dedicated
     * exception type. Before this fix that message fell into the `else` branch above and told
     * the user to try again.
     */
    @Test
    fun `an out-of-space save is distinguished from a generic failure`() {
        assertEquals(
            ItemEditViewModel.OUT_OF_SPACE_ERROR,
            saveFailureMessage(IOException("No space left on device")),
        )
        assertEquals(
            ItemEditViewModel.OUT_OF_SPACE_ERROR,
            saveFailureMessage(java.nio.file.FileSystemException("/data/vault.enc.tmp", null, "No space left on device")),
        )
    }

    @Test
    fun `a vault that outgrew the device does not tell the user to try again`() {
        val messageRes = saveFailureMessage(VaultTooLargeForDeviceException(heapLimitMib = 48))

        assertEquals(ItemEditViewModel.VAULT_TOO_LARGE_ERROR, messageRes)
        assertFalse(
            vaultTooLargeMessage.contains("try again", ignoreCase = true),
            "retrying cannot succeed until the vault is smaller, so the message must not ask for it",
        )
    }

    /**
     * The stored vault is untouched by this failure — `commit` builds the whole new file in memory
     * and only then hands it to `AtomicVaultWriter`, so an allocation failure happens strictly
     * before anything is written. A message that left that in doubt would read as data loss in a
     * password manager, which is the worst thing this screen could say.
     */
    @Test
    fun `the too-large message says the stored vault is safe`() {
        assertEquals(true, vaultTooLargeMessage.contains("safe and unchanged"))
    }

    /**
     * No heap figure in the copy. "48 MiB" is not a number the reader chose, can change, or can act
     * on, and printing device internals into a user-facing string is how the app starts leaking
     * diagnostics into the UI.
     */
    @Test
    fun `the too-large message names no internals`() {
        for (fragment in listOf("MiB", "heap", "OutOfMemory", "Exception")) {
            assertFalse(
                vaultTooLargeMessage.contains(fragment, ignoreCase = true),
                "user-facing copy must not name '$fragment'",
            )
        }
    }

    /**
     * A vault that has outgrown its fixed payload bucket ([VaultBucketExhaustedException]) is a third
     * save failure retrying alone cannot fix. Before this branch it fell into the generic `else` and
     * told the user to try again — false, since every retry serialises the same oversized vault.
     */
    @Test
    fun `a vault that outgrew its bucket is distinguished from a generic failure`() {
        assertEquals(
            ItemEditViewModel.BUCKET_EXHAUSTED_ERROR,
            saveFailureMessage(VaultBucketExhaustedException()),
        )
    }

    @Test
    fun `the bucket-exhausted message does not tell the user to try again`() {
        assertFalse(
            bucketExhaustedMessage.contains("try again", ignoreCase = true),
            "retrying serialises the same oversized vault, so the message must not ask for it",
        )
    }

    /**
     * The fixed bucket only exists because a decoy (duress) vault is configured — the two vaults
     * share it. Naming that in a message shown on the real vault would betray that a decoy exists,
     * defeating the deniability the whole feature provides. The copy must stay silent about it.
     */
    @Test
    fun `the bucket-exhausted message never leaks the decoy reason`() {
        for (fragment in listOf("duress", "decoy", "hidden", "bucket", "region")) {
            assertFalse(
                bucketExhaustedMessage.contains(fragment, ignoreCase = true),
                "user-facing copy must not name '$fragment'",
            )
        }
    }
}

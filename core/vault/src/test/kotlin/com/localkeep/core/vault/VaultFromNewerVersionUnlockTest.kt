package com.localkeep.core.vault

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test: a vault written by a newer build must reach the user as
 * [UnlockResult.VAULT_FROM_NEWER_VERSION], never as [UnlockResult.VAULT_UNREADABLE].
 *
 * The distinction was already implemented — and pointed at the wrong number. `unlock` asks
 * `VaultLoader.unsupportedFormatVersion` only when *nothing on disk parses*, and that reads the
 * CONTAINER format version, which has never bumped past `FORMAT_VERSION_1`. The version that
 * actually moves is the payload schema: `Vault.CURRENT_SCHEMA_VERSION` is 8 and has bumped seven
 * times. A vault from a newer build therefore has a header this build reads perfectly, so
 * `candidates` is non-empty and that branch is never reached at all.
 *
 * What happened instead: the correct password derived, unwrapped and matched the slot commitment,
 * `VaultSerializer.decode` refused the payload, `tryPasswordSlot`'s blanket `catch (_: Exception)`
 * swallowed the refusal, and the unlock returned `VAULT_UNREADABLE` — whose string is "The vault
 * file couldn't be read. Your password is probably fine — try restoring a backup from Settings."
 * Restoring an older backup over a healthy newer vault destroys it irreversibly, and `vault.enc`
 * reaches Google's backup set by design, so an older APK meeting a restored newer vault is a real
 * arrangement and not a hypothetical one.
 *
 * The refusal now has its own type (`VaultSchemaTooNewException` — behaviour covered by
 * `VaultSchemaVersionTest` in `:core:model`) and this pins the three places it has to survive on
 * the way out: caught apart from a corrupt payload, carried as its own `SlotTrial`, and answered
 * with the right `UnlockResult`.
 *
 * **Why this is a source assertion and not a behavioural one.** Reaching the branch means an
 * unlock, which needs an Android `Context`, a real container, and two calibrated Argon2id
 * derivations — this module's lock-manager suite is instrumented for exactly that reason, and the
 * fixture would additionally need a payload stamped at a version `VaultSerializer.encode` will not
 * write from outside `:core:model`. What is checked here is the wiring that was missing. Stated
 * plainly rather than dressed up as more than it is; the same trade [UnlockDispatcherTest]
 * documents.
 */
class VaultFromNewerVersionUnlockTest {

    private val source = java.io.File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    @Test
    fun `a too-new payload is caught apart from a corrupt one`() {
        val body = text().substringAfter("private fun tryPasswordSlot(")
            .substringBefore("private fun commitmentMatches(")
        assertTrue(
            body.contains("catch (_: VaultSchemaTooNewException)"),
            "tryPasswordSlot must catch VaultSchemaTooNewException BEFORE its blanket " +
                "catch (_: Exception). Without it, a healthy vault from a newer build is " +
                "indistinguishable from a damaged one and the user is sent to Restore.",
        )
        assertTrue(
            body.indexOf("catch (_: VaultSchemaTooNewException)") < body.indexOf("catch (_: Exception)"),
            "the blanket catch is first, so it swallows the schema refusal before the specific " +
                "catch can see it",
        )
        assertTrue(
            body.contains("SlotTrial.PayloadTooNew"),
            "tryPasswordSlot must report the too-new payload as its own SlotTrial",
        )
        // The verdict may only be reached once the password has been proved right, or it would
        // answer "was that the right password" before the unlock does.
        assertTrue(
            body.contains("credentialValid && schemaTooNew -> SlotTrial.PayloadTooNew"),
            "PayloadTooNew must stay behind credentialValid — a slot the password does not open " +
                "must return NoMatch whatever its payload says",
        )
    }

    @Test
    fun `the scan reports it and unlock answers with the newer-version result`() {
        val text = text()
        assertTrue(
            text.contains("ScanOutcome.PayloadTooNew"),
            "scanPasswordSlots must carry the too-new verdict out as its own ScanOutcome",
        )

        val branch = text.substringAfter("ScanOutcome.PayloadTooNew ->").substringBefore("ScanOutcome.PayloadCorrupt")
        assertTrue(
            branch.contains("UnlockResult.VAULT_FROM_NEWER_VERSION"),
            "unlock must answer ScanOutcome.PayloadTooNew with VAULT_FROM_NEWER_VERSION. " +
                "VAULT_UNREADABLE tells the user to restore a backup over a vault whose only " +
                "problem is being newer than this build — which destroys it.",
        )
        assertTrue(
            !branch.contains("sawCorruption"),
            "the too-new verdict must not be folded into sawCorruption: that path ends on " +
                "VAULT_UNREADABLE, which is the advice this branch exists to avoid",
        )
    }
}

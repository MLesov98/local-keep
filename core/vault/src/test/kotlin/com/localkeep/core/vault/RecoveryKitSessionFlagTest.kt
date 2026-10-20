package com.localkeep.core.vault

import com.localkeep.core.model.Vault
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A session opened with the Recovery Kit is not the same session as one opened with the password.
 *
 * `LockState.Unlocked` hand-writes `equals`/`hashCode` because it holds a `ByteArray`, and a
 * hand-written `equals` that forgets a field is the bug this guards: the vault list decides whether
 * to offer "set a new master password" from [LockState.Unlocked.openedWithRecoveryKit], and a
 * `StateFlow` does not re-emit a value it considers equal to the last one. So an `equals` that
 * ignored the flag would silently swallow the very transition the note exists to catch — the offer
 * would simply never appear, with nothing anywhere reporting a fault (G3, 2026-09-11).
 */
class RecoveryKitSessionFlagTest {

    private val dek = ByteArray(32) { it.toByte() }
    private val vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH)

    @Test
    fun `a kit session is not equal to a password session`() {
        val viaPassword = LockState.Unlocked(dek, vault)
        val viaKit = LockState.Unlocked(dek, vault, openedWithRecoveryKit = true)
        assertNotEquals(
            viaPassword,
            viaKit,
            "StateFlow drops an equal value, so an equals that ignores this flag hides the transition",
        )
        assertNotEquals(viaPassword.hashCode(), viaKit.hashCode())
    }

    @Test
    fun `two kit sessions with the same contents are equal`() {
        assertEquals(
            LockState.Unlocked(dek, vault, openedWithRecoveryKit = true),
            LockState.Unlocked(dek, vault, openedWithRecoveryKit = true),
        )
    }

    /** The default is the ordinary path: a password unlock must not raise the note. */
    @Test
    fun `an ordinary unlock does not claim the kit was used`() {
        assertEquals(false, LockState.Unlocked(dek, vault).openedWithRecoveryKit)
        assertEquals(false, LockState.Unlocked(dek, vault, isDuress = true).openedWithRecoveryKit)
    }

    /**
     * Every republish of a live session must `copy` it, never rebuild it by hand.
     *
     * This is the bug the equality test above cannot see. `commit()` rebuilt `LockState.Unlocked`
     * field by field after a save and simply did not mention `openedWithRecoveryKit`, so it fell
     * back to its default of `false` and the Recovery-Kit note disappeared the first time the user
     * saved anything — uncommanded, with the reset still not done (V5, 2026-09-12). A hand-built
     * constructor call is the failure mode, so the constructor call is what this forbids.
     *
     * `publishUnlocked` is the one legitimate exception: it *opens* a session rather than
     * republishing one, so it has no prior state to copy from.
     */
    @Test
    fun `only publishUnlocked builds an Unlocked state by hand`() {
        val source = File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")
            .readText()
        val publishBody = source.substringAfter("    private fun publishUnlocked(")
        val outsidePublish = source.substringBefore("    private fun publishUnlocked(") +
            publishBody.substringAfter("\n    }\n")

        assertEquals(
            0,
            Regex("""LockState\.Unlocked\(""").findAll(outsidePublish).count(),
            "VaultLockManager must republish a live session with `copy(...)` so every property of " +
                "that session survives. Rebuilding it by hand is how openedWithRecoveryKit was " +
                "silently dropped on save (V5).",
        )
    }

    /**
     * Rotating the DEK under slot zero *is* the user doing what the note asked, so the note must be
     * retired there. It was preserved instead, which brought the note back immediately after the
     * password change it requested (V6, 2026-09-12).
     */
    @Test
    fun `rotating the master password clears the kit flag`() {
        val source = File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")
            .readText()
        val rotate = source
            .substringAfter("    private fun rotateDekUnderSlotZero(")
            .substringBefore("\n    private fun ")

        assertEquals(
            true,
            rotate.contains("openedWithRecoveryKit = false"),
            "rotateDekUnderSlotZero must clear openedWithRecoveryKit — the rotation is the answer " +
                "to the note, so the note must not survive it (V6).",
        )
    }
}

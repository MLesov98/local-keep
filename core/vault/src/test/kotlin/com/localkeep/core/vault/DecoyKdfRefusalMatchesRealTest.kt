package com.localkeep.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A decoy session's refusal to re-calibrate the KDF reads like the real vault's.
 *
 * Red before the fix. `rewrapKdfParams` opened on `realSessionSnapshot`, which `check`s the session
 * is not a duress one and throws `IllegalStateException` when it is. `BackupSettingsViewModel`
 * catches that alongside every other unnamed `IllegalStateException` and renders
 * `backup_error_generic` — "that didn't go through, try again" — while the offer stays on screen.
 * So in a decoy session Update failed, said nothing, and asked to be pressed again, forever.
 *
 * A real vault with a decoy configured answers the same question with
 * [RewrapOutcome.DURESS_CONFIGURED], whose wording says this vault's setup does not allow the
 * change and names no feature, and collapses the offer. Two different behaviours on the same
 * control is what a decoy cannot afford: the whole feature holds only while an observer cannot tell
 * which vault they are looking at. Same reasoning, and the same shape of fix, as
 * [DuressRestoreKeepsRealRegionTest].
 *
 * What is *not* equalised is timing — the real refusal arrives after two Argon2id derivations and
 * this one arrives at once. That gap predates this change (the throw was instant too), and the KDoc
 * says so rather than letting a reader assume the two are indistinguishable in every respect.
 *
 * Asserted against the source, following [DuressRestoreKeepsRealRegionTest]: this module's unit
 * tests have no Android runtime, and `rewrapKdfParams` needs a live unlocked manager.
 */
class DecoyKdfRefusalMatchesRealTest {

    private val source = File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")

    private val body: String
        get() {
            if (!source.isFile) {
                fail("cannot find ${source.absolutePath} — this test reads the source it guards")
            }
            val declaration = "internal suspend fun rewrapKdfParams("
            val text = source.readText()
            assertTrue(
                declaration in text,
                "cannot find `$declaration` in VaultLockManager.kt — this test is no longer " +
                    "checking anything",
            )
            return text.substringAfter(declaration).substringBefore("\n    /**")
        }

    @Test
    fun `a duress session is refused by outcome rather than by a thrown check`() {
        val rewrap = body
        val guard = rewrap.indexOf("isDuress")
        val snapshot = rewrap.indexOf("realSessionSnapshot(")
        assertTrue(
            snapshot >= 0,
            "rewrapKdfParams no longer takes a session snapshot — this test is stale",
        )
        assertTrue(
            guard in 0 until snapshot,
            "the duress session must be answered before `realSessionSnapshot`, which throws on " +
                "one; a thrown IllegalStateException reaches the backup screen as the generic " +
                "retry message",
        )
        assertTrue(
            "RewrapOutcome.DURESS_CONFIGURED" in rewrap.substring(guard, snapshot),
            "the duress session must return the same typed outcome the real vault returns, so " +
                "both render the same wording",
        )
    }

    /**
     * The control, and it must stay green while the test above goes red.
     *
     * The refusal that matters most is the *real* session's: a vault with a decoy configured cannot
     * move slot 0 without moving slot 1, and slot 1 needs a password this session must never ask
     * for. Answering the decoy session early must not displace that check, and it must not turn
     * into a blanket "always refuse" that stops an ordinary vault from ever re-calibrating.
     */
    @Test
    fun `the real session still detects a configured decoy and still succeeds without one`() {
        val rewrap = body
        assertTrue(
            "if (duressConfigured) return@withLock RewrapOutcome.DURESS_CONFIGURED" in rewrap,
            "the real-session decoy detection — region 1 failing to authenticate under the real " +
                "key — must survive; the early guard covers a different session, not this case",
        )
        assertTrue(
            "RewrapOutcome.OK" in rewrap,
            "rewrapKdfParams must still have a success path",
        )
    }
}

package com.localkeep.core.vault

import com.localkeep.core.model.Note
import com.localkeep.core.model.RevealPhraseVerifier
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Which reveal-phrase verifier survives a write, and why the two import modes want different answers.
 *
 * A **merge** keeps the local records, so it keeps the local verifier: the phrase the user knows still
 * describes the vault in front of them. A **replace** throws every local item and folder away, so
 * keeping the local verifier leaves the imported hidden entries concealed behind a phrase that no
 * longer exists anywhere. That was the shipped behaviour until this test existed. Nothing was lost —
 * the authenticated Settings screen is never gated and gives every entry back — but the user's own
 * reveal phrase for their own data silently stopped working, which is a bad way to learn that a
 * restore succeeded.
 *
 * The default is the conservative one on purpose: every other caller of [VaultMutator] edits items or
 * folders and must not touch the verifier by omission.
 */
class VaultMutatorRevealPhraseTest {

    private val now: Instant = Instant.parse("2026-08-18T12:00:00Z")

    private fun verifier(seed: Byte) = RevealPhraseVerifier(
        salt = ByteArray(16) { seed },
        memoryKib = 65536,
        iterations = 19,
        parallelism = 1,
        hash = ByteArray(32) { seed },
    )

    private fun item(title: String) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = now,
        updatedAt = now,
        content = Note("n"),
    )

    private fun vault(phrase: RevealPhraseVerifier?) = Vault(
        items = listOf(item("Cafe wifi")),
        folders = emptyList(),
        createdAt = now,
        modifiedAt = now,
        revealPhrase = phrase,
    )

    @Test
    fun anOrdinaryEditKeepsTheVerifierItFound() {
        val local = verifier(1)
        val mutator = VaultMutator(vault(local))

        mutator.items.add(item("Shopping list"))

        assertSame(local, mutator.build().revealPhrase, "an item edit must not touch the verifier")
    }

    @Test
    fun aReplaceCanInstallTheImportedVerifier() {
        val local = verifier(1)
        val imported = verifier(2)
        val mutator = VaultMutator(vault(local))

        mutator.items.clear()
        mutator.items.add(item("Offshore account"))
        mutator.revealPhrase = imported

        assertSame(
            imported,
            mutator.build().revealPhrase,
            "replace installs the verifier belonging to the records it installed",
        )
    }

    @Test
    fun aReplaceFromAVaultWithNothingHiddenClearsTheVerifier() {
        // Null is a real answer, not a missing one: after replacing with a backup that hid nothing,
        // this vault hides nothing either, and a leftover verifier would be a phrase that reveals
        // an empty set — indistinguishable, to the user typing it, from a phrase that stopped working.
        val mutator = VaultMutator(vault(verifier(1)))

        mutator.revealPhrase = null

        assertNull(mutator.build().revealPhrase)
    }

    @Test
    fun theDefaultIsTheExistingVerifierRatherThanNull() {
        // Stated as its own case because the failure mode is silent: were the field to default to
        // null, every save in the app would quietly erase the reveal phrase and the only symptom
        // would be a user's phrase no longer working the next time they tried it.
        val local = verifier(3)
        assertEquals(local, VaultMutator(vault(local)).revealPhrase)
        assertNull(VaultMutator(vault(null)).revealPhrase)
    }
}

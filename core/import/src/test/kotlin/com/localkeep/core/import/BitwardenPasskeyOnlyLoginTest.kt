package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Bitwarden login whose only credential is a passkey.
 *
 * Nothing about a WebAuthn credential can be imported — it is a private key bound to Bitwarden's
 * own store — and the KeePass-style caveat on the source step says so. But the *item* still crosses
 * over with its name, its username and its URL, so the preview showed an ordinary login with an
 * empty password and said nothing at all (1A-F8, 2026-09-20). What is fixed is the account of it,
 * not the carrying: [ParsedImport.passkeyOnlyLoginCount] is what the wizard states on the preview
 * and again on the Done step, which is the last moment before it offers to delete the export.
 */
class BitwardenPasskeyOnlyLoginTest {

    private val path = "bitwarden/passkey-export.json"

    @Test
    fun `a login whose only credential is a passkey is counted`() {
        assertTrue("fido2Credentials" in RealWorldCorpus.text(path), "$path no longer carries a passkey")
        val parsed = BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        // The item is imported, not skipped — which is exactly why a count was needed.
        val login = parsed.items.single().content as Login
        assertEquals("", login.password)
        assertEquals(0, parsed.skipped.size)
        assertEquals(1, parsed.passkeyOnlyLoginCount)
    }

    /**
     * Control, and the reason the counter is not "logins that carry a passkey": an ordinary export
     * with no passkey in it must report zero, or the sentence appears on every import.
     */
    @Test
    fun `an export with no passkeys counts none`() {
        val plain = "bitwarden/plain-export.json"
        assertEquals(0, BitwardenJsonImporter.parse(RealWorldCorpus.bytes(plain)).orFail(plain).passkeyOnlyLoginCount)
    }

    /**
     * The other control: a login that has a password *as well as* a passkey imports usably, so it
     * is not what this sentence is about. Built by putting a password on the corpus file's own
     * item, so the only difference from the counted case is the one under test.
     */
    @Test
    fun `a login with a password as well as a passkey is not counted`() {
        val withPassword = RealWorldCorpus.text(path).replace("\"password\": null", "\"password\": \"hunter2\"")
        assertTrue("\"hunter2\"" in withPassword, "the fixture no longer spells its null password this way")
        val parsed = BitwardenJsonImporter.parse(withPassword.toByteArray()).orFail(path)
        assertEquals("hunter2", (parsed.items.single().content as Login).password)
        assertEquals(0, parsed.passkeyOnlyLoginCount)
    }
}

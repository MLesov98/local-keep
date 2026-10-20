package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Login
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * KeePass field references — `{REF:U@I:…}`, one entry borrowing a field from another.
 *
 * The real-world half of this is `test4.kdbx`, which pykeepass wrote and which holds all three
 * shapes the feature has to get right: a plain clone, a clone *of* a clone, and a reference with
 * text either side of it. They imported as the raw token, so the item's username was an unusable
 * placeholder and nothing in the wizard said so (1A-F7, 2026-09-20).
 */
class KdbxFieldReferenceTest {

    private fun test4(): ParsedImport = KdbxImporter.parse(
        RealWorldCorpus.bytes("keepass/pykeepass/test4.kdbx"),
        "password".toCharArray(),
        keyFile = RealWorldCorpus.bytes("keepass/pykeepass/test4.key"),
    ).orFail("keepass/pykeepass/test4.kdbx")

    private fun ParsedImport.login(title: String): Login =
        items.first { it.title == title }.content as Login

    /** The finding's own entry: its username and password are `foobar_entry`'s. */
    @Test
    fun `a cloned entry borrows the fields it references`() {
        val parsed = test4()
        assertEquals("foobar", parsed.login("foobar_entry - Clone").username)
        assertEquals("foobar", parsed.login("foobar_entry - Clone").password)
    }

    /**
     * A clone *of* the clone, whose reference resolves to another reference. One hop would leave
     * this one exactly as the finding describes, so it is the case that decides whether chains are
     * followed at all.
     */
    @Test
    fun `a reference to a reference resolves to the value at the end of the chain`() {
        assertEquals("foobar", test4().login("foobar_entry - Clone of clone").username)
    }

    /**
     * `domain\{REF:U@I:…}2` — the token is substituted in place, and the text either side of it is
     * the user's, so it stays.
     */
    @Test
    fun `text around a reference is preserved`() {
        val parsed = test4()
        assertEquals("domain\\foobar2", parsed.login("foobar_entry - Clone with prefix and suffix").username)
        assertEquals("AfoobarBC", parsed.login("foobar_entry - Clone with prefix and suffix").password)
    }

    /** Nothing in this database is left pointing at a token. */
    @Test
    fun `a database whose references all resolve reports none unresolved`() {
        assertEquals(0, test4().unresolvedFieldReferenceCount)
    }

    /**
     * The other half of the contract, and the reason it is not "substitute an empty string": a
     * reference nothing answers is left exactly as the file wrote it and is *counted*, so the
     * wizard can say so on the step before the one that offers to delete the export. Blanking it
     * would turn "this points somewhere I cannot follow" into "this field is empty".
     */
    @Test
    fun `a reference nothing answers stays literal and is counted`() {
        val password = "correct horse battery staple"
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Borrower"
                username = "{REF:U@I:00000000000000000000000000000000}"
                setPassword("own-password")
            },
        )
        val bytes = ByteArrayOutputStream().use { out ->
            db.save(KdbxCreds(password.toByteArray(Charsets.UTF_8)), out)
            out.toByteArray()
        }
        val parsed = KdbxImporter.parse(bytes, password.toCharArray()).orFail("synthetic")
        assertEquals("{REF:U@I:00000000000000000000000000000000}", (parsed.items.single().content as Login).username)
        assertEquals(1, parsed.unresolvedFieldReferenceCount)
    }
}

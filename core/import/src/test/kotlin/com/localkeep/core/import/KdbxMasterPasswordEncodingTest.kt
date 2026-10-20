package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Control for the encoder-buffer wipe in `KdbxImporter.charArrayToUtf8Bytes`.
 *
 * The wipe itself is not observable from outside the importer — that is the honest reason there is
 * no assertion on it here, and why this file cannot be made to fail on the un-wiped version. What
 * *is* observable is the encoded credential, and the multi-byte case is where wiping the wrong
 * region would show: for non-ASCII text the encoder's backing array is longer than the character
 * count and its limit sits short of its capacity, so a wipe that ran before the bytes were copied
 * out, or that reasoned about the wrong window, produces a credential that no longer opens the
 * database. A password of pure ASCII would not catch it, and every other test in this module uses
 * one.
 */
class KdbxMasterPasswordEncodingTest {

    @Test
    fun `a non-ASCII master password still opens the database it created`() {
        // Two-byte (é, ü), three-byte (中), and four-byte (an emoji, encoded as a surrogate pair in
        // the CharArray) — one of each width, so the byte count and the char count differ in every
        // way UTF-8 allows.
        val password = "pâsswörd-中文-🔐"
        val creds = KdbxCreds(password.toByteArray(Charsets.UTF_8))

        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Example"
                setPassword("s3cr3t-p@ss")
            },
        )
        val encrypted = ByteArrayOutputStream().use { out ->
            db.save(creds, out)
            out.toByteArray()
        }

        // The importer encodes the CharArray itself; if that encoding did not match the bytes the
        // writer used above, this comes back as WrongPassword rather than Success.
        val result = KdbxImporter.parse(encrypted, password.toCharArray())

        assertTrue(result is Outcome.Success, "a non-ASCII master password must decrypt its own database")
        assertEquals("Example", result.value.items.single().title)
    }
}

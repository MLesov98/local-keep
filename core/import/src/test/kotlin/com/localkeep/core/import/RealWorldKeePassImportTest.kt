package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.import.RealWorldCorpus.failureReason
import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Login
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * KeePass `.kdbx`, from `src/test/resources/realworld/keepass/` (see `PROVENANCE.md` there).
 *
 * Two corpora, for two different jobs. **pykeepass** (`pykeepass/`) covers every container
 * permutation with a password/KDF/cipher combination confirmed from that project's own passing test
 * suite — KDBX 3.1 and 4.0, AES/ChaCha20/Twofish, AES-KDF/Argon2d/Argon2id, three key-file formats,
 * a blank password. **KeePassXC** (`keepassxc/`) adds richer databases, of which `NewDatabase.kdbx`
 * (password `a`) and its merge companions are the ones whose password is confirmed.
 *
 * Counts come from [RealWorldKdbxOracle], which reads the decrypted container's own `<Entry>`
 * elements. That matters here more than anywhere else in the corpus: a KDBX has no plaintext
 * sibling to count from, and what is under test is [KdbxImporter]'s *walk* of the tree — which
 * groups it descends into, what it does with the Recycle Bin — so the count has to come from the
 * container rather than from the walk.
 */
class RealWorldKeePassImportTest {

    /** Every `.kdbx` in the corpus, whatever this build can do with it. */
    private val allDatabases = listOf(
        "keepass/pykeepass/test3.kdbx",
        "keepass/pykeepass/test3_transformed.kdbx",
        "keepass/pykeepass/test4.kdbx",
        "keepass/pykeepass/test4_aes.kdbx",
        "keepass/pykeepass/test4_aeskdf.kdbx",
        "keepass/pykeepass/test4_argon2id.kdbx",
        "keepass/pykeepass/test4_blankpass.kdbx",
        "keepass/pykeepass/test4_chacha20.kdbx",
        "keepass/pykeepass/test4_twofish.kdbx",
        "keepass/pykeepass/test4_hex.kdbx",
        "keepass/pykeepass/test4_keyx.kdbx",
        "keepass/pykeepass/test4_transformed.kdbx",
        "keepass/pykeepass/test4_aes_uncompressed.kdbx",
        "keepass/pykeepass/test4_chacha20_uncompressed.kdbx",
        "keepass/pykeepass/test4_twofish_uncompressed.kdbx",
        "keepass/pykeepass/extra_content.kdbx",
        "keepass/keepassxc/NewDatabase.kdbx",
        "keepass/keepassxc/NewDatabase2.kdbx",
        "keepass/keepassxc/MergeDatabase.kdbx",
        "keepass/keepassxc/SyncDatabase.kdbx",
        "keepass/keepassxc/Compressed.kdbx",
        "keepass/keepassxc/RecycleBinEmpty.kdbx",
        "keepass/keepassxc/RecycleBinWithData.kdbx",
        "keepass/keepassxc/ProtectedStrings.kdbx",
        "keepass/keepassxc/NonAscii.kdbx",
        "keepass/keepassxc/KeyFileProtected.kdbx",
        "keepass/keepassxc/FileKeyHex.kdbx",
        "keepass/keepassxc/FileKeyXmlV2.kdbx",
        "keepass/keepassxc/Format200.kdbx",
        "keepass/keepassxc/Format300.kdbx",
        "keepass/keepassxc/Format400.kdbx",
    )

    /**
     * The databases this build can actually open, with the password that opens them. Every other
     * pykeepass file needs a key file as well (see the key-file gap below), and the remaining
     * KeePassXC ones have no password confirmed from upstream source.
     */
    private val openable = mapOf(
        "keepass/pykeepass/test4_argon2id.kdbx" to "password",
        "keepass/pykeepass/test4_aes_uncompressed.kdbx" to "password",
        "keepass/pykeepass/test4_chacha20_uncompressed.kdbx" to "password",
        "keepass/pykeepass/test4_twofish_uncompressed.kdbx" to "password",
        "keepass/pykeepass/extra_content.kdbx" to "password",
        "keepass/keepassxc/NewDatabase.kdbx" to "a",
        "keepass/keepassxc/NewDatabase2.kdbx" to "a",
        "keepass/keepassxc/MergeDatabase.kdbx" to "a",
        "keepass/keepassxc/SyncDatabase.kdbx" to "a",
        "keepass/keepassxc/Compressed.kdbx" to "",
    )

    /**
     * Every `.kdbx` is recognised as one, and no file in the corpus can make [KdbxImporter] throw or
     * return a failure with no typed reason. This is the invariant that has to hold for *all* 31
     * databases, including the ones nothing here can open: a failure with no reason is what
     * `feature/import` renders as raw English, which is the leak `ImportFailureReason` exists to
     * make impossible.
     */
    @Test
    fun `every KDBX in the corpus is recognised and answers with a typed reason or a parse`() {
        allDatabases.forEach { path ->
            val bytes = RealWorldCorpus.bytes(path)
            assertTrue(KdbxImporter.detect(bytes), "$path was not recognised as a KDBX database")
            val outcome = KdbxImporter.parse(bytes, (openable[path] ?: "password").toCharArray())
            if (outcome is Outcome.Failure) {
                // Throws with a message naming the path if the reason is missing.
                RealWorldCorpus.reasonOf(outcome)
            }
        }
    }

    /**
     * The count check. For each database this build can open, the number of live `<Entry>` elements
     * in the decrypted container must equal the items the importer produced plus the entries it
     * named as skipped — an entry may not simply go missing between the two.
     */
    @Test
    fun `every entry in an openable container becomes an item or a named skip`() {
        openable.forEach { (path, password) ->
            val contents = RealWorldKdbxOracle.contents(RealWorldCorpus.bytes(path), password)
            val parsed = KdbxImporter.parse(RealWorldCorpus.bytes(path), password.toCharArray()).orFail(path)
            assertEquals(
                contents.entries,
                parsed.items.size + parsed.skipped.size,
                "$path — container holds ${contents.entries} entries",
            )
            assertEquals(
                contents.recycleBinEntries,
                parsed.skipped.count { it.reason == SkipReason.SourceRecycleBin },
                "$path — every deleted entry, and only those, are reported as recycle-bin skips",
            )
            assertEquals(contents.groups, parsed.folders.size, "$path folder count")
            assertEquals(true, parsed.timestampsKnown, "$path — KDBX carries real entry times")
        }
    }

    /**
     * The KeePassXC database with a confirmed password, read down to its fields. Its two entries and
     * seven groups are what [RealWorldKdbxOracle] counts in the container, and the group names are
     * KeePass's own default template plus the one the fixture adds.
     */
    @Test
    fun `NewDatabase opens with its password and keeps its tree and its entries`() {
        val path = "keepass/keepassxc/NewDatabase.kdbx"
        val parsed = KdbxImporter.parse(RealWorldCorpus.bytes(path), "a".toCharArray()).orFail(path)
        assertEquals(
            listOf("General", "Windows", "Network", "Internet", "eMail", "Homebanking", "Subgroup"),
            parsed.folders.map { it.name },
            "the container's own groups, parents before children",
        )
        val sample = parsed.items.first { it.title == "Sample Entry" }
        val login = sample.content as Login
        assertEquals("User Name", login.username)
        assertEquals("http://www.somesite.com/", login.uris.single().uri)
        assertEquals("Notes", login.notes)
        assertNotNull(login.totp, "the sample entry carries a TOTP seed in its custom strings")

        // The entry in a subgroup must be filed in it, not flattened to the root.
        val subgroupEntry = parsed.items.first { it.title == "Subgroup Entry" }
        assertEquals(parsed.folders.first { it.name == "Subgroup" }.id, subgroupEntry.folderId)
    }

    /**
     * The cipher/KDF permutations this build can open without a key file, proving the container
     * variation is not what decides whether an import works.
     */
    @Test
    fun `an uncompressed AES and an uncompressed ChaCha20 container both open with the same password`() {
        listOf(
            "keepass/pykeepass/test4_aes_uncompressed.kdbx",
            "keepass/pykeepass/test4_chacha20_uncompressed.kdbx",
        ).forEach { path ->
            val parsed = KdbxImporter.parse(RealWorldCorpus.bytes(path), "password".toCharArray()).orFail(path)
            assertEquals(1, parsed.items.size, "$path item count")
            assertEquals("foo", parsed.items.single().title, "$path entry title, as the container spells it")
        }
    }

    /**
     * Argon2id is what KeePassXC has written by default since 2.6.6, and KeePassJava2 2.2.4 cannot
     * derive it — [Argon2idKdbxHeader] is what makes this file openable at all, and an *unopenable*
     * Argon2id database used to be reported as damaged. This is the real-world file behind
     * [KdbxArgon2idTest]'s synthetic one; the container genuinely holds no entries.
     */
    @Test
    fun `a real Argon2id container opens`() {
        val path = "keepass/pykeepass/test4_argon2id.kdbx"
        val parsed = KdbxImporter.parse(RealWorldCorpus.bytes(path), "password".toCharArray()).orFail(path)
        assertEquals(0, RealWorldKdbxOracle.contents(RealWorldCorpus.bytes(path), "password").entries)
        assertEquals(emptyList(), parsed.items)
    }

    /**
     * Twofish is one of KeePass's three ciphers and KeePassJava2 registers only two, so both of
     * these used to answer [ImportFailureReason.KdbxSupportBroken] — a message asking their owner
     * to report a bug about a well-formed file. [TwofishKdbxHeader] builds that stream itself now;
     * the cipher's own evidence, including the published known-answer vectors, is in
     * [KdbxTwofishTest], and what this keeps is the corpus-wide statement that no cipher in the set
     * is refused.
     */
    @Test
    fun `a Twofish container opens like its AES and ChaCha20 siblings`() {
        assertEquals(
            "foo",
            KdbxImporter.parse(
                RealWorldCorpus.bytes("keepass/pykeepass/test4_twofish_uncompressed.kdbx"),
                "password".toCharArray(),
            ).orFail("keepass/pykeepass/test4_twofish_uncompressed.kdbx").items.single().title,
        )
        assertTrue(
            KdbxImporter.parse(
                RealWorldCorpus.bytes("keepass/pykeepass/test4_twofish.kdbx"),
                "password".toCharArray(),
                keyFile = RealWorldCorpus.bytes("keepass/pykeepass/test4.key"),
            ) is Outcome.Success,
            "the compressed Twofish container opens with its password and key file",
        )
    }

    /**
     * **Key files** — the most consequential gap the corpus found, now closed.
     *
     * [PasswordProtectedImporter.parse] took a password and nothing else, and `KdbxCreds` was built
     * from those bytes alone, so a database protected by a password *and* a key file could not be
     * opened however correct the password was. Eight of the pykeepass databases are in exactly that
     * state, across all three key-file formats (classic 32-byte binary `.key`, legacy 64-hex, XML
     * v2 `.keyx`), and every one of them answered [ImportFailureReason.WrongPassword] — not only a
     * refusal but a misleading one, because the password is right.
     *
     * The password beside each file was measured here against the container, not read off a
     * table: `test4_blankpass.kdbx` opens on an **empty** password plus `test4.key`, and
     * `test4_keyx.kdbx` — which `PROVENANCE.md` listed with no password until this was run — needs
     * `password` beside its `.keyx`. Both are recorded there now.
     */
    @Test
    fun `a database protected by a password and a key file opens`() {
        listOf(
            Triple("keepass/pykeepass/test3.kdbx", "keepass/pykeepass/test3.key", "password"),
            Triple("keepass/pykeepass/test4.kdbx", "keepass/pykeepass/test4.key", "password"),
            Triple("keepass/pykeepass/test4_aes.kdbx", "keepass/pykeepass/test4.key", "password"),
            Triple("keepass/pykeepass/test4_aeskdf.kdbx", "keepass/pykeepass/test4.key", "password"),
            Triple("keepass/pykeepass/test4_chacha20.kdbx", "keepass/pykeepass/test4.key", "password"),
            Triple("keepass/pykeepass/test4_hex.kdbx", "keepass/pykeepass/test4_hex.key", "password"),
            Triple("keepass/pykeepass/test4_keyx.kdbx", "keepass/pykeepass/test4_keyx.keyx", "password"),
            Triple("keepass/pykeepass/test4_blankpass.kdbx", "keepass/pykeepass/test4.key", ""),
        ).forEach { (database, keyFile, password) ->
            assertTrue(RealWorldCorpus.bytes(keyFile).isNotEmpty(), "$keyFile is checked in and ready")
            val parsed = KdbxImporter.parse(
                RealWorldCorpus.bytes(database),
                password.toCharArray(),
                keyFile = RealWorldCorpus.bytes(keyFile),
            ).orFail(database)
            // Every container's own live <Entry> count, read by decrypting it separately — the
            // invariant the rest of this class holds every KDBX to.
            assertEquals(
                RealWorldKdbxOracle.contents(
                    RealWorldCorpus.bytes(database),
                    password,
                    RealWorldCorpus.bytes(keyFile),
                ).entries,
                parsed.items.size + parsed.skipped.size,
                "$database entries",
            )
        }
    }

    /**
     * Control for the test above: the key file is the *other half* of the credential, so the right
     * password without it must still be refused — and the refusal must stop claiming the password
     * is simply wrong, which is the lie the corpus caught.
     */
    @Test
    fun `the right password without its key file says a key file may be needed`() {
        val path = "keepass/pykeepass/test4.kdbx"
        assertEquals(
            ImportFailureReason.WrongPassword(keyFileMayBeNeeded = true),
            KdbxImporter.parse(RealWorldCorpus.bytes(path), "password".toCharArray()).failureReason(path),
        )
    }

    /**
     * And with a key file supplied there is nothing left to hedge about: the composite failed, and
     * the user has already given both halves.
     */
    @Test
    fun `a wrong password with a key file is just a wrong password`() {
        val path = "keepass/pykeepass/test4.kdbx"
        assertEquals(
            ImportFailureReason.WrongPassword(keyFileMayBeNeeded = false),
            KdbxImporter.parse(
                RealWorldCorpus.bytes(path),
                "not the password".toCharArray(),
                keyFile = RealWorldCorpus.bytes("keepass/pykeepass/test4.key"),
            ).failureReason(path),
        )
    }

    /**
     * **Gap 14, closed.** KeePass 1.x `.kdb` shares its first four bytes with KDBX, so `detect`
     * says yes and the parse has to be the refusal. It used to be [ImportFailureReason.NotAKdbxFile]
     * — "this file isn't a KeePass database" — which is false about a file that is one, just the
     * previous format. Nothing here can open a `.kdb`; the reason now says which format it is, and
     * its copy names the one remedy that works.
     */
    @Test
    fun `a legacy KeePass 1 kdb is refused as the old format, not as a non-KeePass file`() {
        val path = "keepass/keepassxc/CompositeKey.kdb"
        assertEquals(
            ImportFailureReason.LegacyKeePass1File,
            KdbxImporter.parse(RealWorldCorpus.bytes(path), "password".toCharArray()).failureReason(path),
        )
        // Control: a file that really is not a KeePass database keeps the reason that says so.
        assertEquals(
            ImportFailureReason.NotAKdbxFile,
            KdbxImporter.parse("not a database at all".toByteArray(), "password".toCharArray())
                .failureReason("a non-database"),
        )
    }

    /**
     * FORMAT GAP — KeePass 2's "Generic XML" export, which is a plaintext `<KeePassFile>` document
     * rather than a container. No importer here claims it, and [KdbxImporter] correctly says it is
     * not a KDBX file. `NewDatabase.xml` is the same shape, shipped beside its own `.kdbx`.
     */
    @Test
    fun `a KeePass2 XML export is not mistaken for a database`() {
        listOf(
            "keepass/keepass2xml/TestData.xml",
            "keepass/keepass2xml/TestData1.xml",
            "keepass/keepass2xml/TestData2.xml",
            "keepass/keepass2xml/TestDataWithProtectedFields.xml",
            "keepass/keepassxc/NewDatabase.xml",
        ).forEach { path ->
            // `<Group>` rather than `<KeePassFile>` or `<Root>`: two of these four are upstream's
            // deliberately truncated variants (TestData1 has no `<Root>`, TestData2 no
            // `<KeePassFile>`), and the group tree is the one element all five share.
            assertTrue(
                "<Group>" in RealWorldCorpus.text(path),
                "$path is no longer the KeePass2 XML document this test is about",
            )
            assertEquals(false, KdbxImporter.detect(RealWorldCorpus.bytes(path)), "$path detect")
        }
    }
}

package com.localkeep.core.import

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Login
import com.localkeep.core.model.PasswordHistory
import com.localkeep.core.model.PasswordHistoryEntry
import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import org.linguafranca.pwdb.Credentials
import org.linguafranca.pwdb.kdbx.Helpers
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import org.linguafranca.pwdb.kdbx.dom.DomEntryWrapper
import org.linguafranca.pwdb.kdbx.dom.DomSerializableDatabase
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

object KdbxImporter : PasswordProtectedImporter {
    override val sourceFormat: SourceFormat = SourceFormat.KDBX

    // KDBX base signature, per the format spec: 0x9AA2D903 little-endian.
    private val KDBX_MAGIC = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte())

    // The *second* signature dword, which is what tells the two KeePass generations apart:
    // 0xB54BFB65 is KeePass 1.x's `.kdb`, 0xB54BFB66/67 the KDBX this build reads. Both share
    // KDBX_MAGIC, so `detect` says yes to a `.kdb` and the refusal has to come from the parse.
    private val KDB1_SIGNATURE = byteArrayOf(0x65, 0xFB.toByte(), 0x4B, 0xB5.toByte())

    override fun detect(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(KDBX_MAGIC)

    /** [PasswordProtectedImporter] contract: the password-free overload always fails. */
    override fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> =
        Outcome.Failure(
            AppError.Validation(
                "KDBX requires a password — call parse(bytes, password)",
                ImportRejection(ImportFailureReason.Unexpected),
            ),
        )

    override fun parse(
        bytes: ByteArray,
        password: CharArray,
        titles: ImportDefaultTitles,
        keyFile: ByteArray?,
    ): Outcome<ParsedImport> {
        // Encode CharArray -> UTF-8 bytes WITHOUT an intermediate String: a String is immutable and
        // cannot be zeroized, so `String(password)` would leave an un-scrubbable plaintext copy of
        // the master password on the heap, defeating the point of taking a CharArray. Both the
        // CharArray and the intermediate bytes are wiped in `finally`.
        if (bytes.size >= 8 && bytes.copyOfRange(4, 8).contentEquals(KDB1_SIGNATURE)) {
            // Answered before the password is even encoded: nothing about a `.kdb` is openable
            // here, and NotAKdbxFile — "this file isn't a KeePass database" — is the wrong sentence
            // for a file that is one, just the previous format (gap 14).
            password.fill('\u0000')
            return Outcome.Failure(
                AppError.Validation(
                    "this is a KeePass 1 database, which this build cannot open",
                    ImportRejection(ImportFailureReason.LegacyKeePass1File),
                ),
            )
        }
        val pwBytes = charArrayToUtf8Bytes(password)
        var keyFileKey: ByteArray? = null
        try {
            keyFileKey = keyFile?.let { KdbxKeyFile.keyBytes(it) }
            var lastFailure: Outcome.Failure? = null
            compositeKeys(pwBytes, keyFileKey).forEach { key ->
                val outcome = open(key, bytes, titles, keyFileSupplied = keyFile != null)
                // Only a credential refusal is worth trying the next composition. A damaged file, a
                // KDF too large for the device or a broken build all say the same thing whichever
                // key was derived, and re-deriving would double a wait the user is already sitting
                // through, for nothing.
                if (outcome !is Outcome.Failure || reasonOf(outcome) !is ImportFailureReason.WrongPassword) {
                    return outcome
                }
                lastFailure = outcome
            }
            return checkNotNull(lastFailure) { "compositeKeys never returns an empty list" }
        } catch (t: Throwable) {
            return Outcome.Failure(describeFailure(t, keyFileSupplied = keyFile != null))
        } finally {
            pwBytes.fill(0)
            keyFileKey?.fill(0)
            password.fill('\u0000') // zeroize — never retained past this call
        }
    }

    private fun reasonOf(failure: Outcome.Failure): ImportFailureReason? =
        (failure.error.cause as? ImportRejection)?.reason

    /**
     * The KeePass composite key: SHA-256 over each supplied credential's own 32-byte key, in order.
     *
     * With no key file this is what `KdbxCreds(byte[])` already builds — SHA-256(SHA-256(password))
     * — and it is still used, so nothing about the password-only path changes. With one, the
     * components are concatenated before the outer hash, which is the shape of
     * `KdbxCreds(byte[], InputStream)`; it is rebuilt here rather than called because that
     * constructor reads the key file through the library's own XPath-backed reader, which never
     * checks a `.keyx` `Hash` attribute (see [KdbxKeyFile]).
     *
     * An **empty** password beside a key file returns *two* candidates, and that is not
     * indecision — the writers genuinely disagree. KeePass and pykeepass compose a database saved
     * with an empty password from SHA-256("") as a real component (measured: `test4_blankpass.kdbx`
     * opens on SHA-256(SHA-256("") ‖ keyFileKey) and on nothing else), while a database whose key
     * really is the key file alone has no password component at all and is SHA-256(keyFileKey).
     * Nothing in the container distinguishes them before the check, so both are tried, in the order
     * the corpus proves is the common one. The cost lands only on a blank password that fails, and
     * the alternative is refusing one of the two shapes outright.
     */
    private fun compositeKeys(pwBytes: ByteArray, keyFileKey: ByteArray?): List<ByteArray> {
        if (keyFileKey == null) return listOf(KdbxCreds(pwBytes).key)
        val withPassword = sha256(sha256(pwBytes), keyFileKey)
        return if (pwBytes.isEmpty()) listOf(withPassword, sha256(keyFileKey)) else listOf(withPassword)
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    /** One attempt at one composite key: open the container and walk it into vault items. */
    private fun open(
        key: ByteArray,
        bytes: ByteArray,
        titles: ImportDefaultTitles,
        keyFileSupplied: Boolean,
    ): Outcome<ParsedImport> {
        try {
            val db = load(Credentials { key }, bytes)
            val items = mutableListOf<VaultItem>()
            val folders = mutableListOf<Folder>()
            val skipped = mutableListOf<SkippedEntry>()
            try {
                val references = FieldReferences(db.rootGroup)
                // The root group is the vault itself, not a folder: KeePass always has one and naming
                // it would put every imported entry inside a folder called "Database".
                childElements(db.rootGroup, "Group").forEach {
                    collectGroup(it, parentId = null, depth = 1, db, references, items, folders, skipped, titles)
                }
                childElements(db.rootGroup, "Entry").forEach {
                    items.add(mapEntry(it, folderId = null, shell = db.shell, references = references))
                }
            } catch (t: Throwable) {
                // Past `load`, nothing that fails here is evidence about the user's file or their
                // password: the header verified, the credentials were right, and the database is
                // already open. The outer catch sent this whole region through [describeFailure],
                // whose else arm says "the file looks damaged" and steers the user toward
                // re-exporting or picking a different file — neither of which can fix anything on
                // this side of `load`, and both of which blame a file that has just proved itself
                // readable. [ImportFailureReason.KdbxSupportBroken] says the true thing instead:
                // the KDBX support in this build failed, and it is worth reporting.
                return Outcome.Failure(
                    AppError.Unknown(
                        "KDBX entries could not be read in this build of LocalKeep",
                        ImportRejection(ImportFailureReason.KdbxSupportBroken, t),
                    ),
                )
            }
            // Every KDBX entry that is *live* maps to a Login, so the only entries in `skipped` are
            // the ones the user had already deleted — see [collectGroup]. That is not the same as
            // "nothing is dropped": entry binaries are, which `customFields` documents and the
            // KeePass caveats now disclose. This comment claimed the stronger thing until
            // 2026-08-30 and contradicted the one below. (What it *used* to drop was custom string
            // fields, and those are now carried.)
            return Outcome.Success(
                ParsedImport(
                    items = items,
                    skipped = skipped,
                    folders = folders,
                    unresolvedFieldReferenceCount = items.count { FieldReferences.holdsLiteralReference(it) },
                ),
            )
        } catch (t: Throwable) {
            return Outcome.Failure(describeFailure(t, keyFileSupplied = keyFileSupplied))
        }
    }

    /**
     * Turns a parse failure into something that names the actual cause.
     *
     * Only reached for failures at or before [load] — everything after it is
     * attributed to this build by [parse]'s own catch, because a file that has already decrypted
     * cannot be the thing at fault.
     *
     * This exists because of the B4a defect: the whole class of "the library cannot run here"
     * failure used to be reported as *"wrong password or corrupted file"*, so a missing XML API on
     * Android looked to every user like their own password being wrong. A linkage error is never
     * the user's fault and must never be phrased as though it were — it is a bug in this app.
     *
     * The credential messages below are not all of one kind, and the difference matters:
     *
     * - *"Header HMAC does not match"* is KDBX4's pre-decryption header check. It fails exactly
     *   when the key was derived from the wrong password.
     * - *"Inconsistent stream start bytes"* is KDBX 3.1's credential check. That format has no
     *   pre-decryption verification at all: the library decrypts and then compares the stored
     *   start bytes, so this is the only signal a 3.x database gives that the password was wrong.
     *   Unclassified it used to land in the `else` arm and tell the user, mid-migration, that an
     *   intact database looked damaged — the inverted form of the B4a defect above.
     * - *"Header hash does not match"* is KDBX3's header-integrity check, and it runs only *after*
     *   decryption succeeded, i.e. only when the password was right and the header was altered. It
     *   is kept on this arm because that is where it has always been and moving it is a separate
     *   behaviour change, but it is not evidence of a wrong password.
     *
     * A *block* HMAC failure is deliberately not in that list — the header already verified, so the
     * password was right and the payload is genuinely damaged.
     *
     * Residual, unavoidable: for KDBX 3.1 a genuinely corrupt ciphertext opened with the *right*
     * password also fails the start-byte check, so it reads as a wrong password. The format offers
     * nothing that distinguishes the two, and KeePass's own message says the same thing.
     */
    internal fun describeFailure(t: Throwable, keyFileSupplied: Boolean = false): AppError {
        val message = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        // ImportRejection is normally thrown, not constructed and returned like this — it is reused
        // here purely as a typed carrier for `reason` (see its kdoc), since this failure was caught
        // rather than authored by a deliberate `throw` in this module.
        return when {
            // First, and deliberately not message-based: an OutOfMemoryError carries a message about
            // an allocation, which matches none of the patterns below and so fell to the else arm —
            // "the file looks damaged", about a file that is fine. The whole cause chain is checked
            // because the KDBX library wraps what the KDF throws.
            generateSequence(t) { it.cause }.any { it is OutOfMemoryError } ->
                AppError.Validation(
                    "this KeePass database's key derivation needs more memory than this device allows",
                    ImportRejection(ImportFailureReason.KdbxKdfTooLarge, t),
                )

            t is LinkageError || t is ClassNotFoundException ->
                AppError.Unknown(
                    "KDBX support is broken in this build of LocalKeep",
                    ImportRejection(ImportFailureReason.KdbxSupportBroken, t),
                )

            // An algorithm this build does not implement. KeePassJava2 2.2.4 registers exactly one
            // Argon2 variant (Argon2d, EF636DDF-8C29-444B-91F7-A9A403E30A0C) and two ciphers (AES,
            // ChaCha20), and refuses anything else with a message beginning "Unknown Cipher UUID" —
            // `Encryption$KeyDerivationFunction.getKdf` throws it as an IllegalArgumentException
            // for an unknown KDF, `KdbxHeader.setCipherUuid` as an IllegalStateException for an
            // unknown cipher. Neither matched a pattern below, so an intact Argon2id KDBX4 — what
            // KeePassXC has written by default since 2.6.6 — fell to the else arm and was called
            // damaged, steering the user to re-export a file that is fine (fixed 2026-09-13).
            //
            // **Neither Argon2id nor Twofish reaches here any more**: [Argon2idKdbxHeader] takes
            // that KDF's UUID away from the library before it can be resolved and
            // [TwofishKdbxHeader] does the same for that cipher's, with KdbxArgon2idTest,
            // KdbxTwofishTest and the real-world corpus proving both containers import. What is
            // left on this arm is every *other* unimplemented algorithm — a plugin cipher, a KDF
            // nobody has written yet — for which the message is still the true one.
            //
            // Matched on the *prefix* of each individual throwable's own message, and never
            // surfaced: setCipherUuid builds its text by appending the UUID it read out of the
            // untrusted file, and file-derived bytes are never echoed into the UI.
            // [ImportFailureReason.KdbxSupportBroken] rather than a new reason because it is the
            // true statement — this build's KDBX support cannot open the file, it is not the
            // password and not the file — and its copy already asks for a report, which is the one
            // action that fixes it.
            generateSequence(t) { it.cause }.any {
                (it is IllegalArgumentException || it is IllegalStateException) &&
                    it.message?.startsWith("Unknown Cipher UUID") == true
            } ->
                AppError.Unknown(
                    "this KeePass database uses an algorithm this build of LocalKeep cannot open",
                    ImportRejection(ImportFailureReason.KdbxSupportBroken, t),
                )

            // A key file the user picked that cannot be read as one. Raised by [KdbxKeyFile]
            // before any derivation runs, so it is the one key-file failure that is provable: the
            // password has not been tested yet and is not what is wrong.
            generateSequence(t) { it.cause }.any { it is KdbxKeyFile.Unreadable } ->
                AppError.Validation(
                    "this key file could not be read",
                    ImportRejection(ImportFailureReason.UnreadableKeyFile, t),
                )

            "Header HMAC does not match" in message ||
                "Header hash does not match" in message ||
                "Inconsistent stream start bytes" in message ->
                AppError.Validation(
                    "wrong password for this KeePass database",
                    // KDBX verifies the whole composite key at once, so this same failure is what a
                    // database wanting a key file gives back when none was supplied. With no key
                    // file to blame, the reason has to keep both possibilities open — see
                    // [ImportFailureReason.WrongPassword.keyFileMayBeNeeded].
                    ImportRejection(ImportFailureReason.WrongPassword(keyFileMayBeNeeded = !keyFileSupplied), t),
                )

            "Magic number did not match" in message ->
                AppError.Validation(
                    "this file is not a KeePass database",
                    ImportRejection(ImportFailureReason.NotAKdbxFile, t),
                )

            else ->
                AppError.Validation(
                    "the KeePass database could not be read — the file looks damaged",
                    ImportRejection(ImportFailureReason.DamagedKdbxFile, t),
                )
        }
    }

    /**
     * Encodes without copying into a String, and wipes what it can of what the encoder allocated.
     *
     * The buffer handed back here is a *third* copy of the password, next to the caller's CharArray
     * and the `out` array — both of which [parse] wipes in its `finally`. This one had no owner, so
     * it was left behind. Wiping it mirrors `core/crypto`'s `Passwords.wipe`, which is this
     * project's convention for exactly this; it is mirrored rather than called because `:core:crypto`
     * is not on this module's classpath (see build.gradle.kts).
     *
     * Deliberately partial, and stated so rather than glossed: `CharsetEncoder.encode` grows its
     * buffer by *reallocating*, so any earlier array is already unreachable and cannot be reached to
     * wipe, and `KdbxCreds` and `DomDatabaseWrapper` copy the bytes again inside the library. So
     * this removes one live copy from the heap — hygiene consistency with the rest of the codebase,
     * not a guarantee. `Zeroizer` already says runtime copies cannot be guaranteed, and the attacker
     * who could read them is T3 in the threat model, which is out of scope.
     */
    private fun charArrayToUtf8Bytes(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        // `array()` is the whole backing store, not just [position, limit) — which is what we want:
        // the encoder may have written past the limit before flipping. Guarded because a read-only
        // or direct buffer has no accessible array; the heap buffer `encode` returns always does.
        if (bb.hasArray() && !bb.isReadOnly) bb.array().fill(0)
        return out
    }

    /**
     * What one KDBX file yields: the `<Group>` element the whole tree hangs off, and the one fact
     * about the database that lives only in `Meta`.
     *
     * [shell] is an empty database handed to every [DomEntryWrapper] built below, because its
     * constructor takes one and the entry reader consults it for nothing this importer does — the
     * protected values were already decrypted in place by `DomSerializableDatabase.load`. It is
     * *not* the database being imported, so nothing may ask it a question about the file.
     */
    private class LoadedKdbx(
        val rootGroup: Element,
        /** `Meta/RecycleBinUUID`, or null when the file names no bin (absent, or the all-zero id). */
        val recycleBinId: UUID?,
        val shell: DomDatabaseWrapper,
    )

    /**
     * Opens the container, bypassing `DomDatabaseWrapper.load` for two reasons that both need it.
     *
     * `DomDatabaseWrapper.load` hard-codes `new KdbxStreamFormat()`, whose header is the stock
     * [org.linguafranca.pwdb.kdbx.KdbxHeader] — so an Argon2id or Twofish database could not be
     * opened through it at all (see [Argon2idKdbxHeader] and [TwofishKdbxHeader]). And the wrapper keeps its `Meta` element private, while
     * the recycle-bin id lives nowhere else: the library's own `isRecycleBin()` and
     * `getRecycleBin()` both look the element up as `RecycleBinUuid` while KDBX spells it
     * `RecycleBinUUID`, so on a real database both silently answer "no bin" (measured 2026-09-13 by
     * writing a database with a bin and reading it back — every group reported `isRecycleBin=false`,
     * and `getRecycleBin()` fell through to *creating* a fresh group).
     *
     * `DomSerializableDatabase` is the same object the wrapper loads into and decrypts through, so
     * nothing about how the file is read changes here — only which header does the key derivation,
     * and what this module can see afterwards.
     */
    private fun load(creds: Credentials, bytes: ByteArray): LoadedKdbx {
        val database = DomSerializableDatabase.createEmptyDatabase()
        KdbxStreamFormat(TwofishKdbxHeader()).load(database, creds, ByteArrayInputStream(bytes))
        val keePassFile = database.doc.documentElement
        val root = childElements(keePassFile, "Root").firstOrNull()
            ?: throw IllegalStateException("KDBX document has no Root element")
        val rootGroup = childElements(root, "Group").firstOrNull()
            ?: throw IllegalStateException("KDBX Root element has no Group")
        val meta = childElements(keePassFile, "Meta").firstOrNull()
        return LoadedKdbx(
            rootGroup = rootGroup,
            recycleBinId = meta?.let { recycleBinId(it) },
            shell = DomDatabaseWrapper(),
        )
    }

    /**
     * The group KeePass files deleted entries into, read from `Meta/RecycleBinUUID`.
     *
     * The all-zero UUID is KeePass's own "there is no bin", written by every database that has
     * never had one, so it is filtered here rather than compared against real group ids.
     * `RecycleBinEnabled` is deliberately *not* consulted: turning recycling off stops new deletes
     * going to the bin, it does not resurrect what is already in it, and a user who imported those
     * entries would be getting back things they deleted years ago.
     */
    private fun recycleBinId(meta: Element): UUID? {
        val encoded = childElements(meta, "RecycleBinUUID").firstOrNull()?.textContent?.trim()
        if (encoded.isNullOrEmpty()) return null
        val id = runCatching { Helpers.uuidFromBase64(encoded) }.getOrNull() ?: return null
        return id.takeIf { it.mostSignificantBits != 0L || it.leastSignificantBits != 0L }
    }

    /** Direct `<name>` children of [parent]. Direct only — `<History>` nests whole `<Entry>` elements. */
    private fun childElements(parent: Element, name: String): List<Element> {
        val children = parent.childNodes
        val out = mutableListOf<Element>()
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == name) out += node
        }
        return out
    }

    private fun childText(parent: Element, name: String): String? =
        childElements(parent, name).firstOrNull()?.textContent

    /**
     * Maps one KeePass group to a folder and walks into it.
     *
     * KDBX is the one source format with a real tree, and it used to be flattened: every entry
     * arrived at the top level and the organisation the user had built in KeePass — often years of
     * it — was simply not in the import.
     *
     * [depth] is clamped rather than refused. A KeePass tree can nest arbitrarily and this app's
     * cannot, so groups past the limit become children of the deepest level that fits: their
     * entries stay together and stay reachable, which is what the nesting was for. Refusing the
     * import outright, or dropping those entries, would both be worse than a slightly flatter tree.
     *
     * The Recycle Bin is the one group that does not become a folder. Its entries are things the
     * user deleted in KeePass, and importing them made each one live again — searchable, offered by
     * autofill, and counted by the health dashboard — inside a folder named after the bin. Dropping
     * them silently would be the other half of the same defect, so every entry in that subtree is
     * named in `skipped` instead: [SkipReason.SourceRecycleBin], which the wizard's preview groups
     * and counts like any other reason.
     */
    private fun collectGroup(
        group: Element,
        parentId: UUID?,
        depth: Int,
        db: LoadedKdbx,
        references: FieldReferences,
        items: MutableList<VaultItem>,
        folders: MutableList<Folder>,
        skipped: MutableList<SkippedEntry>,
        titles: ImportDefaultTitles,
    ) {
        if (db.recycleBinId != null && groupId(group) == db.recycleBinId) {
            collectDeleted(group, skipped, titles)
            return
        }
        val tooDeep = depth > FolderTree.MAX_DEPTH
        val folder = if (tooDeep) {
            null
        } else {
            Folder(id = UUID.randomUUID(), name = childText(group, "Name").orEmpty().ifBlank { titles.untitledFolder }, parentId = parentId)
                .also(folders::add)
        }
        // When clamped, the entries join the deepest folder that did fit rather than the root:
        // "somewhere near where it was" beats "back at the top".
        val target = folder?.id ?: parentId
        childElements(group, "Entry").forEach {
            items.add(mapEntry(it, folderId = target, shell = db.shell, references = references))
        }
        childElements(group, "Group").forEach {
            collectGroup(it, target, depth + 1, db, references, items, folders, skipped, titles)
        }
    }

    /** Every entry in the bin's subtree, named rather than vanished. Sub-groups of the bin count too. */
    private fun collectDeleted(group: Element, skipped: MutableList<SkippedEntry>, titles: ImportDefaultTitles) {
        childElements(group, "Entry").forEach { entry ->
            skipped += SkippedEntry(
                title = property(entry, "Title").orEmpty().ifBlank { titles.untitledItem },
                reason = SkipReason.SourceRecycleBin,
            )
        }
        childElements(group, "Group").forEach { collectDeleted(it, skipped, titles) }
    }

    private fun groupId(group: Element): UUID? =
        childText(group, "UUID")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Helpers.uuidFromBase64(it) }.getOrNull() }

    /** One `<String><Key>name</Key><Value>…</Value></String>` of an entry, already decrypted. */
    private fun property(entry: Element, name: String): String? =
        childElements(entry, "String")
            .firstOrNull { childText(it, "Key") == name }
            ?.let { childText(it, "Value") }

    private fun mapEntry(
        element: Element,
        folderId: UUID?,
        shell: DomDatabaseWrapper,
        references: FieldReferences,
    ): VaultItem {
        val entry = DomEntryWrapper(element, shell, false)
        val now = Instant.now()
        val totp = findTotp(entry)
        // Read the entry's own KDBX Times element rather than stamping the import moment.
        // BitwardenJsonImporter documents why (its own comment, ~L310-321): stamping every item with
        // `now` made a ten-year-old password look brand new, so the security dashboard's "old
        // passwords" check — a date comparison over `passwordChangedAt ?: updatedAt`, and importers
        // never set passwordChangedAt — reported nothing to fix right after an import. KeePassJava2's
        // Entry exposes CreationTime/LastModificationTime; fall back to `now` only when a source
        // element is genuinely absent.
        val createdAt = entry.creationTime?.toInstant() ?: now
        val updatedAt = entry.lastModificationTime?.toInstant() ?: now
        // Field references are resolved here rather than on the raw element, so a reference in a
        // *history* revision or in a custom field is left exactly as the file wrote it: the five
        // standard fields are the ones KeePass's own editor offers the {REF:} button for, and the
        // ones this app renders as a username or a password rather than as free text.
        return VaultItem(
            id = UUID.randomUUID(),
            title = references.resolve(entry.title.orEmpty(), element),
            folderId = folderId,
            tags = tags(element),
            createdAt = createdAt,
            updatedAt = updatedAt,
            customFields = customFields(entry, consumed = STANDARD_PROPERTIES + totp.consumed),
            content = Login(
                username = references.resolve(entry.username.orEmpty(), element),
                password = references.resolve(entry.password.orEmpty(), element),
                // The *unresolved* password is what history is compared against: a snapshot is
                // dropped when it repeats the live password, and the file writes both sides of
                // that comparison as the same raw token.
                passwordHistory = passwordHistory(element, shell, current = entry.password.orEmpty(), undatedAt = updatedAt),
                uris = entry.url?.takeIf { it.isNotBlank() }
                    ?.let { listOf(UriMatch(uri = references.resolve(it, element))) } ?: emptyList(),
                totp = totp.value,
                notes = references.resolve(entry.notes.orEmpty(), element),
            ),
        )
    }

    /**
     * KeePass field references — `{REF:U@I:5060E2E029AA11E88AA80021CCB990C2}` — resolved against the
     * database they point into.
     *
     * KeePass lets one entry borrow a field from another instead of copying it, which is what a user
     * does when several logins share one account: the borrowed field is stored as this token and
     * KeePass substitutes it on use. Nothing here substituted anything, so the token itself was
     * imported as the username — an unusable placeholder, on an item the preview showed as an
     * ordinary login, with nothing saying so (1A-F7, 2026-09-20; `test4.kdbx`'s `foobar_entry -
     * Clone`).
     *
     * The syntax is `{REF:<wanted>@<searchIn>:<text>}`, both codes drawn from `T`(itle),
     * `U`(sername), `P`(assword), `A` (the URL — KeePass's own letter for it), `N`(otes) and `I`
     * (the entry's UUID), with `O` a further search-in code meaning "any other string field".
     * `@I` is an exact match on the 32-hex form of an entry's UUID; every other search is KeePass's
     * own substring search over that field, case-insensitive, first match in document order.
     *
     * Three deliberate limits, each because the alternative is worse than a literal token:
     *
     * - **A bounded number of hops.** A borrowed value may itself be a reference — cloning a clone
     *   is how that arises, and `test4.kdbx` has one — so each is followed, at most [MAX_HOPS]
     *   deep. The bound is what makes a cycle (`A` borrows from `B` borrows from `A`) terminate,
     *   rather than a visited-set that has to be right.
     * - **The referring entry is never its own target**, at every hop.
     * - **Unresolvable references stay exactly as the file wrote them** and are counted into
     *   [ParsedImport.unresolvedFieldReferenceCount], which the wizard reports. Substituting an
     *   empty string would turn "this field points somewhere I cannot follow" into "this field is
     *   blank" — and the export it came from is the next thing the wizard offers to delete.
     */
    internal class FieldReferences(rootGroup: Element) {

        private val entries: List<Element> = buildList { collect(rootGroup) }

        private fun MutableList<Element>.collect(group: Element) {
            addAll(childElements(group, "Entry"))
            childElements(group, "Group").forEach { collect(it) }
        }

        /** [value] with every reference this database can answer substituted; the rest untouched. */
        fun resolve(value: String, self: Element): String = resolve(value, self, MAX_HOPS)

        private fun resolve(value: String, self: Element, hopsLeft: Int): String {
            if (hopsLeft == 0 || !value.contains(TOKEN_START, ignoreCase = true)) return value
            return PATTERN.replace(value) { match ->
                val (wanted, searchIn, text) = match.destructured
                val target = target(searchIn.single().uppercaseChar(), text, self)
                    ?: return@replace match.value
                val borrowed = field(target, wanted.single().uppercaseChar())
                    ?: return@replace match.value
                // The borrowed value may itself be a reference — cloning a clone is how that
                // happens, and `test4.kdbx` has one. Followed from the *target's* point of view, so
                // each hop excludes its own entry, and bounded, which is what makes a cycle
                // terminate without a visited-set that has to be right.
                resolve(borrowed, target, hopsLeft - 1)
            }
        }

        /**
         * First entry in document order, other than the referring one, whose [searchIn] field
         * answers [text] — exact for a UUID, substring for everything else, as KeePass searches.
         *
         * A linear scan rather than an index, and not only for the size of it: KeePass clones an
         * entry *with its UUID*, so `@I` is not unique in a real database — `test4.kdbx` holds four
         * entries sharing `5060E2E0…`, one of which is itself a referrer. A map keyed on the UUID
         * keeps one of them, and when the one it keeps is the referrer the reference resolves to
         * nothing at all. The scan only runs for a value that actually contains a token, which is
         * why an O(entries) lookup is affordable here.
         */
        private fun target(searchIn: Char, text: String, self: Element): Element? {
            val wanted = text.trim()
            return entries.firstOrNull { candidate ->
                if (candidate === self) return@firstOrNull false
                val value = field(candidate, searchIn) ?: return@firstOrNull false
                if (searchIn == 'I') value.equals(wanted, ignoreCase = true) else value.contains(wanted, ignoreCase = true)
            }
        }

        private fun field(entry: Element, wanted: Char): String? = when (wanted) {
            'T' -> property(entry, "Title")
            'U' -> property(entry, "UserName")
            'P' -> property(entry, "Password")
            'A' -> property(entry, "URL")
            'N' -> property(entry, "Notes")
            'I' -> entryId(entry)?.let { hex(it) }
            // 'O' is a search-in code only — "any other string field" names no single value to
            // borrow — so a `{REF:O@…}` has no answer and stays literal.
            else -> null
        }

        private fun entryId(entry: Element): UUID? =
            childText(entry, "UUID")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { Helpers.uuidFromBase64(it) }.getOrNull() }

        private fun hex(id: UUID): String = id.toString().replace("-", "").uppercase()

        companion object {
            private const val TOKEN_START = "{REF:"

            /**
             * How many references deep this will follow. KeePass itself resolves chains; three is
             * a clone of a clone of a clone, which is past anything the corpus or the format's own
             * documentation shows, and the bound is what a cycle runs out of.
             */
            private const val MAX_HOPS = 3

            /**
             * Both braces are escaped, and the closing one is not optional.
             * `java.util.regex` accepts a bare `}` outside a quantifier; Android's ICU engine does
             * not, and throws `PatternSyntaxException` from this companion's initializer — which
             * arrives at the entry walk as `ExceptionInInitializerError` and is reported as
             * "KDBX support is broken in this build". Measured on emulator-5556 (API 29,
             * 2026-09-21): every JVM test in this module passed against the unescaped form while
             * *every* KDBX import on the device failed, which is the exact shape of
             * "a green build is not evidence".
             */
            private val PATTERN = Regex("\\{REF:([A-Za-z])@([A-Za-z]):([^}]*)\\}")

            /**
             * Whether an item still carries a reference token in a field the user will read as a
             * value. Counted after mapping rather than tallied during it, so a token the file wrote
             * by hand — never a valid reference, and just as unusable — is reported too.
             */
            fun holdsLiteralReference(item: VaultItem): Boolean {
                val login = item.content as? Login ?: return false
                return (listOf(item.title, login.username, login.password, login.notes) + login.uris.map { it.uri })
                    .any { PATTERN.containsMatchIn(it) }
            }
        }
    }

    /**
     * The entry's previous passwords, read from its `<History>` element.
     *
     * KeePass keeps a whole prior copy of an entry per edit, and this importer skipped the element
     * outright — so a KeePassXC user's password history arrived empty while the Bitwarden and
     * 1Password importers both filled [Login.passwordHistory], and nothing said so (S2, 2026-09-15).
     *
     * History entries become history and never items: [childElements] is direct-children-only for
     * exactly this reason (see its comment), so the count the wizard shows is unaffected. Each
     * historic `<Entry>` is read through a [DomEntryWrapper] rather than by hand, so the password
     * is unprotected by the same code path as the live one and `LastModificationTime` is parsed
     * whichever of the two KDBX date encodings the file used.
     *
     * [PasswordHistory.trim] does the ordering and the cap: newest first, five entries, the same
     * bound every other door into this field goes through. A historic copy whose password equals
     * the [current] one is dropped — KeePass snapshots the entry on *any* edit, including a rename,
     * so the newest snapshot very often repeats the live password, and listing it as a previous one
     * is a claim the file does not make. Blank passwords are dropped for the reason
     * [PasswordHistory.append] gives.
     */
    private fun passwordHistory(
        element: Element,
        shell: DomDatabaseWrapper,
        current: String,
        undatedAt: Instant,
    ): List<PasswordHistoryEntry> = PasswordHistory.trim(
        childElements(element, "History")
            .flatMap { childElements(it, "Entry") }
            .mapNotNull { historic ->
                val wrapper = DomEntryWrapper(historic, shell, false)
                val password = wrapper.password.orEmpty()
                if (password.isEmpty() || password == current) return@mapNotNull null
                PasswordHistoryEntry(
                    password = password,
                    changedAt = wrapper.lastModificationTime?.toInstant() ?: undatedAt,
                )
            },
    )


    /**
     * The entry's own tags, which are a `<Tags>` child of `<Entry>` and not a named string field.
     *
     * That is why `DomEntryWrapper.getPropertyNames` never saw them and `customFields` could not
     * rescue them either: a KeePassXC user's whole cross-cutting filing scheme — the labels that
     * cut across the group tree, which is exactly what groups cannot express — arrived empty, on an
     * import that reported full success. [VaultItem.tags] has been a real, wired field the entire
     * time.
     *
     * KeePass writes them separated by `;`, KeePassXC by `,`; both accept either on read, so both
     * are split on here. Blanks are dropped and duplicates collapsed, order preserved.
     */
    private fun tags(element: Element): List<String> =
        childText(element, "Tags").orEmpty()
            .split(';', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** The five KDBX string fields that already have a home on [Login]. */
    private val STANDARD_PROPERTIES = setOf("Title", "UserName", "Password", "URL", "Notes")

    /**
     * Every other string field on the entry, which used to be discarded outright.
     *
     * KeePass entries carry arbitrary named strings, and a KeePassXC user's protected ones are
     * exactly where recovery codes and security answers live — so an import that reported full
     * success was quietly emptying part of every affected entry, and the user found out when they
     * were locked out of an account, typically long after deleting the .kdbx.
     *
     * Everything lands as [CustomFieldType.HIDDEN]. KeePassJava2's `Entry` exposes no per-property
     * protection flag, so this importer cannot tell a protected string from a plain one — and
     * between masking a label that did not need it (one tap to reveal) and rendering a recovery
     * code in the clear, only the second is unrecoverable.
     *
     * KDBX binaries are still dropped. **The caveats did not say so** — that claim was checked on
     * 2026-08-30 and was false: `import_source_keepass_caveat_1/2/3` cover in-memory decryption,
     * key files and 2FA seeds, and none of them mentioned attachments. A fourth caveat now does.
     *
     * Dropping them is not a KDBX-specific gap — `ImportMerger` records that no import format has
     * anywhere to put an attachment — but this importer *could* read them: KeePassJava2's
     * `DomEntryWrapper` implements `getBinaryPropertyNames`/`getBinaryProperty` with real DOM
     * reads, so this is a choice, not a library limitation. What made it worth correcting is the
     * Done step's offer to delete the source `.kdbx`: silently dropping a file and then offering
     * to destroy the only remaining copy of it is a different thing from a documented limitation.
     *
     * The group hierarchy is not dropped — see [collectGroup].
     */
    private fun customFields(entry: DomEntryWrapper, consumed: Set<String>): List<CustomField> =
        entry.propertyNames
            .filter { it !in consumed }
            .mapNotNull { name ->
                // A blank value is not a reason to drop the field: a KeePass custom string exists
                // only because the user made it, so "Security answer" with nothing in it yet is a
                // label they wrote and expect to find after migrating. Dropping it took the name
                // with the value, and field-level losses have no home in `skipped` (which is
                // item-level), so the entry read as fully imported. Only a field with neither a
                // name nor a value is nothing at all — the same rule BitwardenJsonImporter's
                // `mapFields` already applies.
                val value = entry.getProperty(name).orEmpty()
                if (name.isBlank() && value.isBlank()) return@mapNotNull null
                CustomField(name = name, value = value, type = CustomFieldType.HIDDEN)
            }

    /** A TOTP lookup and the property names it read, so they are not also carried as custom fields. */
    private data class TotpLookup(val value: Totp?, val consumed: Set<String>)

    /**
     * KeePass has no single native TOTP field. Three community conventions are checked, in order:
     * 1. KeePassXC: custom string field "TOTP Seed" — a bare Base32 secret or an otpauth:// URI.
     * 2. KeeOtp plugin (classic KeePass2): custom string field "otp" — "key=BASE32&step=30&size=6".
     * 3. KeePass 2.47+ / KeeTrayTOTP / KeeWeb: separate "TimeOtp-*" fields — see [findTimeOtp].
     *
     * A convention field is reported as consumed only when it actually parsed. One that did not is
     * left for [customFields] to carry, so a seed in a shape this parser does not know survives the
     * import as text rather than being dropped by the code that was meant to rescue it.
     */
    private fun findTotp(entry: DomEntryWrapper): TotpLookup {
        entry.getProperty("TOTP Seed")?.let { seed ->
            TotpFieldParser.parse(seed)?.let {
                // KeePassXC writes the period/digits alongside the seed; both belong to the Totp.
                // Its legacy companion format is `<step>;<size>;...`; retain the companion as a
                // custom field when it is malformed so the source value is never lost.
                val settings = entry.getProperty("TOTP Settings")
                val parsedSettings = settings?.split(';')?.takeIf { it.size >= 2 }?.let { parts ->
                    val period = parts[0].toIntOrNull()
                    val digits = parts[1].toIntOrNull()
                    if (period != null && digits != null && period in 1..86_400 && digits in setOf(6, 8)) {
                        period to digits
                    } else {
                        null
                    }
                }
                val value = parsedSettings?.let { (period, digits) ->
                    it.copy(periodSec = period, digits = digits)
                } ?: it
                val consumed = if (settings != null && parsedSettings == null) {
                    setOf("TOTP Seed")
                } else {
                    setOf("TOTP Seed", "TOTP Settings")
                }
                return TotpLookup(value, consumed)
            }
        }
        // No "otp" field is not the end of the search: the third convention below writes no single
        // field at all, so giving up here left a KeePass 2.47 seed to arrive as inert text (fixed
        // 2026-09-13). The two `TotpLookup(null, …)` returns further
        // down stay as they are — an "otp" field that is present and unusable is still an answer.
        val otpField = entry.getProperty("otp") ?: return findTimeOtp(entry)
        if (otpField.startsWith("otpauth://", ignoreCase = true)) {
            val parsed = TotpFieldParser.parse(otpField)
            return TotpLookup(parsed, if (parsed != null) setOf("otp") else emptySet())
        }
        if (otpField.contains("key=")) {
            val params = otpField.split("&").mapNotNull { p ->
                val idx = p.indexOf("=")
                if (idx < 0) null else p.substring(0, idx) to p.substring(idx + 1)
            }.toMap()
            val parsed = params["key"]?.let { TotpFieldParser.parse(it) }
                ?: return TotpLookup(null, emptySet())
            val digits = params["size"]?.toIntOrNull() ?: 6
            val periodSec = params["step"]?.toIntOrNull() ?: 30
            // An out-of-range size/step would build a Totp the generator refuses to render
            // (TotpGenerator requires digits in {6, 8} and periodSec > 0). Leave "otp" unconsumed
            // so customFields() carries the raw value instead of shipping a dead 2FA entry.
            if (digits !in setOf(6, 8) || periodSec !in 1..86_400) {
                return TotpLookup(null, emptySet())
            }
            return TotpLookup(parsed.copy(digits = digits, periodSec = periodSec), setOf("otp"))
        }
        return findTimeOtp(entry)
    }

    /**
     * The third convention: KeePass 2.47's own TOTP fields, also written by KeeTrayTOTP and KeeWeb.
     *
     * Unlike the two above, this one spreads the token over *separate* string fields —
     * `TimeOtp-Secret-Base32` plus optional `TimeOtp-Length`, `TimeOtp-Period` and
     * `TimeOtp-Algorithm` — so [findTotp]'s single-field lookups matched none of it and the seed
     * fell through to [customFields] as a HIDDEN field under its raw source name. The login then
     * imported with no live code at all, while the wizard's own KeePass caveat tells the user their
     * 2FA seeds come across (fixed 2026-09-13).
     *
     * A sibling that is present but unreadable — a non-numeric length, an algorithm this app cannot
     * compute — refuses the whole lookup rather than quietly falling back to a default. An unknown
     * algorithm folded to SHA-1 renders six digits that are confidently wrong, which is worse than
     * no code at all, and this is the same rule [TotpFieldParser] already applies to an otpauth://
     * URI carrying `algorithm=SHA384`. Nothing is lost by refusing: the fields stay unconsumed and
     * [customFields] carries every one of them verbatim.
     */
    private fun findTimeOtp(entry: DomEntryWrapper): TotpLookup {
        val seed = entry.getProperty("TimeOtp-Secret-Base32") ?: return TotpLookup(null, emptySet())
        val base = TotpFieldParser.parse(seed) ?: return TotpLookup(null, emptySet())
        val length = entry.getProperty("TimeOtp-Length")
        val period = entry.getProperty("TimeOtp-Period")
        val algorithm = entry.getProperty("TimeOtp-Algorithm")
        val digits = if (length == null) 6 else length.trim().toIntOrNull()
        val periodSec = if (period == null) 30 else period.trim().toIntOrNull()
        // KeePass writes "HMAC-SHA-1"/"HMAC-SHA-256"/"HMAC-SHA-512"; KeeWeb writes the bare name.
        val hashAlgorithm = when (algorithm?.trim()?.uppercase()?.replace("-", "")) {
            null, "HMACSHA1", "SHA1" -> TotpAlgorithm.SHA1
            "HMACSHA256", "SHA256" -> TotpAlgorithm.SHA256
            "HMACSHA512", "SHA512" -> TotpAlgorithm.SHA512
            else -> null
        }
        // The same bounds TotpFieldParser enforces on an otpauth:// URI, for the same reason: a Totp
        // outside them is one TotpGenerator's `require` rejects, i.e. one that never renders a code.
        if (hashAlgorithm == null ||
            digits == null || digits !in setOf(6, 8) ||
            periodSec == null || periodSec !in 1..86_400
        ) {
            return TotpLookup(null, emptySet())
        }
        return TotpLookup(
            base.copy(digits = digits, periodSec = periodSec, algorithm = hashAlgorithm),
            setOfNotNull(
                "TimeOtp-Secret-Base32",
                length?.let { "TimeOtp-Length" },
                period?.let { "TimeOtp-Period" },
                algorithm?.let { "TimeOtp-Algorithm" },
            ),
        )
    }
}

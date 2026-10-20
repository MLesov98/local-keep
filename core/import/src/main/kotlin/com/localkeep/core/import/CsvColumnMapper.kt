package com.localkeep.core.import

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.common.UrlHosts
import com.localkeep.core.model.Address
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object CsvColumnMapper {

    data class ColumnMapping(
        val titleCol: Int? = null,
        val urlCol: Int? = null,
        /**
         * A column holding *further* addresses for the same entry, which NordPass writes as
         * `additional_urls` — a JSON array of strings in one cell.
         *
         * `VaultItem` has held a list of URIs all along and this mapper filled it from one column,
         * so every address after the first was read by no code path at all: a login the user had
         * given two sites arrived with one, on an import that reported full success (1B-F1,
         * 2026-09-20).
         */
        val additionalUrlsCol: Int? = null,
        val usernameCol: Int? = null,
        /**
         * A second identity column, separate from [usernameCol] since 2026-09-13 (2-3).
         *
         * Proton Pass exports `email` and `username` as two columns on the same row, and both header
         * names used to live in one synonym set, so `indexOfFirst` bound whichever came first — the
         * email — and the value in the adjacent `username` column was read by no code path at all.
         */
        val emailCol: Int? = null,
        val passwordCol: Int? = null,
        val totpCol: Int? = null,
        val notesCol: Int? = null,
        /**
         * Bitwarden's CSV `fields` column, which holds everything that is not a login — a card's own
         * numbers among it. Read only to keep a row's content from being destroyed (2-4).
         */
        val fieldsCol: Int? = null,
        /**
         * A tag column, which 1Password's own CSV export writes as `Tags`.
         *
         * `VaultItem.tags` is a real, wired field and no CSV ever filled it, so a user's whole
         * cross-cutting filing scheme arrived empty on an import that reported full success — the
         * same defect the KDBX and 1Password importers were fixed for (F14 residual, 2026-09-13).
         */
        val tagsCol: Int? = null,
        /**
         * A favourite/starred flag, which LastPass writes as `fav` and several exporters as
         * `favorite`/`starred`.
         *
         * `VaultItem.favorite` is a real, wired field that the Bitwarden, 1Password and Aegis
         * importers all carry, and no CSV ever filled it — so the one piece of filing a user does by
         * hand most often arrived empty on an import that reported full success (S4, 2026-09-15).
         */
        val favoriteCol: Int? = null,
        /**
         * The single folder path a row belongs to — LastPass's `grouping`, Bitwarden's `folder`.
         *
         * Read as a folder, never as a tag: see [FOLDER_HEADERS].
         */
        val folderCol: Int? = null,
        /**
         * The record-kind column, which NordPass writes as `type` (`password`, `note`,
         * `credit_card`, `identity`) on a single 22-column schema shared by every record.
         *
         * Read only to choose between the type-specific columns below — an unrecognised value, or a
         * value whose columns this header does not carry, falls through to the ordinary login/note
         * mapping, which is what every other CSV in the corpus takes.
         */
        val typeCol: Int? = null,
        /** NordPass's card columns. Filled only on a `credit_card` row; empty on every other. */
        val cardHolderCol: Int? = null,
        val cardNumberCol: Int? = null,
        val cardCvcCol: Int? = null,
        val cardExpiryCol: Int? = null,
        /** NordPass's identity columns, likewise filled only on an `identity` row. */
        val fullNameCol: Int? = null,
        val phoneCol: Int? = null,
        val address1Col: Int? = null,
        val address2Col: Int? = null,
        val cityCol: Int? = null,
        val stateCol: Int? = null,
        val countryCol: Int? = null,
        val zipCol: Int? = null,
        /**
         * NordPass's `custom_fields`: a JSON array of `{label,type,value}`, one of which may be
         * `hidden`. Kept apart from [fieldsCol], which is Bitwarden's opaque blob and is only ever
         * appended to a note body — this one is structured and becomes real custom fields.
         */
        val customFieldsCol: Int? = null,
        /**
         * The character [folderCol]'s cell separates path segments with.
         *
         * Vendor-specific, and the header name is what says which vendor wrote the file: LastPass's
         * `grouping` nests with `\`, Bitwarden's `folder` with `/`. Splitting on both would be a
         * guess that renames any folder legitimately containing the other character.
         */
        val folderSeparator: Char = '/',
        /**
         * Every header index none of the columns above bound, carried onto each row as a TEXT
         * custom field named by its own header cell.
         *
         * Until 2026-09-20 an unrecognised column was simply not read: no value, no field, no skip
         * entry, no count. A `memo` column's text and an `account` column's identifier were gone,
         * the wizard reported full success, and its last step then offered to delete the export —
         * so the only copy went with it (1B-F1). Aliases close the cases we know
         * (`account`, `memo`, `category`, `additional_urls`); this closes the ones we do not, which
         * is the half that cannot be enumerated in advance. The rescue shape is the one a CSV
         * row's unparseable TOTP column already uses.
         *
         * Empty unless [autoDetect] filled it, so a hand-built mapping behaves exactly as before.
         */
        val extraCols: List<Int> = emptyList(),
    ) {
        /**
         * The indices the named columns above claim — what [autoDetect] subtracts from the header
         * to arrive at [extraCols].
         *
         * **A new column field must be added here in the same edit.** Leaving it out does not
         * fail to compile: the column would simply be read twice, once into its own field and
         * again as a custom field named after the header. `CsvUnmappedColumnTest` pins the count
         * so that omission is a red test rather than a duplicated field in someone's vault.
         */
        internal val boundColumns: Set<Int>
            get() = setOfNotNull(
                titleCol, urlCol, additionalUrlsCol, usernameCol, emailCol, passwordCol, totpCol,
                notesCol, fieldsCol, tagsCol, favoriteCol, folderCol, typeCol, cardHolderCol,
                cardNumberCol, cardCvcCol, cardExpiryCol, fullNameCol, phoneCol, address1Col,
                address2Col, cityCol, stateCol, countryCol, zipCol, customFieldsCol,
            )
    }

    // Known real-world headers this auto-detector must recognize (see Design decisions):
    //   Google/Chrome:  name, url, username, password[, note]
    //   Firefox:        url, username, password, httpRealm, formActionOrigin, guid, ...
    //   Apple/Keychain: Title, URL, Username, Password, Notes[, OTPAuth]
    //   LastPass:       url, username, password, totp, extra, name, grouping, fav
    //   Bitwarden:      name, login_uri, login_username, login_password, login_totp, notes
    // Bitwarden's four `login_*` columns must stay together. Three of them were recognised and
    // `login_totp` was not, so a Bitwarden CSV imported with every 2FA seed silently gone — no skip
    // entry, and not even the HIDDEN-custom-field rescue, because that only fires for a column this
    // mapper recognised but could not parse (V11, 2026-09-12).
    private val TITLE_HEADERS = setOf("name", "title")
    private val URL_HEADERS = setOf("url", "login_uri", "formactionorigin", "origin_url", "website", "site")
    // NordPass's second address column: one cell holding a JSON array of further URLs.
    private val ADDITIONAL_URLS_HEADERS = setOf("additional_urls")
    // `email` used to sit in this set beside `username`, which cost Proton Pass exports the column
    // that holds the real identifier: its header carries BOTH (`type,name,url,email,username,...`),
    // so `indexOfFirst` bound the email at index 3 and nothing ever read index 4. A row whose email
    // was blank imported with a blank username and the value simply gone (2-3, 2026-09-13).
    // `account` is the third spelling of the same idea, and the one a plain spreadsheet export is
    // most likely to use. Without it a `name,url,account,password,memo` file imported with no
    // username at all (1B-F1, 2026-09-20). Dashlane's `account_name`/`account_number`/
    // `account_holder` normalize to those full names and so are untouched by this.
    private val USERNAME_HEADERS = setOf("username", "login_username", "user", "account")
    private val EMAIL_HEADERS = setOf("email")
    private val PASSWORD_HEADERS = setOf("password", "login_password", "pass")
    // `otp_url` is Dashlane's other spelling, and it is the same defect as `otp_secret` (2-2) one
    // column over: Dashlane writes `otpSecret` when it holds a bare seed and `otpUrl` when it holds
    // a full `otpauth://` URI, and only the first was recognised — so half of all Dashlane exports
    // lost every 2FA seed on an import that reported full success, with no skip entry and not even
    // the HIDDEN-custom-field rescue, which fires only for a column this mapper recognised. Both
    // spellings ship as separate fixtures in the corpus, which is how the gap was found (WP11,
    // 2026-09-16).
    private val TOTP_HEADERS = setOf("totp", "login_totp", "otpauth", "otp_secret", "otp_url", "twofactorsecret")
    private val NOTES_HEADERS = setOf("notes", "note", "extra", "comments", "memo")
    // Bitwarden's catch-all column. Read only by the no-login-fields branch in `map`, to keep a
    // card or note row's own content from being destroyed rather than merely mistyped (2-4).
    private val FIELDS_HEADERS = setOf("fields")
    // Only headers a real export actually writes. LastPass's `grouping` and Bitwarden's `folder`
    // are deliberately absent: both name a single folder path, not a tag set, and reading them
    // here would file one concept under another. They are read as folders instead — FOLDER_HEADERS.
    private val TAGS_HEADERS = setOf("tags", "tag")
    // LastPass's `fav` is `1`/`0`; the others write `true`/`false` or `yes`/`no`. Anything else is
    // read as "not a favourite" rather than guessed at.
    private val FAVORITE_HEADERS = setOf("fav", "favorite", "favourite", "starred")
    private val TRUTHY_CELLS = setOf("1", "true", "yes")
    // The folder path column, which used to be discarded entirely: not read as a tag (rightly — see
    // TAGS_HEADERS) and not built as a folder either, so a LastPass user's whole filing scheme was
    // dropped on an import that reported full success (S5, 2026-09-15). `grouping` and `folder` are
    // kept apart from each other only by [ColumnMapping.folderSeparator].
    // `category` is Dashlane's spelling of the same single-path idea, and it was dropped the way
    // `grouping` used to be: a `credentials.csv` filed under Entertainment imported filed nowhere
    // (1B-F1, 2026-09-20). Read as a folder rather than a tag for the reason stated above.
    private val FOLDER_HEADERS = setOf("grouping", "folder", "category")
    // NordPass's unified schema: one 22-column header for every record kind, with `type` saying
    // which. Until 2026-09-16 none of these columns was bound, so a card row was reported as
    // NoLoginFields with its number carried nowhere, and an identity row — which fills `email` —
    // imported as a Login whose username was the person's email and whose name, phone and postal
    // address were dropped without a word (gaps 6 and 7).
    private val TYPE_HEADERS = setOf("type")
    private val CARD_HOLDER_HEADERS = setOf("cardholdername", "card_holder", "cardholder")
    private val CARD_NUMBER_HEADERS = setOf("cardnumber", "card_number")
    private val CARD_CVC_HEADERS = setOf("cvc", "cvv", "security_code")
    private val CARD_EXPIRY_HEADERS = setOf("expirydate", "expiry_date", "expiration_date")
    private val FULL_NAME_HEADERS = setOf("full_name", "fullname")
    private val PHONE_HEADERS = setOf("phone_number", "phone")
    private val ADDRESS1_HEADERS = setOf("address1", "address_1")
    private val ADDRESS2_HEADERS = setOf("address2", "address_2")
    private val CITY_HEADERS = setOf("city")
    private val STATE_HEADERS = setOf("state")
    private val COUNTRY_HEADERS = setOf("country")
    private val ZIP_HEADERS = setOf("zipcode", "zip", "zip_code", "postal_code")
    private val CUSTOM_FIELDS_HEADERS = setOf("custom_fields")

    /**
     * Columns an unrecognised header is *not* carried under — the exporting app's own bookkeeping,
     * which the user never wrote and cannot act on.
     *
     * Every one of these is measured against a real export in the corpus. Firefox writes five
     * internals beside its three real columns (`httpRealm`, a `formActionOrigin` that is not the
     * address, a `guid` and three epoch stamps), so carrying unmatched columns blindly would hang
     * five junk fields off every Firefox login — noise that is a worse answer than the silence it
     * replaces. Bitwarden's `reprompt` is a 0/1 flag, live in the JSON importer and meaningless as
     * the literal text "0" on every row of a CSV.
     */
    private val IGNORED_HEADERS = setOf(
        "httprealm",
        "formactionorigin",
        "guid",
        "timecreated",
        "timelastused",
        "timepasswordchanged",
        "reprompt",
    )

    /**
     * A camelCase word boundary, so `otpSecret` can reach the `otp_secret` alias above.
     *
     * Dashlane writes its 2FA column as camelCase, and `normalize` only ever lowercased and replaced
     * spaces — so `otpSecret` became `otpsecret`, matched no alias, and `totpCol` stayed null. Every
     * Dashlane row's seed was then read as the empty string and discarded: no skip entry, and not
     * even the HIDDEN-custom-field rescue below, which fires only for a column this mapper
     * recognised but could not parse. Same failure class as Bitwarden's `login_totp` (V11), left
     * behind by that fix rather than introduced by it (2-2, 2026-09-13).
     */
    private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")

    /**
     * [bytes] as text, with any byte-order mark dropped.
     *
     * 2026-10-02: this was a plain UTF-8 decode, which turns every byte that is not UTF-8 into U+FFFD
     * without a word. Excel's "CSV (Comma delimited)" writes the Windows code page — cp1252 across
     * every language this app ships in — so a re-saved export imported `Pässwort1` as `P\uFFFDsswort1`,
     * masked in the preview, reported as a success, and next to an offer to delete the source file.
     * Now: a UTF-16 byte-order mark is honoured, strict UTF-8 is tried, and a file that is not UTF-8
     * is read as windows-1252, which maps every byte it uses to the character Excel meant.
     */
    internal fun decodeText(bytes: ByteArray): String {
        val utf16 = bytes.size >= 2 &&
            (
                (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                    (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
                )
        val text = if (utf16) {
            String(bytes, Charsets.UTF_16)
        } else {
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                String(bytes, WINDOWS_1252)
            }
        }
        return text.removePrefix("\uFEFF")
    }

    private val WINDOWS_1252: Charset =
        runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /**
     * Parses raw CSV bytes (see [decodeText] for the encodings) into rows of fields, per RFC 4180.
     *
     * [delimiter] is a parameter because a comma is not universal: a spreadsheet saved in a locale
     * whose decimal mark is a comma writes `;`, and so do several European password-manager
     * exports. Nothing chooses it here — see [parseDelimited], which only reaches for `;` after a
     * comma reading has already failed, so no file that parses today can start parsing differently.
     */
    fun parseRows(bytes: ByteArray, delimiter: Char = ','): List<List<String>> {
        val text = decodeText(bytes)
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length

        fun endField() {
            row.add(field.toString())
            field.clear()
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < n && text[i + 1] == '"' -> { field.append('"'); i += 2 }
                    c == '"' -> { inQuotes = false; i++ }
                    else -> { field.append(c); i++ }
                }
            } else {
                when (c) {
                    '"' -> { inQuotes = true; i++ }
                    delimiter -> { endField(); i++ }
                    '\r' -> { i += if (i + 1 < n && text[i + 1] == '\n') 2 else 1; endRow() }
                    '\n' -> { i++; endRow() }
                    else -> { field.append(c); i++ }
                }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        // Drop fully-blank trailing/embedded lines (a line with nothing but an empty field).
        return rows.filterNot { it.size == 1 && it[0].isEmpty() }
    }

    /**
     * [parseRows] plus the delimiter that actually worked, for a file this module may not read as
     * comma-separated at all.
     *
     * Comma first, always, and `;` only when the comma reading produced no password column — so a
     * file that parses today parses identically tomorrow, and the second attempt is paid for only
     * by a file that was being refused outright. A semicolon-delimited export used to reach the
     * user as one generic "that file isn't in a format LocalKeep recognizes", advising them to
     * check for an encryption and a truncation that were not the problem (1B-F3, 2026-09-20).
     */
    fun parseDelimited(bytes: ByteArray): Pair<List<List<String>>, Char> {
        val byComma = parseRows(bytes, ',')
        if (byComma.isEmpty() || autoDetect(byComma.first()).passwordCol != null) return byComma to ','
        val bySemicolon = parseRows(bytes, ';')
        val readable = bySemicolon.isNotEmpty() && autoDetect(bySemicolon.first()).passwordCol != null
        return if (readable) bySemicolon to ';' else byComma to ','
    }

    /** Proposes a [ColumnMapping] from a header row by matching normalized header synonyms. */
    fun autoDetect(header: List<String>): ColumnMapping {
        fun normalize(s: String) = s.trim().lowercase().replace(" ", "_")
        fun snakeCase(s: String) = normalize(s.trim().replace(CAMEL_BOUNDARY, "\$1_\$2"))
        val normalized = header.map(::normalize)
        val snaked = header.map(::snakeCase)
        // Both forms are matched, never the snake one alone: Firefox's `formActionOrigin` is already
        // in URL_HEADERS as the run-together `formactionorigin`, and splitting it would have bought
        // Dashlane's TOTP column at the price of Firefox's URL column.
        fun find(candidates: Set<String>): Int? =
            header.indices.firstOrNull { normalized[it] in candidates || snaked[it] in candidates }

        val folderCol = find(FOLDER_HEADERS)
        val mapping = ColumnMapping(
            titleCol = find(TITLE_HEADERS),
            urlCol = find(URL_HEADERS),
            additionalUrlsCol = find(ADDITIONAL_URLS_HEADERS),
            usernameCol = find(USERNAME_HEADERS),
            emailCol = find(EMAIL_HEADERS),
            passwordCol = find(PASSWORD_HEADERS),
            totpCol = find(TOTP_HEADERS),
            notesCol = find(NOTES_HEADERS),
            fieldsCol = find(FIELDS_HEADERS),
            tagsCol = find(TAGS_HEADERS),
            favoriteCol = find(FAVORITE_HEADERS),
            typeCol = find(TYPE_HEADERS),
            cardHolderCol = find(CARD_HOLDER_HEADERS),
            cardNumberCol = find(CARD_NUMBER_HEADERS),
            cardCvcCol = find(CARD_CVC_HEADERS),
            cardExpiryCol = find(CARD_EXPIRY_HEADERS),
            fullNameCol = find(FULL_NAME_HEADERS),
            phoneCol = find(PHONE_HEADERS),
            address1Col = find(ADDRESS1_HEADERS),
            address2Col = find(ADDRESS2_HEADERS),
            cityCol = find(CITY_HEADERS),
            stateCol = find(STATE_HEADERS),
            countryCol = find(COUNTRY_HEADERS),
            zipCol = find(ZIP_HEADERS),
            customFieldsCol = find(CUSTOM_FIELDS_HEADERS),
            folderCol = folderCol,
            // `grouping` is LastPass's spelling and the only one that nests with a backslash. The
            // normalized form is matched, not the snaked one: neither header is camelCase.
            folderSeparator = if (folderCol != null && normalized[folderCol] == "grouping") '\\' else '/',
        )
        // Whatever is left. A blank header cell names nothing, so it cannot become a field; the
        // rest are the columns this mapper has no place for and used to discard in silence.
        val bound = mapping.boundColumns
        return mapping.copy(
            extraCols = header.indices.filter {
                it !in bound &&
                    header[it].isNotBlank() &&
                    normalized[it] !in IGNORED_HEADERS &&
                    snaked[it] !in IGNORED_HEADERS
            },
        )
    }

    /**
     * Maps parsed rows to [VaultItem]s using the given [mapping] — `Login` content, or `Note` for a
     * row that carries text but no credential (see [map]).
     */
    fun mapRows(
        rows: List<List<String>>,
        mapping: ColumnMapping,
        hasHeader: Boolean = true,
        titles: ImportDefaultTitles = ImportDefaultTitles(),
    ): List<VaultItem> = map(rows, mapping, hasHeader, titles).items

    /**
     * [mapRows] plus an account of the rows it dropped, for the wizard's "not imported" list.
     *
     * A row with no address, username *and* password is not a login by any reading, but it can
     * still carry a title and a note the user wrote — so it is named rather than vanished.
     *
     * Since 2026-09-13 it is more than named: if that row's `notes` or `fields` column holds
     * anything, it becomes a secure `Note` carrying that text, because "named" still destroyed the
     * body (2-4). Only a row with nothing left in it is skipped.
     */
    fun map(
        rows: List<List<String>>,
        mapping: ColumnMapping,
        hasHeader: Boolean = true,
        titles: ImportDefaultTitles = ImportDefaultTitles(),
    ): ParsedImport {
        val dataRows = if (hasHeader) rows.drop(1) else rows
        // The header cells themselves, not just their indices: a second identity column is carried
        // across under the name the source file gave it (see `secondIdentity` below), which is data
        // read out of the export rather than copy this module would have to translate.
        val headerRow = if (hasHeader) rows.firstOrNull().orEmpty() else emptyList()
        val now = Instant.now()
        val skipped = mutableListOf<SkippedEntry>()
        // Minted lazily, one per distinct path prefix a row actually lands in, so the list can never
        // hold a folder nothing was filed into or a `parentId` pointing at a folder that was not
        // created. Ordered parents-before-children by construction.
        val folders = mutableListOf<Folder>()
        val folderIdsByPath = mutableMapOf<String, UUID>()
        // Header names of the unrecognised columns that actually carried something, in header
        // order — what the wizard reports as "N columns kept as custom fields". A column present in
        // the header but empty on every row is not reported: nothing was kept, so there is nothing
        // to tell the user about.
        val carriedColumns = linkedSetOf<String>()
        // Seeds that survived only as a hidden field — see [ParsedImport.unusableTotpCount].
        var unusableTotp = 0

        val items = dataRows.mapNotNull { row ->
            fun raw(idx: Int?): String = idx?.let { row.getOrNull(it) }.orEmpty()
            fun col(idx: Int?): String = raw(idx).trim()

            // Columns [ColumnMapping.extraCols] named, as TEXT custom fields on this row. TEXT and
            // not HIDDEN: this mapper does not know what the column held, and masking something the
            // export wrote in the clear would claim a property the source never had.
            //
            // A function, not a val, because calling it is what records the header in
            // `carriedColumns`: the branches below can still skip a row, and a column reported as
            // "kept" on a row that was dropped would be a count the vault does not back up.
            fun extras(): List<CustomField> = mapping.extraCols.mapNotNull { idx ->
                val name = headerRow.getOrNull(idx)?.trim().orEmpty()
                if (name.isBlank() || raw(idx).isBlank()) return@mapNotNull null
                carriedColumns += name
                CustomField(name = name, value = raw(idx), type = CustomFieldType.TEXT)
            }

            val url = col(mapping.urlCol)
            // Two identity cells, one `Login.username`. Whichever the row actually filled in wins,
            // preferring `username` — the column the user picked deliberately — over `email`, which
            // Proton Pass writes for every row whether or not it holds anything (2-3, 2026-09-13).
            val usernameCell = col(mapping.usernameCol)
            val emailCell = col(mapping.emailCol)
            val username = usernameCell.ifBlank { emailCell }
            // Verbatim, never trimmed. The parser already preserves whitespace inside quotes
            // exactly, so ` hunter2 ` in a correctly-quoted CSV cell is the password — and trimming
            // it produced a vault entry that silently would not log in, with nothing on screen to
            // suggest why. A password is opaque bytes; only the fields the user reads as text can
            // afford to be tidied.
            val password = raw(mapping.passwordCol)
            // LastPass's secure-note sentinel. Every secure note leaves LastPass with `http://sn`
            // in the url column and its content as Key:Value lines in `extra`; read as an address,
            // the row became a Login pointing at a host called `sn` — autofill-eligible for it and
            // counted by the health dashboard as a login with a blank password (gap 3).
            if (LastPassSecureNote.isSentinel(url)) {
                val blob = raw(mapping.notesCol)
                if (blob.isBlank()) {
                    skipped += SkippedEntry(
                        title = col(mapping.titleCol).ifBlank { titles.untitledRow },
                        reason = SkipReason.NoLoginFields,
                    )
                    return@mapNotNull null
                }
                val mapped = LastPassSecureNote.map(blob)
                return@mapNotNull VaultItem(
                    id = UUID.randomUUID(),
                    title = col(mapping.titleCol).ifBlank { titles.importedItem },
                    folderId = folderIdOf(col(mapping.folderCol), mapping, folders, folderIdsByPath, now),
                    favorite = isTruthy(col(mapping.favoriteCol)),
                    tags = tagsOf(raw(mapping.tagsCol)),
                    createdAt = now,
                    updatedAt = now,
                    customFields = mapped.customFields + extras(),
                    content = mapped.content,
                )
            }
            // NordPass's `type` column, and only where the header actually carries the columns that
            // type names — so a `type` cell from some other export (Proton Pass writes `login`,
            // `note`, `alias`) falls through to the mapping it has always taken.
            val typedContent = when (normalizeType(col(mapping.typeCol))) {
                "credit_card" -> mapping.cardNumberCol?.let { cardOf(mapping, ::raw) }
                "identity" -> mapping.fullNameCol?.let { identityOf(mapping, ::raw) }
                else -> null
            }
            if (typedContent != null) {
                return@mapNotNull VaultItem(
                    id = UUID.randomUUID(),
                    title = col(mapping.titleCol).ifBlank { titles.importedItem },
                    folderId = folderIdOf(col(mapping.folderCol), mapping, folders, folderIdsByPath, now),
                    favorite = isTruthy(col(mapping.favoriteCol)),
                    tags = tagsOf(raw(mapping.tagsCol)),
                    createdAt = now,
                    updatedAt = now,
                    customFields = jsonCustomFields(raw(mapping.customFieldsCol)) + extras(),
                    content = typedContent,
                )
            }
            if (url.isBlank() && username.isBlank() && password.isBlank()) {
                // Not a login — but "not a login" is not "not anything". A Bitwarden CSV writes its
                // secure notes and cards as rows with all three login columns blank and the real
                // content in `notes` / `fields`, and this branch used to drop the whole row down to
                // a bare title: `SkippedEntry` carries no content, and `notesCol` was read only
                // inside the item branch that `return@mapNotNull null` skipped past. The wizard's
                // last step then offers to delete the export, so the note body existed nowhere
                // afterwards (2-4, 2026-09-13).
                //
                // Both columns verbatim and unlabelled: this module cannot author text (see
                // ImportFailureReason's kdoc), so a heading naming which half came from `fields`
                // would be untranslatable English written into the user's vault.
                val noteBody = listOf(raw(mapping.notesCol), raw(mapping.fieldsCol))
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                // `extras` deliberately does NOT rescue this branch. A row with no address, no
                // identity and no password, whose only content is in columns this mapper could not
                // name, is the shape a header-less file takes when it is forced through the generic
                // mapper — every column unmatched, every row a stranger — and turning those into
                // items produced a vault full of confidently-wrong entries
                // (RealWorldKeeperImportTest pins exactly that). The row is still named in
                // `skipped`, so nothing here is silent.
                if (noteBody.isBlank()) {
                    skipped += SkippedEntry(
                        title = col(mapping.titleCol).ifBlank { titles.untitledRow },
                        reason = SkipReason.NoLoginFields,
                    )
                    return@mapNotNull null
                }
                return@mapNotNull VaultItem(
                    id = UUID.randomUUID(),
                    title = col(mapping.titleCol).ifBlank { titles.importedItem },
                    folderId = folderIdOf(col(mapping.folderCol), mapping, folders, folderIdsByPath, now),
                    favorite = isTruthy(col(mapping.favoriteCol)),
                    tags = tagsOf(raw(mapping.tagsCol)),
                    createdAt = now,
                    updatedAt = now,
                    customFields = jsonCustomFields(raw(mapping.customFieldsCol)) + extras(),
                    content = Note(body = noteBody),
                )
            }

            val totp = col(mapping.totpCol).takeIf { it.isNotBlank() }?.let(TotpFieldParser::parse)
            // A TOTP column that does not parse (a HOTP URI, a non-Base32 secret) is a second
            // factor the user exported and expects to come across. Dropping it silently made the
            // import report full success while the seed existed nowhere in the vault — and the
            // wizard then offered to delete the source file, making the loss permanent. The KDBX
            // importer rescues unparseable seeds as HIDDEN custom fields; do the same here so the
            // value survives in a form the user can act on.
            val rescuedTotp: CustomField? = if (totp == null) {
                col(mapping.totpCol).takeIf { it.isNotBlank() }?.let { rawSeed ->
                    unusableTotp++
                    CustomField(name = titles.rescuedTotpFieldName, value = rawSeed, type = CustomFieldType.HIDDEN)
                }
            } else {
                null
            }

            // Both identity columns held something, and `Login` has one field for them. The loser
            // is carried as a custom field rather than dropped, under the header the source file
            // used for it — the same rescue shape as `rescuedTotp` above, and the name is a cell
            // read out of the export, not copy this module authored. Nothing is carried when the
            // caller supplied a mapping with no header row to name the column from, which no
            // production path does: `CsvImporter` always maps a header it just auto-detected.
            val secondIdentity: CustomField? = if (usernameCell.isNotBlank() && emailCell.isNotBlank()) {
                mapping.emailCol?.let { headerRow.getOrNull(it) }?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { CustomField(name = it, value = emailCell, type = CustomFieldType.TEXT) }
            } else {
                null
            }

            VaultItem(
                id = UUID.randomUUID(),
                title = col(mapping.titleCol).ifBlank { hostOf(url).ifBlank { titles.importedItem } },
                folderId = folderIdOf(col(mapping.folderCol), mapping, folders, folderIdsByPath, now),
                favorite = isTruthy(col(mapping.favoriteCol)),
                tags = tagsOf(raw(mapping.tagsCol)),
                createdAt = now,
                updatedAt = now,
                customFields = listOfNotNull(rescuedTotp, secondIdentity) +
                    jsonCustomFields(raw(mapping.customFieldsCol)) + extras(),
                content = Login(
                    username = username,
                    password = password,
                    // The address column plus whatever [ColumnMapping.additionalUrlsCol] held.
                    // `distinct` because an export is free to repeat the primary address in its
                    // list, and two identical URIs on one login are one URI to every reader of it.
                    uris = (listOf(url) + additionalUris(raw(mapping.additionalUrlsCol)))
                        .filter { it.isNotBlank() }
                        .distinct()
                        .map { UriMatch(uri = it) },
                    totp = totp,
                    // Also verbatim: a note's leading indentation is content the user wrote.
                    notes = raw(mapping.notesCol),
                ),
            )
        }
        // `timestampsKnown = false`: no CSV export this mapper recognises carries a per-row
        // created/modified time, so both stamps above are the import instant and mean nothing in a
        // merge. See [ParsedImport.timestampsKnown].
        return ParsedImport(
            items = items,
            skipped = skipped,
            folders = folders,
            timestampsKnown = false,
            carriedColumns = carriedColumns.toList(),
            unusableTotpCount = unusableTotp,
        )
    }

    /**
     * An `additional_urls` cell to the addresses in it.
     *
     * NordPass writes a JSON array of strings (`["https://example.net"]`) in one CSV cell, which is
     * what the first branch reads. The fallback is for the same column written the way a
     * spreadsheet does it — one address, or several separated by a comma or a newline — because a
     * cell that is not JSON still holds the user's addresses and refusing to read it would be the
     * silent drop this column was added to end.
     */
    private fun additionalUris(cell: String): List<String> {
        val trimmed = cell.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            runCatching { fieldsJson.decodeFromString(ListSerializer(String.serializer()), trimmed) }
                .getOrNull()
                ?.let { array -> return array.map { it.trim() }.filter { it.isNotEmpty() } }
        }
        return trimmed.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Whether a flag cell means yes. Anything unrecognised is no — never a guess. */
    private fun isTruthy(cell: String): Boolean = cell.lowercase() in TRUTHY_CELLS

    /** A `type` cell, in the one spelling the branches above compare against. */
    private fun normalizeType(cell: String): String =
        cell.trim().lowercase().replace(CAMEL_BOUNDARY, "$1_$2").replace(' ', '_').replace('-', '_')

    /**
     * A NordPass `credit_card` row. Every column is optional — a row that fills only some of them
     * still becomes a card with the rest blank, which is what the editor shows for a card the user
     * part-filled by hand.
     */
    private fun cardOf(mapping: ColumnMapping, raw: (Int?) -> String): Card {
        val number = raw(mapping.cardNumberCol).trim()
        val expiry = expiryOf(raw(mapping.cardExpiryCol))
        return Card(
            holder = raw(mapping.cardHolderCol).trim(),
            number = number,
            // The digits decide the brand — see ImportedCardBrand. NordPass exports no brand text,
            // so there is nothing to fall back to and nothing to disagree with.
            brand = ImportedCardBrand.of(number, null),
            expMonth = expiry?.first,
            expYear = expiry?.second,
            cvv = raw(mapping.cardCvcCol).trim(),
            notes = raw(mapping.notesCol),
        )
    }

    /**
     * A NordPass `identity` row.
     *
     * `email` is read here as the identity's own address, which is the column the old behaviour
     * turned into a `Login.username` — the reason an identity row imported as a login at all.
     */
    private fun identityOf(mapping: ColumnMapping, raw: (Int?) -> String): Identity {
        // One `full_name` column, two model fields. First token is the given name, the rest the
        // family name — the same split LastPassSecureNote makes, and stated as crude there too.
        val name = raw(mapping.fullNameCol).trim()
        return Identity(
            firstName = name.substringBefore(' '),
            lastName = name.substringAfter(' ', missingDelimiterValue = ""),
            email = raw(mapping.emailCol).trim(),
            phone = raw(mapping.phoneCol).trim(),
            address = Address(
                line1 = raw(mapping.address1Col).trim(),
                line2 = raw(mapping.address2Col).trim(),
                city = raw(mapping.cityCol).trim(),
                state = raw(mapping.stateCol).trim(),
                zip = raw(mapping.zipCol).trim(),
                country = raw(mapping.countryCol).trim(),
            ),
        )
    }

    /**
     * `01 / 22` → month 1, year 2022. Null when either half is unreadable, and the cell is then
     * simply absent from the card: a wrong expiry is worse than a missing one.
     *
     * A two-digit year is read as 20xx. Payment cards do not carry a 19xx expiry.
     */
    private fun expiryOf(cell: String): Pair<Int, Int>? {
        val parts = cell.split('/', '-', '.').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val month = parts[0].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val year = parts[1].toIntOrNull()?.let { if (it in 0..99) 2000 + it else it } ?: return null
        return month to (year.takeIf { it in 1000..9999 } ?: return null)
    }

    /**
     * NordPass's `custom_fields` cell: a JSON array of `{label,type,value}`.
     *
     * `type` is `hidden` for a field the user marked secret, and that is carried across as
     * [CustomFieldType.HIDDEN] — a field the source masked must not arrive unmasked. An unparseable
     * cell yields nothing rather than failing the row: the rest of the record is still the user's
     * data, and a malformed extras blob is not a reason to lose it.
     */
    private fun jsonCustomFields(cell: String): List<CustomField> {
        if (cell.isBlank()) return emptyList()
        val array = runCatching { fieldsJson.decodeFromString(ListSerializer(CsvCustomField.serializer()), cell) }
            .getOrNull() ?: return emptyList()
        return array.filter { it.label.isNotBlank() }.map {
            CustomField(
                name = it.label,
                value = it.value,
                type = if (it.type.equals("hidden", ignoreCase = true)) CustomFieldType.HIDDEN else CustomFieldType.TEXT,
            )
        }
    }

    private val fieldsJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable
    private data class CsvCustomField(
        val label: String = "",
        val type: String = "text",
        val value: String = "",
    )

    /**
     * The folder a row is filed into, creating it (and every ancestor it needs) on first use.
     *
     * The cell is a *path*, not a name: LastPass writes `Finance\Banks`, Bitwarden `Finance/Banks`,
     * and [ColumnMapping.folderSeparator] says which. Segments are trimmed, blanks dropped — a
     * trailing separator is not an unnamed child folder — and the path is clamped to
     * [FolderTree.MAX_DEPTH] the way [KdbxImporter] clamps a KeePass tree, so a deeper export lands
     * near where it was rather than being refused or flattened to the root.
     *
     * Returns null for a blank cell, which is the row belonging in no folder at all.
     */
    private fun folderIdOf(
        cell: String,
        mapping: ColumnMapping,
        folders: MutableList<Folder>,
        idsByPath: MutableMap<String, UUID>,
        importedAt: Instant,
    ): UUID? {
        if (mapping.folderCol == null || cell.isBlank()) return null
        val segments = cell.split(mapping.folderSeparator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(FolderTree.MAX_DEPTH)
        var parentId: UUID? = null
        val path = StringBuilder()
        for (segment in segments) {
            if (path.isNotEmpty()) path.append(mapping.folderSeparator)
            path.append(segment)
            val here = parentId
            parentId = idsByPath.getOrPut(path.toString()) {
                UUID.randomUUID().also {
                    // Stamped now for the same reason BitwardenJsonImporter stamps its folders now:
                    // a null `Folder.updatedAt` loses every rename tie-break, which is the wrong
                    // default for something arriving fresh.
                    folders += Folder(id = it, name = segment, parentId = here, updatedAt = importedAt)
                }
            }
        }
        return parentId
    }

    /**
     * One tag cell to a tag list.
     *
     * Comma and semicolon are both separators: a correctly-quoted CSV cell may hold either, and
     * 1Password writes its multi-value tag cell comma-separated inside quotes. Blanks are dropped
     * and duplicates collapsed, order preserved — the same rule the KDBX importer's `<Tags>`
     * element follows, so the two sources cannot disagree about what a tag list is.
     */
    private fun tagsOf(cell: String): List<String> =
        cell.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * The host to title an untitled row with — [UrlHosts.normalise], not a second implementation.
     *
     * This was a private reimplementation, and it carried the exact defect `UrlHosts` was written to
     * fix: `URI.host` is null for a registry-based authority (an underscore is enough —
     * `https://my_bank.example.com/login`), and the fallback was the whole raw URL, so the row was
     * titled with a scheme, a path and a query. It also never lowercased or dropped `www.`, while
     * the duplicate detector and the import merger — asking the same question about the same value
     * — did both. A title that disagrees with the identity the rest of the app derives is how an
     * import adds a login the security dashboard then reports as a duplicate of it.
     */
    private fun hostOf(url: String): String = UrlHosts.normalise(url)
}

/**
 * Default CSV pipeline: auto-detects the column mapping from the header row and maps every
 * remaining row. The future preview UI calls [CsvColumnMapper] directly instead when the user
 * needs to override the detected mapping.
 */
object CsvImporter : Importer {
    override val sourceFormat: SourceFormat = SourceFormat.CSV

    override fun detect(bytes: ByteArray): Boolean = runCatching {
        val rows = CsvColumnMapper.parseDelimited(bytes).first
        if (rows.isEmpty()) return@runCatching false
        val mapping = CsvColumnMapper.autoDetect(rows.first())
        // Require at least (an identity column or url) + password recognized, else this isn't a
        // password-manager CSV we know how to read — could be an unrelated spreadsheet.
        // `emailCol` is named here because it used to be one of the `usernameCol` synonyms: without
        // it, a CSV whose only identity column is `email` would stop being recognised at all (2-3).
        mapping.passwordCol != null &&
            (mapping.usernameCol != null || mapping.emailCol != null || mapping.urlCol != null)
    }.getOrDefault(false)

    override fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> = try {
        val rows = CsvColumnMapper.parseDelimited(bytes).first
        if (rows.isEmpty()) {
            Outcome.Success(ParsedImport(emptyList(), timestampsKnown = false))
        } else {
            val mapping = CsvColumnMapper.autoDetect(rows.first())
            Outcome.Success(CsvColumnMapper.map(rows, mapping, hasHeader = true, titles = titles))
        }
    } catch (cancellation: CancellationException) {
        // Preserved from the `runCatchingOutcome` this replaced, which re-throws it: a cancelled
        // import is not a failed one.
        throw cancellation
    } catch (t: Throwable) {
        Outcome.Failure(describeFailure(t))
    }

    /**
     * Why a CSV parse failed, for the one case that is not the file's fault.
     *
     * This importer used to go through `runCatchingOutcome`, which reports every throw as a
     * cause-less `AppError.Unknown` — and `ImportError.FromImporter` maps a cause-less AppError to
     * `ImportFailureReason.Unexpected`, rendered as "may be corrupted or in a format LocalKeep
     * doesn't support". For an `OutOfMemoryError` that is false twice over: the file is neither
     * corrupted nor unsupported, the device simply ran out of room. The four sibling importers
     * gained this branch on 2026-09-13 (V14/G13) and CsvImporter was the one left out — which
     * matters because Chrome, Apple/Keychain and the generic "Other/CSV" source all route here
     * (5-3, 2026-09-13).
     *
     * Everything else keeps the exact fallback `runCatchingOutcome` produced, cause-less message
     * included, so a genuinely malformed file still resolves to `Unexpected` and this branch cannot
     * swallow an unrelated failure.
     */
    internal fun describeFailure(t: Throwable): AppError =
        // The whole cause chain, not just the top: the same shape as KdbxImporter.kt:128.
        if (generateSequence(t) { it.cause }.any { it is OutOfMemoryError }) {
            AppError.Validation(
                "failed to parse CSV export",
                ImportRejection(ImportFailureReason.ExportNeedsMoreMemory(SourceFormat.CSV), t),
            )
        } else {
            AppError.Unknown("Unexpected failure")
        }
}

package com.localkeep.core.import

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import com.localkeep.core.model.VaultItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

@Serializable
internal data class AegisExport(
    val version: Int? = null,
    val header: AegisHeader = AegisHeader(),
    val db: JsonElement = JsonNull,
) {
    /** Whether the file declared a `version` at all — the one key every Aegis export writes. */
    val hasVersion: Boolean get() = version != null
}

@Serializable
internal data class AegisHeader(
    val slots: JsonElement? = null,
)

@Serializable
internal data class AegisDb(
    val version: Int = 1,
    val entries: List<AegisEntry> = emptyList(),
    /**
     * The vault's own group records, which newer Aegis exports write as `{uuid, name}` and refer to
     * from an entry's `groups` array. `db.version 2` has no such list — it writes the group's *name*
     * straight onto the entry — so this is empty for the older shape and the entry carries the name.
     */
    val groups: List<AegisGroup> = emptyList(),
)

@Serializable
internal data class AegisGroup(
    val uuid: String = "",
    val name: String = "",
)

@Serializable
internal data class AegisEntry(
    val type: String = "totp",
    val name: String = "",
    val issuer: String = "",
    val favorite: Boolean = false,
    /**
     * The user's own free-text note on the entry — "backup codes taped inside the router".
     *
     * Undeclared until 2026-09-13, so `ignoreUnknownKeys = true` discarded it before `parse` ever
     * saw it, and the imported item carried no trace of it: no custom field, no skip entry, on an
     * import that reported full success. Different in kind from the base64 `icon` this importer is
     * right to ignore — a note is something a person wrote (4-2).
     */
    val note: String = "",
    /**
     * The group this entry is filed in.
     *
     * Two shapes, both read: `db.version 2` writes the group's name here as a bare string, and
     * newer exports write a `groups` array of uuids resolved through [AegisDb.groups]. Neither was
     * read at all until 2026-09-16 (gap 5), so a grouped vault imported flat on a success message
     * while [ParsedImport.folders] — which the CSV and KDBX importers both fill — stayed empty.
     */
    val group: String? = null,
    val groups: List<String> = emptyList(),
    val info: AegisInfo = AegisInfo(),
)

@Serializable
internal data class AegisInfo(
    val secret: String = "",
    val algo: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
) {
    /**
     * Whether this app can actually compute the code this entry describes.
     *
     * The same three rules `TotpFieldParser` and `OtpAuthParser` apply, stated once here because
     * the Aegis path is the one TOTP importer that does not go through a URI parser: the export is
     * already structured, so there was nothing between the file and the model.
     */
    fun isSupported(): Boolean = settingsSupported() && TotpFieldParser.parse(secret) != null

    /**
     * The half of [isSupported] that is genuinely about the *settings* — algorithm, digit count,
     * period — and says nothing about whether the secret can be read.
     *
     * Split out on 2026-09-13 because the caller reported both halves with the same
     * `SkipReason.UnsupportedTotpSettings`, which renders the three values verbatim. An entry with
     * an empty secret and nothing else wrong was therefore told "Unsupported TOTP settings (SHA1,
     * 6 digits, 30s)" — the three most ordinary, fully supported parameters there are, blamed for a
     * failure they had no part in, while the actual cause went unnamed (4-3).
     */
    fun settingsSupported(): Boolean =
        algo.uppercase() in SUPPORTED_ALGORITHMS &&
            (digits == 6 || digits == 8) &&
            period in 1..MAX_PERIOD_SECONDS

    private companion object {
        val SUPPORTED_ALGORITHMS = setOf("SHA1", "SHA256", "SHA512")
        const val MAX_PERIOD_SECONDS = 86_400
    }
}

object AegisJsonImporter : Importer {
    override val sourceFormat: SourceFormat = SourceFormat.AEGIS

    // `coerceInputValues`: Aegis writes JSON `null` — not `[]` — for a `db.version 2` export's
    // `groups`, and a null landing on a non-nullable List fails the whole file. Same defect and
    // same fix as BitwardenJsonImporter's, found the same way (the corpus's own grouped fixture).
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    override fun detect(bytes: ByteArray): Boolean = runCatching {
        val export = json.decodeFromString(AegisExport.serializer(), bytes.toString(Charsets.UTF_8))
        // A decrypted export (`db` is the entries object), an encrypted one whose header lists
        // its key slots, or an encrypted one whose `db` is the ciphertext string. The third was
        // missing until 2026-09-14: `parse` already answers it with "this export is
        // password-encrypted", but `detect` never let it get there, so the user was told the
        // file was not recognised at all — the one message that does not tell them what to fix.
        // The `version` key is required beside it so that any JSON with a string `db` does not
        // pass as Aegis.
        export.db is JsonObject ||
            export.header.slots != null ||
            (export.db is JsonPrimitive && export.db.isString && export.hasVersion)
    }.getOrDefault(false)

    // NOTE: uses manual try/catch (not runCatchingOutcome) so the specific failure reason (e.g.
    // ContainerEncrypted) reaches the caller — see the same note in BitwardenJsonImporter.
    override fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> = try {
        val export = try {
            json.decodeFromString(AegisExport.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            throw ImportRejection(ImportFailureReason.MalformedExport(SourceFormat.AEGIS), t)
        }
        val dbElement = export.db
        if (dbElement !is JsonObject) {
            throw ImportRejection(ImportFailureReason.ContainerEncrypted(SourceFormat.AEGIS))
        }
        val db = try {
            json.decodeFromJsonElement(AegisDb.serializer(), dbElement)
        } catch (t: Throwable) {
            throw ImportRejection(ImportFailureReason.MalformedExport(SourceFormat.AEGIS), t)
        }

        val now = Instant.now()
        // Steam is imported, not skipped — this app generates Steam Guard codes (see
        // `TotpGenerator.generateSteam`) and the 2FA editor has a toggle for them, so refusing the
        // one export format that can carry a Steam token was a gap, and the skip message
        // ("LocalKeep only carries TOTP") told the user the app lacked a feature it ships.
        // HOTP is still skipped, and genuinely: `Totp` has no counter field to put one in.
        val (totpTyped, nonTotp) = db.entries
            .partition { it.type.equals("totp", ignoreCase = true) }
        val (steamTyped, unsupported) = nonTotp
            .partition { it.type.equals("steam", ignoreCase = true) }
        // A Steam entry needs only a readable secret. Its algorithm, digit count and period are
        // fixed by the scheme — SHA1, five characters, 30 seconds, all hard-coded in
        // `generateSteam` — so the export's own values are irrelevant and are deliberately not
        // copied onto the model, where a `digits = 5` would be a number the rest of the app rejects.
        val (steamSupported, steamMalformed) = steamTyped
            .partition { TotpFieldParser.parse(it.info.secret) != null }
        // An export's `info` is untrusted input, and it used to be copied onto `Totp` verbatim.
        // `digits: 7`, `period: 0` or an algorithm this app cannot compute all produce an item that
        // imports "successfully" and then never renders a code — every TotpGenerator call site
        // wraps its `require` in runCatching, so the failure is silent — or, for a folded unknown
        // algorithm, renders six digits that are wrong. Named in `skipped` instead, which is the
        // same treatment an HOTP entry already gets and the only outcome the user can act on.
        val (supported, malformed) = totpTyped.partition { it.info.isSupported() }
        // Which half of `isSupported` actually failed decides which reason the user is shown. An
        // unreadable secret used to borrow `UnsupportedTotpSettings`, whose text quotes the
        // algorithm, digits and period — so `{"type":"totp","info":{"secret":""}}` was reported as
        // "Unsupported TOTP settings (SHA1, 6 digits, 30s)", naming three defaults the app fully
        // supports as the cause of a failure they had nothing to do with, and leaving the missing
        // secret unmentioned. `IncompleteEntry` ("Incomplete entry in the export") is the existing
        // reason that actually fits, and `SkipReason`'s own kdoc already scoped
        // `UnsupportedTotpSettings` to "algorithm, digit count, or period" (4-3, 2026-09-13).
        //
        // A Steam entry is always this case: `generateSteam` hard-codes the scheme's parameters and
        // never reads the export's, so there is no setting there to be unsupported.
        val (unsupportedSettings, unreadableSecret) = malformed.partition { !it.info.settingsSupported() }
        fun titleOf(entry: AegisEntry): String =
            entry.name.ifBlank { entry.issuer.ifBlank { titles.untitledCode } }
        val skipped = unsupported.map { entry ->
            SkippedEntry(
                title = titleOf(entry),
                reason = SkipReason.UnsupportedAegisType(entry.type.uppercase()),
            )
        } + (steamMalformed + unreadableSecret).map { entry ->
            SkippedEntry(title = titleOf(entry), reason = SkipReason.IncompleteEntry)
        } + unsupportedSettings.map { entry ->
            SkippedEntry(
                title = titleOf(entry),
                reason = SkipReason.UnsupportedTotpSettings(
                    algorithm = entry.info.algo,
                    digits = entry.info.digits,
                    periodSec = entry.info.period,
                ),
            )
        }
        // Minted on first use by an entry that actually lands, so the list can never hold a folder
        // nothing was filed into — the same rule BitwardenJsonImporter and CsvColumnMapper follow.
        // `group2` in the grouped fixture holds only an HOTP entry this build skips, and a folder
        // created for it would arrive empty.
        val folders = mutableListOf<Folder>()
        val folderIdsByName = mutableMapOf<String, UUID>()
        val groupNames = db.groups.filter { it.uuid.isNotBlank() && it.name.isNotBlank() }
            .associate { it.uuid to it.name }
        fun folderIdOf(entry: AegisEntry): UUID? {
            val name = (entry.group ?: entry.groups.firstOrNull()?.let { groupNames[it] ?: it })
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            return folderIdsByName.getOrPut(name) {
                UUID.randomUUID().also {
                    // Stamped now for the reason BitwardenJsonImporter stamps its folders now: an
                    // Aegis group record carries no timestamp, and a null `updatedAt` loses every
                    // rename tie-break.
                    folders += Folder(id = it, name = name, updatedAt = now)
                }
            }
        }
        val items = supported
            .map { entry ->
                val parsedSecret = TotpFieldParser.parse(entry.info.secret)!!
                VaultItem(
                    id = UUID.randomUUID(),
                    title = entry.name.ifBlank { entry.issuer.ifBlank { titles.importedCode } },
                    folderId = folderIdOf(entry),
                    favorite = entry.favorite,
                    createdAt = now,
                    updatedAt = now,
                    customFields = noteField(entry, titles),
                    content = Totp(
                        issuer = entry.issuer,
                        account = entry.name,
                        secretBase32 = parsedSecret.secretBase32,
                        algorithm = when (entry.info.algo.uppercase()) {
                            "SHA256" -> TotpAlgorithm.SHA256
                            "SHA512" -> TotpAlgorithm.SHA512
                            else -> TotpAlgorithm.SHA1
                        },
                        digits = entry.info.digits,
                        periodSec = entry.info.period,
                    ),
                )
            }
        val steamItems = steamSupported.map { entry ->
            val parsedSecret = TotpFieldParser.parse(entry.info.secret)!!
            VaultItem(
                id = UUID.randomUUID(),
                title = entry.name.ifBlank { entry.issuer.ifBlank { titles.importedCode } },
                folderId = folderIdOf(entry),
                favorite = entry.favorite,
                createdAt = now,
                updatedAt = now,
                customFields = noteField(entry, titles),
                content = Totp(
                    issuer = entry.issuer,
                    account = entry.name,
                    secretBase32 = parsedSecret.secretBase32,
                    // Left at the model defaults on purpose — `generate()` branches on `steam`
                    // before it reads any of them.
                    steam = true,
                ),
            )
        }
        // `timestampsKnown = false`: an Aegis entry carries no created/modified time, so both
        // stamps above are `now` and mean nothing in a merge. See [ParsedImport.timestampsKnown].
        Outcome.Success(
            ParsedImport(
                items = items + steamItems,
                skipped = skipped,
                folders = folders,
                timestampsKnown = false,
            ),
        )
    } catch (t: ImportRejection) {
        Outcome.Failure(AppError.Validation("Aegis import rejected (${t.reason})", t))
    } catch (t: Throwable) {
        // See ImportRejection: a library's message about an untrusted file is not shippable copy.
        // An OutOfMemoryError is not a malformed file. It carries a message about an
        // allocation, matched nothing above, and fell through to "this doesn't look like a
        // valid export" — a claim about the user's file that the app had not earned
        // (V14, 2026-09-13). The whole cause chain is checked because the parser wraps what
        // it throws; same shape as KdbxImporter.kt:128.
        val reason = if (generateSequence(t) { it.cause }.any { it is OutOfMemoryError }) {
            ImportFailureReason.ExportNeedsMoreMemory(SourceFormat.AEGIS)
        } else {
            ImportFailureReason.MalformedExport(SourceFormat.AEGIS)
        }
        Outcome.Failure(AppError.Validation("failed to parse Aegis export", ImportRejection(reason, t)))
    }

    /**
     * An entry's own free-text note, rescued into a custom field.
     *
     * [Totp] has no notes field of its own, so a note like "backup codes taped inside the router"
     * had nowhere to go and was discarded — while the wizard still counted the entry as fully
     * carried. The same shape [BitwardenJsonImporter]'s `identityNotesField` and
     * [OnePuxImporter]'s already handle for `Identity`, down to the default name, which is why this
     * reuses [ImportDefaultTitles.identityNotesFieldName] rather than inventing a string this
     * module cannot translate (4-2, 2026-09-13).
     */
    private fun noteField(entry: AegisEntry, titles: ImportDefaultTitles): List<CustomField> {
        val note = entry.note.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(CustomField(name = titles.identityNotesFieldName, value = note, type = CustomFieldType.TEXT))
    }
}

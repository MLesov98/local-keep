package com.localkeep.core.import

/**
 * The source format an [ImportFailureReason] or [Importer.sourceFormat] names.
 *
 * Not the display name itself — "Bitwarden", "KeePass", "Aegis", "1Password" are brand names
 * (never translated) and the strings for them already live in
 * `feature/import`'s `ImportSource.labelRes`; this module cannot hold Android string resources at
 * all (see the module-level kdoc on [Importer]), so it names the format without ever spelling it.
 */
enum class SourceFormat {
    BITWARDEN,
    KDBX,
    AEGIS,
    ONEPUX,
    CSV,

    /**
     * The four formats added on 2026-09-17. Each importer read its own file correctly from the
     * day it landed and answered `CSV` when asked what it was, because a new constant here
     * breaks the exhaustive `when` in `feature/import` that turns one of these into a brand
     * name — so the enum and that `when` are one change and had to be made together.
     *
     * `PROTONPASS`, not `PROTONPASSJSON`: the constant names the product, and Proton Pass CSV
     * is read by [CsvImporter] and is still CSV. Same for Dashlane, whose logins go through
     * [CsvImporter] while its four non-credential files go through [DashlaneImporter] — which
     * is why `DASHLANE` names the four, and a `credentials.csv` still reports `CSV`.
     */
    KEEPER,
    ENPASS,
    PROTONPASS,
    DASHLANE,
}

/**
 * Why an importer could not parse a source file at all.
 *
 * Carries no English text. `core/import` is a pure-Kotlin/JVM leaf module (`kotlin("jvm")`, no
 * Android plugin) and cannot call `stringResource` or
 * hold `strings.xml`, so it cannot author UI copy the way `feature/settings` or
 * `feature/unlock` do. `feature/import` is the only place a value of this type is turned into
 * text, mirroring the pattern `ImportWizardViewModel.ImportError.Simple` already uses for the
 * literals that ViewModel authors itself.
 *
 * This type replaces the free-text messages [ImportRejection] and the importers' own
 * `AppError.Validation(...)` calls used to carry. Those strings used to reach the UI **unresolved**
 * through `ImportWizardViewModel`'s `ImportError.FromImporter(parsed.error.message)` — a raw
 * `AppError.message` is documented as internal-only and never
 * meant to reach a user, but that one code path echoed it anyway, forever, in English (findings F2,
 * F22). A typed reason makes the leak structurally impossible: there is no English field left on
 * the failure path for a careless `.message` read to pick up.
 */
sealed interface ImportFailureReason {
    /**
     * The export's own container (JSON, zip, or KDBX database) could not be parsed at all — the
     * file is not a valid export of [source], or this build does not yet understand the shape it
     * used. Collapses several distinct English messages the affected importers used to author
     * separately (an outer JSON-decode failure, an inner db-JSON-decode failure, and the generic
     * catch-all) — none of that distinction is something a user can act on differently.
     */
    data class MalformedExport(val source: SourceFormat) : ImportFailureReason

    /**
     * The export is encrypted at the container level, and this format offers no way to import it
     * without first turning that off (Bitwarden's own export password, Aegis's backup encryption).
     * Distinct from [WrongPassword]: KDBX *is* an encrypted container this module knows how to open
     * given the right password, so a KDBX credential failure is never this case.
     */
    data class ContainerEncrypted(val source: SourceFormat) : ImportFailureReason

    /**
     * The export is sealed with a key belonging to the *account* it came from — Bitwarden's
     * "Account restricted" export — so nothing the user can type opens it and no password step is
     * offered.
     *
     * Split out of [ContainerEncrypted] on 2026-09-17 (V3-02). Both cases shared one sentence,
     * and that sentence named the wrong one: it called an account-restricted file
     * "password-protected", which is the shape that now *works* (see [WrongExportPassword] and
     * `BitwardenEncryptedExport`), and told the user to turn encryption off — advice that is worse
     * than the alternative, because re-exporting as "Password protected" imports here and is safer
     * than writing every password to storage in the clear. It also contradicted the wizard's own
     * STEP 3 card three lines below it on the same screen.
     *
     * [ContainerEncrypted] keeps the cases where turning encryption off really is the only remedy
     * (an Aegis encrypted backup), which is why the two cannot share a reason.
     */
    data class AccountEncrypted(val source: SourceFormat) : ImportFailureReason

    /**
     * The password typed for a *password-protected export* did not open it — the export's own
     * key-validation blob failed its MAC.
     *
     * Separate from [WrongPassword], which is KDBX's and says so in its copy ("this KeePass
     * database"), and separate from [ContainerEncrypted], which is the case where no password can
     * help: an account-encrypted Bitwarden export is sealed with a key that never leaves the
     * account, while a password-protected one carries its own KDF parameters and opens for anyone
     * who knows what the user typed at export time. Telling the second group to "turn off
     * encryption and export again" would be advice for a problem they do not have.
     */
    data class WrongExportPassword(val source: SourceFormat) : ImportFailureReason

    /**
     * A KDBX header integrity check failed (KDBX4's header HMAC, KDBX3's header hash) — the derived
     * key does not match, which happens if and only if the password was wrong. See
     * [KdbxImporter.describeFailure] for why a *block*-level HMAC failure is deliberately not this
     * case: the header already verified there, so the password was right and the payload itself is
     * damaged — see [DamagedKdbxFile].
     */
    data class WrongPassword(
        /**
         * True when no key file was supplied. KDBX has one credential check and it covers the whole
         * composite key, so a database that wants a key file answers exactly as a mistyped password
         * does — which is how eight databases in the real-world corpus were told their correct
         * password was wrong. Nothing in the format distinguishes the two, so when the user gave no
         * key file the copy has to name both possibilities; when they did give one, "wrong password"
         * is the only thing left to say.
         */
        val keyFileMayBeNeeded: Boolean,
    ) : ImportFailureReason

    /**
     * The key file the user picked is not usable as one: a `.keyx` whose `Hash` attribute does not
     * match its own data, or hex/base64 key material that will not decode.
     *
     * Kept apart from [WrongPassword] because it is the one key-file failure the app can actually
     * prove, and the remedy is different: the password is not in question, the picked file is.
     */
    data object UnreadableKeyFile : ImportFailureReason

    /** The bytes are not a KDBX database at all — wrong file picked, or the magic number mismatch. */
    data object NotAKdbxFile : ImportFailureReason

    /**
     * A KeePass 1.x `.kdb`, which is a KeePass database this build cannot open — not "not a KeePass
     * database", which is what [NotAKdbxFile] says and which is false about this file.
     *
     * The two share their first four bytes and differ in the second signature dword, so `detect`
     * says yes and the parse used to answer with the wrong sentence. Nothing here can read the old
     * format at all; what the user can do is open it in KeePass and save it as `.kdbx`, which is
     * why the reason is separate — that remedy fits no other case.
     */
    data object LegacyKeePass1File : ImportFailureReason

    /**
     * A KDBX file whose header verified (so the password was right) but whose body the KDBX library
     * rejects — genuine file damage, not a credential problem.
     */
    data object DamagedKdbxFile : ImportFailureReason

    /**
     * The KDBX file's own key-derivation asks for more memory than this device allows an app, so the
     * derivation died before any credential check could run.
     *
     * Distinct from every other case here, and the distinction is the whole point: an
     * `OutOfMemoryError` during the KDF used to fall into [DamagedKdbxFile]'s catch-all, so a
     * KeePassXC database with the product's **default** 64 MiB Argon2d told an API 28/29 user that
     * their intact file "looks damaged" — and told them exactly the same thing when the password was
     * wrong, when a key file was used, and when the file really was truncated. Four different
     * situations, one message, none of them true. Measured on a 48 MiB heap: the same bytes and the
     * same password import cleanly on a 192 MiB device (G13, 2026-09-11).
     *
     * This app caps its *own* vaults at `KdfParams.PORTABLE_MAX_MEMORY_KIB` so they stay portable;
     * an imported database carries whatever its author chose, and nothing here can lower it. The
     * remedy belongs to the user in the app they are leaving, so the copy has to name it.
     */
    data object KdbxKdfTooLarge : ImportFailureReason

    /**
     * A JVM/Android linkage failure inside the KDBX library itself (the historical B4a defect: a
     * missing StAX API on-device). Never the user's fault, and must never be phrased as a wrong
     * password or a damaged file — seeing a linkage error and reporting a familiar credential
     * message to the person holding the phone is exactly the failure this type exists to prevent.
     *
     * Also covers any failure raised *after* `DomDatabaseWrapper.load` returned, while
     * [KdbxImporter] walks the open database into vault items. By then the header has verified and
     * the password has proved correct, so the same rule applies: whatever broke is on this side of
     * the container, and telling the user their file looks damaged would send them to re-export a
     * file that just opened.
     */
    /**
     * A JSON/1PUX export whose parse ran out of memory on this device.
     *
     * The same shape as [KdbxKdfTooLarge], for the other importers. Their `catch (t: Throwable)`
     * swallowed an `OutOfMemoryError` into [MalformedExport], so a perfectly valid export was
     * reported as "This doesn't look like a valid %1$s export" — a claim about the user's file
     * that the app had not earned (V14). Deliberately **not** [ExportTooLarge]: the wizard already
     * caps imports at 64 MB, so saying the file is too large would assert a size it does not have.
     */
    data class ExportNeedsMoreMemory(val source: SourceFormat) : ImportFailureReason

    data object KdbxSupportBroken : ImportFailureReason

    /** A `.1pux` archive with no `export.data` entry inside it — not a 1Password export. */
    data object OnePuxMissingExportData : ImportFailureReason

    /**
     * A single entry inside the export decompressed past the size this importer will read — the
     * decompression-bomb guard in [OnePuxImporter.readCapped]. [limitMb] is the fixed cap, not a
     * value derived from the untrusted file, so it is safe to show.
     *
     * Since 2026-10-02 also the wizard's answer for a picked file over its own read cap, which used
     * to be reported as a file it could not read.
     */
    data class ExportTooLarge(val limitMb: Long) : ImportFailureReason

    /**
     * Everything not thrown deliberately by this module: a library's own exception, whose message
     * may quote the file's own bytes, a class name, or a path, and therefore can never reach the UI
     * by design. Also covers the handful of defensive/contract-violation paths that should be
     * unreachable through the wizard's own flow (e.g. `KdbxImporter`'s password-free `parse`
     * overload, which always fails by contract — see [PasswordProtectedImporter]).
     */
    data object Unexpected : ImportFailureReason
}

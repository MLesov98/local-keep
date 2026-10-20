package com.localkeep.core.import

import com.localkeep.core.common.Outcome

/**
 * A single third-party migration format (spec §8.4). Implementations are pure and operate
 * entirely in-memory: no network, no plaintext temp files. [parse] never throws — malformed
 * or unexpected input becomes [Outcome.Failure], never an uncaught exception.
 */
interface Importer {
    /**
     * What format this importer reads, for the format-detection / preview UI. A [SourceFormat], not
     * a display name: this module is a pure-Kotlin/JVM leaf and cannot author one (see
     * [ImportFailureReason]'s kdoc) — `feature/import` maps this to a brand-name string it already
     * owns (`ImportSource.labelRes`).
     */
    val sourceFormat: SourceFormat

    /** Cheap structural check (magic bytes / JSON shape / zip entry names) — no full parse. */
    fun detect(bytes: ByteArray): Boolean

    /**
     * Full parse → a [ParsedImport]. Tolerant of malformed rows/entries (skips, never throws).
     *
     * Skipping is still allowed, but no longer silent: every entry this returns without is named in
     * [ParsedImport.skipped] so the wizard can say what did not cross over.
     *
     * [titles] supplies the fallback titles for entries the source left blank. Defaulted so every
     * existing call site and test keeps compiling; see [ImportDefaultTitles]'s kdoc for why the
     * production caller ([com.localkeep.feature.importwizard.ImportWizardViewModel]) always
     * overrides it rather than relying on the default.
     */
    fun parse(bytes: ByteArray, titles: ImportDefaultTitles = ImportDefaultTitles()): Outcome<ParsedImport>
}

/**
 * An [Importer] whose source file may itself be encrypted and need a user-supplied password before
 * it can be parsed. KeePass `.kdbx` (12d) is one; a Bitwarden export written with an export
 * password is the other. [Importer.parse] is still implemented — for KDBX (see [KdbxImporter]) it
 * always fails, directing the caller to this overload via [ImportFailureReason.Unexpected], while
 * [BitwardenJsonImporter] serves the ordinary unencrypted export from it.
 */
interface PasswordProtectedImporter : Importer {
    /**
     * Whether *these bytes* need a password, asked before the wizard shows a step to type one into.
     *
     * A `.kdbx` always does, which is the default. A Bitwarden export usually does not: the format
     * is the same JSON either way and only the file itself says which it is, so sending every
     * Bitwarden import through a password step — the alternative, since the wizard's old check was
     * per *source* rather than per file — would ask the overwhelming majority of users for a
     * password that does not exist.
     */
    fun needsPassword(bytes: ByteArray): Boolean = true

    /**
     * [keyFile] is the raw bytes of the source's key file, when it has one. KeePass lets a database
     * be protected by a password, a key file, or both, and a database that wants one cannot be
     * opened without it however correct the password is — so it is the second half of a credential,
     * not an option. Null means the user supplied none; an empty [password] with a non-null
     * [keyFile] is the key-file-only database.
     *
     * The bytes are secret material for as long as this call runs and are wiped by the
     * implementation before it returns, exactly as [password] is.
     */
    fun parse(
        bytes: ByteArray,
        password: CharArray,
        titles: ImportDefaultTitles = ImportDefaultTitles(),
        keyFile: ByteArray? = null,
    ): Outcome<ParsedImport>
}

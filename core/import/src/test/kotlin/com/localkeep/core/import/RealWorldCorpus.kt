package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The real-world import corpus: authentic export files collected from the Bitwarden, KeePassXC,
 * pykeepass and Aegis test suites and checked in under `src/test/resources/realworld/`.
 *
 * Every `RealWorld*ImportTest` runs its files through the importer the wizard would pick for them,
 * so what these tests measure is the app's actual migration surface rather than a fixture this
 * project wrote to suit its own parser. Provenance, licence, passwords and key files for each
 * source live in that source's `PROVENANCE.md`.
 */
object RealWorldCorpus {

    /** Bytes of one corpus file, e.g. `bytes("bitwarden/plain-export.json")`. */
    fun bytes(path: String): ByteArray =
        RealWorldCorpus::class.java.getResourceAsStream("/realworld/$path")?.use { it.readBytes() }
            ?: error("missing corpus file: realworld/$path")

    /** The corpus file as UTF-8 text, for the fixtures whose ground truth is read out of the file. */
    fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)

    /**
     * The parsed result, or a test failure naming the typed reason the importer gave instead.
     *
     * A bare `as Outcome.Success` would report a real failure as a ClassCastException with no
     * reason in it, which is the one thing these tests exist to tell apart.
     */
    fun Outcome<ParsedImport>.orFail(path: String): ParsedImport = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> error("$path failed to parse: ${reasonOf(this)}")
    }

    /**
     * The [ImportFailureReason] an importer attached to a failure, read off `AppError.cause` — the
     * only place it lives, since `AppError.message` is internal-only text that must never be
     * asserted on (see [ImportFailureReason]'s kdoc).
     */
    fun reasonOf(failure: Outcome.Failure): ImportFailureReason =
        (failure.error.cause as? ImportRejection)?.reason
            ?: error("AppError.cause was not an ImportRejection carrying a typed reason: ${failure.error.cause}")

    /** The typed reason for a parse that must fail, or a test failure if it unexpectedly succeeded. */
    fun Outcome<ParsedImport>.failureReason(path: String): ImportFailureReason = when (this) {
        is Outcome.Failure -> reasonOf(this)
        is Outcome.Success -> error("$path parsed successfully when a named failure was expected")
    }

    /**
     * Data rows in a CSV fixture — the file's own row count minus its header.
     *
     * Derived through [CsvColumnMapper.parseRows] rather than by counting newlines, because several
     * corpus files (LastPass secure notes, Firefox's account blob) carry quoted cells with embedded
     * newlines, where a line count is not a row count.
     */
    fun csvDataRows(path: String): Int = (CsvColumnMapper.parseRows(bytes(path)).size - 1).coerceAtLeast(0)

    /**
     * The length of a top-level JSON array in a corpus file — how many records the export itself
     * says it holds, counted without going through any importer.
     *
     * An absent key counts as zero, which is the right reading for the shape it appears in here:
     * Bitwarden omits `collections` from a personal-vault export and `folders` from an organisation
     * one.
     */
    fun jsonArraySize(path: String, key: String): Int =
        ((Json.parseToJsonElement(text(path)) as JsonObject)[key] as? JsonArray)?.size ?: 0
}

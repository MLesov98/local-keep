package com.localkeep.test.fuzz

import com.localkeep.core.common.Outcome
import com.localkeep.core.import.ImportFailureReason
import com.localkeep.core.import.ImportRejection
import com.localkeep.core.import.Importer

/**
 * The oracle every importer target is checked against.
 *
 * The point of writing this down once is that **"it threw" is not a bug here**. These parsers throw
 * internally on purpose and catch at the boundary; `KdbxImporter.describeFailure` is built entirely
 * around matching on a cause chain. A fuzzer told to report exceptions would bury the signal.
 *
 * So the properties are stated positively:
 *
 *  1. `parse` returns an [Outcome] — it never propagates a throwable to its caller. For the
 *     importers this is the whole published contract (`Importer.parse` KDoc).
 *  2. No unbounded allocation from an attacker-controlled length field. Enforced by the 256 MB heap
 *     this module's test JVM runs with (see build.gradle.kts) rather than by an assertion.
 *  3. No hang. Jazzer's own timeout catches pathological backtracking and decompression loops.
 *  4. Nothing but authored text reaches the user. This is the property with real teeth: it
 *     generalises `ImportErrorHygieneTest` — which pins the messages for exactly one garbage input —
 *     across every input the fuzzer can invent.
 *
 * Property 2 has a stated blind spot rather than a silent one. `BitwardenJsonImporter`,
 * `AegisJsonImporter`, `OnePuxImporter` and — since 2026-09-13 — `CsvImporter` end in
 * `catch (t: Throwable)`, which catches
 * `OutOfMemoryError` too, so an over-allocation inside them surfaces as an ordinary
 * `Outcome.Failure` and this harness cannot see it. That is the correct behaviour for the app and an
 * inconvenient one for a fuzzer. `BreachFilter.load` does not catch, which is why the allocation
 * hypothesis is tested there — see [BreachFilterFuzzTest].
 */
object ImporterOracle {

    /**
     * Every string an importer authored for a failed parse, copied from the source rather than
     * paraphrased. An allow-list, not a pattern: the set of messages a user can see must stay closed
     * and reviewable, so adding one has to be deliberate — and that is the moment to ask whether it
     * leaks. `ImportErrorHygieneTest` keeps the same list for its single fixed input.
     */
    private val authoredMessages: Set<String> = setOf(
        // BitwardenJsonImporter.kt:130, :134, :145, :149
        "malformed Bitwarden export JSON",
        "this Bitwarden export is password-protected — re-export as unencrypted JSON",
        "failed to parse Bitwarden export",
        // AegisJsonImporter.kt:116, :119 and its rejections
        "malformed Aegis export JSON",
        "malformed Aegis db JSON",
        "this Aegis export is password-encrypted — export with encryption OFF",
        "failed to parse Aegis export",
        // OnePuxImporter.kt:107, :111, :132, :135
        ".1pux archive has no export.data entry",
        "malformed export.data JSON inside .1pux",
        "failed to parse .1pux export",
        "export.data exceeds the 256 MB import limit",
        // KdbxImporter.kt:30, :73, :79, :82, :85
        "KDBX requires a password — call parse(bytes, password)",
        "wrong password for this KeePass database",
        "this file is not a KeePass database",
        "the KeePass database could not be read — the file looks damaged",
        // CsvColumnMapper.kt's CsvImporter.describeFailure. Since 2026-09-13 it catches its own
        // throwables rather than going through runCatchingOutcome, so that it can tell an
        // OutOfMemoryError from a bad file (5-3); everything else still reports the exact
        // cause-less "Unexpected failure" that runCatchingOutcome produced, which is why both
        // strings are here.
        "failed to parse CSV export",
        "Unexpected failure",
    )

    /**
     * The shapes a *library* message takes. Kept alongside the allow-list because they are the
     * diagnostic: when this fires, the failure report should say what leaked, not just that
     * something did. These are the exact four `ImportErrorHygieneTest` checks.
     */
    private fun leakDescription(message: String): String? = when {
        message.contains("Exception") -> "an exception class name"
        message.contains("kotlinx") -> "a library package name"
        message.contains("/") -> "something path-shaped"
        message.contains("JSONObject") -> "a parser internal"
        else -> null
    }

    /** Applies properties 1 and 4 to one [Importer.parse] call. Throws to report a finding. */
    fun check(name: String, importer: Importer, bytes: ByteArray) {
        check(name) { importer.parse(bytes) }
    }

    /** As [check], for a call this harness builds itself (the KDBX two-argument overload). */
    fun check(name: String, parse: () -> Outcome<*>) {
        val outcome = try {
            parse()
        } catch (t: Throwable) {
            // Property 1. Deliberately re-thrown with context: Jazzer reports the throwable, and
            // "NumberFormatException from Bitwarden" is a finding a human can act on, where a bare
            // stack trace is a puzzle.
            throw AssertionError("$name.parse propagated ${t::class.java.name} instead of returning Outcome.Failure", t)
        }
        val failure = outcome as? Outcome.Failure ?: return
        val message = failure.error.message
        val leak = leakDescription(message)
        if (leak != null) {
            throw AssertionError("$name surfaced $leak to the user: $message")
        }

        // A typed rejection is self-certifying, and that is the whole point of ImportFailureReason:
        // it holds no free text, so there is no field a library message or a quoted byte of the
        // input could travel in. Checking the *type* rather than the rendered string is what keeps
        // this oracle honest as the taxonomy grows — the previous version compared against a set of
        // English sentences the importers had already stopped authoring, so every typed rejection
        // read as "text it did not author" and the suite was red at HEAD without saying anything
        // true about the app.
        val rejection = failure.error.cause as? ImportRejection
        if (rejection != null) {
            if (rejection.reason::class !in KNOWN_REASONS) {
                throw AssertionError(
                    "$name returned an ImportFailureReason this oracle has never seen: " +
                        "${rejection.reason::class.simpleName}\n\n" +
                        "Adding a reason is allowed and is meant to be deliberate. Add it to " +
                        "ImporterOracle.KNOWN_REASONS once you have checked that it carries no " +
                        "attacker-controlled value — a reason may hold a constant (ExportTooLarge's " +
                        "fixed cap) but never anything read out of the file.",
                )
            }
            return
        }

        if (message !in authoredMessages) {
            throw AssertionError(
                "$name surfaced text it did not author: $message\n\n" +
                    "Either the importer gained a new authored message — in which case add it to " +
                    "ImporterOracle.authoredMessages AND ImportErrorHygieneTest, having first read " +
                    "it for anything quoting the input — or a library message is reaching the UI, " +
                    "which the project's error-message rule forbids outright.",
            )
        }
    }

    /**
     * Every [ImportFailureReason] the importers may return, pinned so that adding one is a
     * deliberate act rather than a silent widening.
     *
     * The structural check above would pass any `ImportRejection` on its own — the type guarantees
     * no free text — so this set is not what makes the failure path safe. It is what makes a *new*
     * reason arrive as a build failure and a decision, which is the same reason
     * `BackupInclusionSetTest` asserts two exclusion lists are equal rather than trusting them to
     * stay in step. The question to answer when this fires is the one in the message: does the new
     * reason carry anything read out of the untrusted file?
     */
    private val KNOWN_REASONS = setOf(
        ImportFailureReason.MalformedExport::class,
        ImportFailureReason.ContainerEncrypted::class,
        ImportFailureReason.AccountEncrypted::class,
        ImportFailureReason.WrongPassword::class,
        ImportFailureReason.NotAKdbxFile::class,
        ImportFailureReason.DamagedKdbxFile::class,
        ImportFailureReason.KdbxSupportBroken::class,
        ImportFailureReason.OnePuxMissingExportData::class,
        ImportFailureReason.ExportTooLarge::class,
        // Both added 2026-09-13, both already returnable before that: KdbxKdfTooLarge since G13 and
        // ExportNeedsMoreMemory since V14, so this set had been stale — the oracle would have failed
        // a fuzz run on a reason the importers were already authoring, which reads as a finding and
        // is not one. Each carries a SourceFormat or nothing at all; neither holds a value read out
        // of the file, which is the question this set exists to force.
        ImportFailureReason.KdbxKdfTooLarge::class,
        ImportFailureReason.ExportNeedsMoreMemory::class,
        ImportFailureReason.Unexpected::class,
        // 2026-09-16, WP12: a key file that is not one of the four KeePass key-file shapes, and a
        // KeePass 1.x `.kdb` (shares KDBX's first magic bytes). Neither carries anything read out of
        // the file; both name a fixed condition.
        ImportFailureReason.UnreadableKeyFile::class,
        ImportFailureReason.LegacyKeePass1File::class,
    )
}

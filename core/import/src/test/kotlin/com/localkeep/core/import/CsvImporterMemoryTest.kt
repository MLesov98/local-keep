package com.localkeep.core.import

import com.localkeep.core.common.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A CSV that will not fit in memory must not be called corrupted.
 *
 * The CSV analogue of [KdbxKdfTooLargeTest], and the gap V14 left behind: on 2026-09-13 the three
 * JSON importers and KDBX all gained an `OutOfMemoryError` branch reporting the honest
 * [ImportFailureReason.ExportNeedsMoreMemory], and `CsvImporter` — the one Chrome, Apple/Keychain
 * and the generic "Other/CSV" source all route through — was the one left out. It went through
 * `runCatchingOutcome`, which reports every throw as a cause-less `AppError.Unknown`, which the
 * wizard resolves to `Unexpected`: "Couldn't read this file. It may be corrupted or in a format
 * LocalKeep doesn't support." Both halves false — the file is fine, the device ran out of room
 * (5-3).
 */
class CsvImporterMemoryTest {

    private fun reasonFor(t: Throwable): ImportFailureReason {
        val error = CsvImporter.describeFailure(t)
        val validation = assertIs<AppError.Validation>(error)
        val rejection = assertIs<ImportRejection>(validation.cause)
        return rejection.reason
    }

    @Test
    fun `an out-of-memory reading a CSV is not a corrupted file`() {
        assertEquals(
            ImportFailureReason.ExportNeedsMoreMemory(SourceFormat.CSV),
            reasonFor(OutOfMemoryError("Failed to allocate a 1040 byte allocation")),
        )
    }

    /** The whole cause chain is checked, for the same reason the four siblings check theirs. */
    @Test
    fun `an out-of-memory wrapped by its caller is still recognised`() {
        assertEquals(
            ImportFailureReason.ExportNeedsMoreMemory(SourceFormat.CSV),
            reasonFor(IllegalStateException("could not map rows", OutOfMemoryError("Java heap space"))),
        )
    }

    /**
     * The control, and the one that matters most: the new branch must not swallow anything else.
     * Every other throwable keeps the exact fallback `runCatchingOutcome` produced — cause-less, so
     * `ImportError.FromImporter` still resolves it to `ImportFailureReason.Unexpected` — and carries
     * no text from the library that threw.
     */
    @Test
    fun `any other failure keeps the unchanged generic fallback`() {
        val error = CsvImporter.describeFailure(IllegalStateException("kotlinx.serialization said /tmp/x"))

        assertIs<AppError.Unknown>(error)
        assertEquals("Unexpected failure", error.message)
        assertNull(error.cause, "a cause-less AppError is what resolves to Unexpected")
    }
}

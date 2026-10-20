package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An encrypted Aegis export whose header carries no slots was not recognised as Aegis at all, so
 * the user was told "that file isn't in a format LocalKeep recognizes" instead of "this export is
 * password-encrypted — export with encryption OFF" (device, 2026-09-14). `detect` accepted only a
 * decrypted `db` object or a `header.slots` list; an export whose `db` is the ciphertext string is
 * the very shape Aegis writes when encryption is on, and is the one that most needs the specific
 * message, because the fix is on the Aegis side.
 */
class AegisEncryptedDetectionTest {

    private val encryptedWithoutSlots = """{"version":1,"header":{},"db":"QkFTRTY0Q0lQSEVSVEVYVA=="}"""

    @Test
    fun anEncryptedExportWithoutSlotsIsStillRecognisedAsAegis() {
        assertTrue(AegisJsonImporter.detect(encryptedWithoutSlots.toByteArray()))
    }

    @Test
    fun andItIsRefusedAsEncryptedNotAsUnrecognised() {
        val outcome = AegisJsonImporter.parse(encryptedWithoutSlots.toByteArray())
        val rejection = (outcome as Outcome.Failure).error.cause as ImportRejection
        assertEquals(ImportFailureReason.ContainerEncrypted(SourceFormat.AEGIS), rejection.reason)
    }

    /** Control: a JSON file that merely has a string somewhere is not an Aegis export. */
    @Test
    fun aStrayJsonFileIsNotDetected() {
        assertFalse(AegisJsonImporter.detect("""{"encrypted":false,"items":[]}""".toByteArray()))
        assertFalse(AegisJsonImporter.detect("""{"db":"x"}""".toByteArray()))
        assertFalse(AegisJsonImporter.detect("this is not a vault".toByteArray()))
    }
}

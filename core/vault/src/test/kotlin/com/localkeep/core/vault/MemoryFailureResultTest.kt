package com.localkeep.core.vault

import com.localkeep.core.crypto.KdfParams
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An allocation failure while opening a vault that fits this device must not be reported as a device
 * limit.
 *
 * Measured on an API 29 device with a 48 MiB growth limit: after half an hour of ordinary use the
 * process sat at 34 MiB, and a **three-item** vault created on that same device refused to open
 * three times running with "This vault needs more memory than this device allows an app… Open it on
 * a phone with more memory, then lower its encryption settings". `am force-stop`, and the identical
 * password opened it in seconds. The vault never needed more memory than the device allows; the
 * process did — and the one remedy that works, closing the app fully, was never mentioned. Found by
 * the pre-iOS gap sweep 2026-09-11 (G9).
 */
class MemoryFailureResultTest {

    @Test
    fun `a vault within the portable ceiling is a transient process failure`() {
        assertEquals(
            UnlockResult.VAULT_MEMORY_TRANSIENT,
            memoryFailureResult(KdfParams.PORTABLE_MAX_MEMORY_KIB),
            "a vault at the ceiling fits every supported device, so the process was what ran out",
        )
    }

    @Test
    fun `the cost this build actually ships is transient`() {
        assertEquals(
            UnlockResult.VAULT_MEMORY_TRANSIENT,
            memoryFailureResult(KdfParams.DEFAULT.memoryKib),
            "the params this build creates must never produce buy-another-phone advice",
        )
    }

    @Test
    fun `a vault above the portable ceiling really does need more memory`() {
        assertEquals(
            UnlockResult.VAULT_NEEDS_MORE_MEMORY,
            memoryFailureResult(KdfParams.PORTABLE_MAX_MEMORY_KIB + 1),
        )
    }

    /** No header was read, so nothing is known. Keep the older answer rather than guess. */
    @Test
    fun `an unknown cost keeps the cautious answer`() {
        assertEquals(UnlockResult.VAULT_NEEDS_MORE_MEMORY, memoryFailureResult(null))
    }
}

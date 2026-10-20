package com.localkeep.feature.files

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadableSizeTest {

    /**
     * The decimal separator must not follow the device locale.
     *
     * `"%.1f MB".format(...)` (no explicit locale) resolves the format against
     * `Locale.getDefault()`, so on a device set to e.g. French the same call produces a comma:
     * "4,0 MB". Every other formatted value in this codebase (VaultRepository, RecoveryKit,
     * OtpAuthParser) pins `Locale.ROOT` explicitly for this exact reason — this is the one that
     * hadn't been brought in line yet.
     */
    @Test
    fun `size formatting does not follow the default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("4.0 MB", 4_194_304L.asReadableSize())
            assertEquals("1.0 GB", 1_073_741_824L.asReadableSize())
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `small sizes keep their own units`() {
        assertEquals("4 KB", 4_096L.asReadableSize())
        assertEquals("4.0 MB", 4_194_304L.asReadableSize())
    }

    @Test
    fun `sub-kilobyte sizes are left to the caller to localise`() {
        // "900 bytes" was built here as an English literal until 2026-07-30, and reached a French
        // device unchanged. The word now comes from files_size_bytes; the arithmetic branch's job
        // is to say "this one is not mine".
        assertNull(900L.asReadableSize())
        assertNull(0L.asReadableSize())
        assertNull(1_023L.asReadableSize())
    }

    @Test
    fun `a gigabyte attachment reads in gigabytes`() {
        // Nothing caps attachment size, so without this branch the MB one ran off the end: a 2 GB
        // file reported as "2048.0 MB".
        assertEquals("1.0 GB", 1_073_741_824L.asReadableSize())
        assertEquals("2.0 GB", 2_147_483_648L.asReadableSize())
    }

    @Test
    fun `one byte below the boundary is still megabytes`() {
        assertEquals("1024.0 MB", (1_073_741_824L - 1).asReadableSize())
    }
}

package com.localkeep.autofill

import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutofillSessionCacheTest {
    @After
    fun tearDown() {
        AutofillSessionCache.clearAll()
    }

    @Test
    fun recallsOnlySamePackageAndDomain() {
        val itemId = UUID.randomUUID()
        AutofillSessionCache.remember("com.example.browser", "example.com", itemId, nowMs = 10)

        assertEquals(
            itemId,
            AutofillSessionCache.recall(
                "com.example.browser",
                "EXAMPLE.COM",
                nowMs = 11,
            )?.itemId,
        )
        assertNull(
            AutofillSessionCache.recall(
                "com.other.browser",
                "example.com",
                nowMs = 11,
            ),
        )
    }

    @Test
    fun expiredEntryIsRemoved() {
        AutofillSessionCache.remember(
            "com.example",
            null,
            UUID.randomUUID(),
            nowMs = 10,
        )

        assertNull(
            AutofillSessionCache.recall(
                "com.example",
                null,
                nowMs = 10 + AutofillSessionCache.TTL_MS + 1,
            ),
        )
        assertNull(AutofillSessionCache.recall("com.example", null, nowMs = 11))
    }

    /**
     * An entry that is never recalled has no eviction path other than [AutofillSessionCache]'s
     * opportunistic sweep inside `remember()` — recall()'s own TTL check never runs for a key
     * nobody asks about again. Without that sweep this cache is an unbounded, process-lifetime
     * ConcurrentHashMap.
     */
    @Test
    fun rememberSweepsEntriesNeverRecalledOnceTheirTtlHasPassed() {
        AutofillSessionCache.remember(
            "com.example.abandoned",
            "abandoned.example",
            UUID.randomUUID(),
            nowMs = 0,
        )
        assertEquals(1, AutofillSessionCache.size)

        // A later, unrelated remember() — the abandoned entry above was never recalled — should
        // still sweep it once its own TTL has elapsed.
        AutofillSessionCache.remember(
            "com.example.other",
            "other.example",
            UUID.randomUUID(),
            nowMs = AutofillSessionCache.TTL_MS + 1,
        )

        assertEquals(1, AutofillSessionCache.size)
    }
}

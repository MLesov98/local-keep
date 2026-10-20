package com.localkeep.core.health

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The build gate `checkBreachCorpusNotPlaceholder` protects the release process. This protects the
 * user, and they are not the same thing.
 *
 * The placeholder shipped at `app/src/main/assets/breached-passwords.filter` is 190 bytes and — this
 * is the part that matters — **is a completely valid filter**. Decoded by hand on 2026-07-28: magic
 * `4c504246`, version 1, 33 entries, vintage `"ad-hoc placeholder corpus (Task 4 pre-real-corpus
 * note) — replace before release"`. It parses. `hasData` was `true`. So the Security screen would
 * report every password as absent from the breach set, having compared it against thirty-three
 * passwords, and say so in the words it uses when a real check ran.
 *
 * The rule: *"User-facing counts and claims must be literally true: ... no 'checked against the
 * breach set' when no check ran."* A gate that only runs at build time cannot keep that promise —
 * it can only stop one particular way of breaking it.
 *
 * Every test here fails without the `minimumCredibleEntries` check in `ensureLoaded`.
 */
class BreachFilterPlaceholderTest {

    /** Rebuilds a stub of the same shape as the shipped placeholder: a valid asset, 33 entries. */
    private fun placeholderAsset(): ByteArray = BreachFilterAssetBuilder.build(
        passwords = (1..33).map { "placeholder-password-$it" },
        vintage = "ad-hoc placeholder corpus — replace before release",
    )

    @Test
    fun `a placeholder corpus reports no data rather than a clean bill of health`() {
        val filter = BreachFilter { ByteArrayInputStream(placeholderAsset()) }
        filter.ensureLoaded()

        assertFalse(
            filter.hasData,
            "a 33-entry stub must be the 'no breach data' state, not a corpus. If hasData is true " +
                "here, every password on the Security screen is reported as unbreached having been " +
                "checked against nothing.",
        )
        assertEquals("not loaded", filter.filterVintage())
    }

    @Test
    fun `a corpus above the floor still loads and still answers`() {
        // The floor must not break the real thing.
        //
        // The margin is 1% rather than 10 passwords, and the reason is worth recording: the header's
        // `entryCount` is the number of DISTINCT BUCKETS after dedup, not the number of passwords in.
        // At the default riceK of 10 the universe is entries << 10, so ~100,000 passwords collide
        // into ~99,950 buckets — and a first version of this test built exactly
        // MIN_CREDIBLE_ENTRY_COUNT + 10 passwords and failed, because the asset it produced held
        // fewer entries than it was given. Anything testing near this boundary must account for it.
        val passwords = (1..(BreachFilter.MIN_CREDIBLE_ENTRY_COUNT.toInt() * 101 / 100)).map { "pw-$it" }
        val filter = BreachFilter { ByteArrayInputStream(
            BreachFilterAssetBuilder.build(passwords = passwords, vintage = "credible corpus"),
        ) }
        filter.ensureLoaded()

        assertTrue(filter.hasData, "a corpus over the floor must load")
        assertEquals("credible corpus", filter.filterVintage())
        assertTrue(
            filter.isPotentiallyBreached("pw-1"),
            "a password that is in the corpus must still be found — the floor must not change the " +
                "membership answer, only whether the corpus counts as one",
        )
    }

    @Test
    fun `foss still reports no data`() {
        // The floor must not disturb the flavour that ships no asset at all.
        val filter = BreachFilter { null }
        filter.ensureLoaded()
        assertFalse(filter.hasData)
    }
}

package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProUnlockAnnouncementTest {

    // The literal text is irrelevant here — this test is about the transition logic, and the
    // real string now lives in strings.xml (app_pro_unlock_announcement), resolved by the caller.
    private val message = "Pro unlocked"

    @Test
    fun `an existing pro user is not congratulated on every launch`() {
        // The flow's first emission is the current tier, not a purchase.
        assertNull(proUnlockAnnouncement(previous = null, current = true, message = message))
    }

    @Test
    fun `a free user is told nothing on launch`() {
        assertNull(proUnlockAnnouncement(previous = null, current = false, message = message))
    }

    @Test
    fun `unlocking says so, once`() {
        val result = proUnlockAnnouncement(previous = false, current = true, message = message)
        assertEquals(message, result)
        // Still true on the next emission of the same value: nothing changed, so nothing is said.
        assertNull(proUnlockAnnouncement(previous = true, current = true, message = message))
    }

    @Test
    fun `losing pro is not announced as a purchase`() {
        assertNull(proUnlockAnnouncement(previous = true, current = false, message = message))
    }

    /**
     * S5-10, 2026-10-02. In a play build `isPro` starts as a placeholder `false` and becomes the
     * cached `true` when an asynchronous read lands. If first composition saw the placeholder, the
     * cache arriving was announced as a purchase to someone who bought Pro weeks ago.
     */
    @Test
    fun `the cache catching up is not announced as a purchase`() {
        // First composition: the placeholder, before the cache has been read.
        val placeholder = observeProTier(seen = null, isPro = false, loaded = false, message = message)
        assertNull(placeholder.say)
        // The cache lands: the paying user's real tier, seen for the first time.
        val cached = observeProTier(seen = placeholder.seen, isPro = true, loaded = true, message = message)
        assertNull(cached.say, "a cold start of a paying user was told Pro had just been unlocked")
    }

    /** The control: a real purchase after the tier has loaded is still announced, once. */
    @Test
    fun `a purchase after the tier has loaded is still announced`() {
        val free = observeProTier(seen = null, isPro = false, loaded = true, message = message)
        assertNull(free.say)
        val bought = observeProTier(seen = free.seen, isPro = true, loaded = true, message = message)
        assertEquals(message, bought.say)
        assertNull(observeProTier(seen = bought.seen, isPro = true, loaded = true, message = message).say)
    }

    /**
     * Tier E: purchase copy may not describe the paid tier as granting anything it does not.
     *
     * Cards and identities stopped being Pro-only on 2026-09-03 — `ProTypeQuota.FREE_LIMIT` is 1,
     * not 0 — so a purchaser who already held one of each was being told Pro had just "unlocked"
     * two things they already had. What Pro removes is the cap, exactly as it removes
     * `AttachmentQuota.FREE_LIMIT` for files, and the files half of this same sentence was
     * corrected on that reasoning on 2026-08-30 while the other two nouns were not.
     *
     * Asserted positionally rather than by banned word: naming the categories is fine and the
     * notice has to, so what is pinned is that the *cap* language comes first — "no cap on the
     * cards" states what was bought, "cards, identities, and no cap on files" does not.
     */
    @Test
    fun `the announcement names the caps Pro removes, not categories it grants`() {
        // language directory to (cap phrase, the word for cards) — the cap phrase must precede it.
        val expected = mapOf(
            "values" to ("no cap" to "cards"),
            "values-fr" to ("aucune limite" to "cartes"),
            "values-de" to ("keine Obergrenze" to "Karten"),
            "values-es" to ("sin límite" to "tarjetas"),
            "values-pt" to ("sem limite" to "cartões"),
        )
        expected.forEach { (dir, terms) ->
            val (cap, cards) = terms
            val xml = File("src/main/res/$dir/strings.xml")
            assertTrue(xml.exists(), "missing ${xml.path}")
            val value = Regex("""<string name="app_pro_unlock_announcement">(.*?)</string>""")
                .find(xml.readText())
                ?.groupValues
                ?.get(1)
            assertTrue(value != null, "$dir has no app_pro_unlock_announcement")
            val capAt = value.indexOf(cap)
            val cardsAt = value.indexOf(cards)
            assertTrue(capAt >= 0, "$dir does not say what Pro removes (\"$cap\"): $value")
            assertTrue(cardsAt >= 0, "$dir no longer names the card category (\"$cards\"): $value")
            assertTrue(
                capAt < cardsAt,
                "$dir presents cards/identities as things Pro grants rather than caps it lifts; " +
                    "\"$cap\" must come before \"$cards\": $value",
            )
        }
    }
}

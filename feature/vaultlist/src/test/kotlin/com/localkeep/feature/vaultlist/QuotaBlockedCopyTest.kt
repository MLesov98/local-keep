package com.localkeep.feature.vaultlist

import com.localkeep.core.vault.AttachmentQuota
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The quota refusal has to state the vault's real numbers, in every language.
 *
 * Both numbers in the title used to be the same format argument — `%1$d of %1$d used` — and the
 * composable passed `FREE_LIMIT` twice, so "5 of 5 used" was a literal wearing a count's clothes.
 * The body went further and named the remedy: "Remove one to make room". That is true at exactly
 * the cap and false above it, and above it is a supported state — import is never charged against
 * the cap (see [AttachmentQuota]'s invariant), so a vault can legitimately hold thirty.
 *
 * Someone in that state removes a photo, empties the trash, taps add, and is handed a sentence
 * identical to the last one down to the byte. The app knew the real number and both surfaces
 * withheld it. That was reported from a device on 2026-08-25 with five files stored, one of which
 * was inside a folder and therefore not on the vault list at all — so even the count the user
 * could see disagreed with the one being enforced.
 *
 * Pure JVM: reads the catalogue rather than resolving ids, like `QuotaMessageTest` next door.
 */
class QuotaBlockedCopyTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private fun items(dir: String, name: String): List<String> {
        val xml = File("src/main/res/$dir/strings.xml")
            .takeIf { it.exists() }
            ?.readText()
            ?: fail("$dir/strings.xml missing")
        val block = Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?: fail("$name missing from $dir/strings.xml")
        return Regex("""<item quantity="[a-z]+">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block).map { it.groupValues[1] }.toList()
            .ifEmpty { fail("$name has no items in $dir/strings.xml") }
    }

    @Test
    fun theTitleCountsWhatIsUsedRatherThanNamingTheCapTwice() {
        languages.forEach { dir ->
            items(dir, "vaultlist_quota_blocked_title").forEach { text ->
                assertTrue(
                    "%1\$d" in text && "%2\$d" in text,
                    "$dir must place the used count and the cap as two arguments, not one twice: \"$text\"",
                )
            }
        }
    }

    @Test
    fun theBodyNamesHowManyMustGoAndHowManyThereAre() {
        languages.forEach { dir ->
            items(dir, "vaultlist_quota_blocked_body_base").forEach { text ->
                listOf("%1\$d", "%2\$d", "%3\$d").forEach { arg ->
                    assertTrue(
                        arg in text,
                        "$dir must carry $arg (remove-count, cap, in use): \"$text\"",
                    )
                }
            }
        }
    }

    /**
     * The cap's noun is fixed-plural, so the cap may not fall to one.
     *
     * The body's plural quantity is how many have to go — the number the reader acts on — which
     * leaves "%2$d files and photos" agreeing with nothing. Above one that is correct in all five
     * languages and at one it is "1 files and photos", the exact untruth Tier F exists to stop.
     * Cheaper to fail here than to discover it in a catalogue.
     */
    @Test
    fun theCapIsPluralSoTheSentenceAroundItCanBe() {
        assertTrue(
            AttachmentQuota.FREE_LIMIT > 1,
            "a cap of ${AttachmentQuota.FREE_LIMIT} needs the body re-keyed on the cap, not on the " +
                "remove-count — see the comment above vaultlist_quota_blocked_body_base",
        )
    }

    /**
     * The number the sentence asks for, against the number the guard enforces.
     *
     * One at exactly the cap — the ordinary case, and the only one the old copy described — and the
     * real surplus above it. Never zero: this is only ever read by a refusal, and a refusal asking
     * for no deletions describes no way forward.
     */
    @Test
    fun removeCountMatchesWhatTheGuardWouldStopRefusing() {
        val limit = AttachmentQuota.FREE_LIMIT
        assertEquals(1, AttachmentQuota.mustRemove(limit))
        assertEquals(3, AttachmentQuota.mustRemove(limit + 2))
        assertEquals(1, AttachmentQuota.mustRemove(0))

        // The contract that makes the sentence true: remove that many and one more fits.
        listOf(limit, limit + 1, limit + 7).forEach { used ->
            val after = used - AttachmentQuota.mustRemove(used)
            assertTrue(
                !AttachmentQuota.exceedsFreeLimit(after, isPro = false),
                "removing ${AttachmentQuota.mustRemove(used)} of $used must make room, left $after",
            )
        }
    }
}

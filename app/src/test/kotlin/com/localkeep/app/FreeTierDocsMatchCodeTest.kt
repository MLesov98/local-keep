package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The published free-tier caps must equal the constants the app actually refuses on.
 *
 * Written after `5 attachments` was found in both public-facing documents — `FEATURES.md`
 * and `COMPARISON.md`, which are the two files a prospective buyer or a reviewer reads — four days
 * after `AttachmentQuota.FREE_LIMIT` moved to 7, and found `FEATURES.md` still describing Cards and
 * Identities as "gated on Pro outright" after `ProTypeQuota` gave a free vault one of each. Both
 * numbers had drifted for the same reason the Argon2id figure did (see
 * [KdfMemoryDocsMatchCodeTest]): nothing tied the prose to the code.
 *
 * The prose is what moves. If this test fails, correct the document — the constants are a product
 * decision, and a document is not a reason to make one.
 *
 * The constants are read out of the source text rather than imported, so the check holds without
 * `:app` taking a dependency on `:core:vault` for it.
 */
class FreeTierDocsMatchCodeTest {

    private val attachmentLimit: Int =
        constantIn("core/vault/src/main/kotlin/com/localkeep/core/vault/AttachmentQuota.kt")

    private val proTypeLimit: Int =
        constantIn("core/vault/src/main/kotlin/com/localkeep/core/vault/ProTypeQuota.kt")

    @Test
    fun `every published free-tier attachment figure matches AttachmentQuota`() {
        assertClaims(ATTACHMENT_SITES, attachmentLimit, "AttachmentQuota.FREE_LIMIT")
    }

    @Test
    fun `every published free-tier card and identity figure matches ProTypeQuota`() {
        assertClaims(PRO_TYPE_SITES, proTypeLimit, "ProTypeQuota.FREE_LIMIT")
    }

    /**
     * The one claim written as an English word rather than a numeral, checked separately because a
     * regex over digits cannot see it — and it is the sentence that names the constant out loud,
     * so it is the one a reader is most likely to trust.
     */
    @Test
    fun `the spelled-out card and identity allowance matches ProTypeQuota`() {
        val expected = NUMBER_WORDS[proTypeLimit]
            ?: fail(
                "ProTypeQuota.FREE_LIMIT is $proTypeLimit, which NUMBER_WORDS does not spell. " +
                    "Extend the table rather than deleting the sentence it guards.",
            )
        val line = File(REPO, "FEATURES.md").readLines()
            .firstOrNull { SPELLED_ALLOWANCE.containsMatchIn(it) }
            ?: fail(
                "FEATURES.md no longer carries the '<word> Card / <word> Identity allowance' " +
                    "sentence. It named ProTypeQuota.FREE_LIMIT in prose; restore it or this " +
                    "guard is checking nothing.",
            )
        val match = SPELLED_ALLOWANCE.find(line)!!
        val (card, identity) = match.destructured
        if (!card.equals(expected, ignoreCase = true) || !identity.equals(expected, ignoreCase = true)) {
            fail(
                "ProTypeQuota.FREE_LIMIT is $proTypeLimit (\"$expected\"), but FEATURES.md says " +
                    "\"$card Card / $identity Identity\". Fix the prose, not the constant:\n" +
                    line.trim(),
            )
        }
    }

    private fun assertClaims(sites: List<Site>, expected: Int, constant: String) {
        val offenders = mutableListOf<String>()
        for (site in sites) {
            val file = File(REPO, site.path)
            assertTrue(file.isFile, "${site.path} not found")
            val lines = file.readLines()
            var hits = 0
            lines.forEachIndexed { i, line ->
                site.claim.findAll(line).forEach { m ->
                    hits++
                    val stated = m.groupValues[1].toInt()
                    if (stated != expected) {
                        offenders += "${site.path}:${i + 1} claims $stated — ${line.trim()}"
                    }
                }
            }
            // Without this, a document that dropped the sentence entirely — or reworded it past the
            // marker — would turn this test green by examining nothing, which is the failure mode
            // KdfMemoryDocsMatchCodeTest shipped in its first draft.
            assertTrue(
                hits > 0,
                "${site.path}: the marker /${site.claim}/ matched nothing, so this guard is " +
                    "checking less than it claims to. Fix the marker or restore the claim — do " +
                    "not delete the entry.",
            )
        }
        if (offenders.isNotEmpty()) {
            fail(
                "The free tier is $expected per $constant, but these lines say otherwise. " +
                    "Fix the prose, not the constant:\n" + offenders.joinToString("\n"),
            )
        }
    }

    private fun constantIn(path: String): Int {
        val src = File(REPO, path)
        assertTrue(src.isFile, "${src.path} not found")
        return Regex("""const val FREE_LIMIT = (\d+)""")
            .find(src.readText())
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: fail("FREE_LIMIT not found in $path")
    }

    /** A public document and the pattern identifying a line in it that states a free-tier cap. */
    private data class Site(val path: String, val claim: Regex)

    private companion object {
        val ATTACHMENT_SITES = listOf(
            Site("FEATURES.md", Regex("""\(free tier: \*\*(\d+)\*\*\)""")),
            Site("FEATURES.md", Regex("""\*\*(\d+)\*\* is `AttachmentQuota\.FREE_LIMIT`""")),
            Site("COMPARISON.md", Regex("""; (\d+) attachments""")),
            // COMPARISON.md states the same three caps twice — once in the Price table and again
            // in the "What you can store" grid, 28 lines apart. Only the Price table was pinned
            // here, so on 2026-09-07 the grid still read `$ (5 free)` while the row above it had
            // been corrected to 7, and this test was green throughout. Anchored on the trailing
            // `✓ + hidden` cell, which only the LocalKeep row carries.
            Site("COMPARISON.md", Regex("""\$ \((\d+) free\) \| ✓ \+ hidden""")),
        )

        val PRO_TYPE_SITES = listOf(
            Site("COMPARISON.md", Regex("""(\d+) card,""")),
            Site("COMPARISON.md", Regex("""(\d+) identity""")),
            // The same grid row's Cards and Identity cells. Each is anchored by its neighbours
            // rather than by itself, because the two cells are textually identical.
            Site("COMPARISON.md", Regex("""✓ \| ✓ \| \$ \((\d+) free\)""")),
            Site("COMPARISON.md", Regex("""\$ \(\d+ free\) \| \$ \((\d+) free\) \| ✓""")),
        )

        val SPELLED_ALLOWANCE = Regex("""\*\*(\w+) Card / (\w+) Identity\*\* allowance""")

        val NUMBER_WORDS = mapOf(
            1 to "one", 2 to "two", 3 to "three", 4 to "four", 5 to "five",
            6 to "six", 7 to "seven", 8 to "eight", 9 to "nine", 10 to "ten",
        )
    }
}

private val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")

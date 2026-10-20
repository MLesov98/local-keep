package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `SECURITY-COPY: tier` marker in the repo must be one the verification tooling can actually
 * see.
 *
 * ### The defect this exists for
 *
 * `scripts/i18n/verify_pack.py` builds the back-translation and 3-vote packs that
 * the security-copy review protocol calls the substitute for native review. It finds tagged
 * keys with [EXTRACTOR] below: the marker must be a comment containing nothing else, sitting
 * immediately above the element. Put the rationale in the same comment and the key is invisible —
 * so it never enters a pack, never gets back-translated, never gets three votes, and the marker
 * reads as protection while providing none.
 *
 * That is not hypothetical. When this test was written, **122 of the 286 tagged keys in the repo
 * were invisible** — 43%. Whole modules were dark: `feature/onboarding` had 19 tagged keys and 0
 * extractable, `feature/unlock` 10 invisible against 3 visible, and `unlock_subtitle` — the
 * standing Tier G example in the protocol itself — was among them.
 *
 * ### Why nobody noticed
 *
 * The two consumers of these markers disagree, and the lenient one is the only one with a build
 * gate behind it. [TierGClaimsTest]'s scanner treats a run of comments before an element as tagging
 * it, so **Tier G was covered whatever the comment shape**. Tiers A to F are read only by the
 * Python extractor, which has no test at all. The stricter parser was the unguarded one.
 *
 * ### The allowlist
 *
 * Following `config/i18n-allowlist.txt` and `HiddenChokepointTest`: known debt is committed, named,
 * and **only ever shrinks**. It is not permission — every entry is a security string that is not
 * being verified. Fixing one is mechanical: move the prose into its own comment above, and leave a
 * second comment holding only the marker. Do not add entries; a new tag that cannot be parsed is a
 * new tag that does nothing.
 */
class SecurityCopyTagsAreParsableTest {

    /**
     * Copied verbatim from `scripts/i18n/verify_pack.py` (the `TIER` pattern, currently line 43),
     * translated from Python to Kotlin regex syntax without changing what it matches.
     *
     * Duplicated rather than shared because the extractor is Python and this is a JVM test, and
     * there is no build step that could keep one definition in both languages. **The two must stay
     * in step**: if that pattern is ever loosened or tightened, change it here in the same commit,
     * or this test starts guarding a rule the tool no longer applies.
     */
    private val extractor =
        Regex("""<!--\s*SECURITY-COPY:\s*tier\s*([A-G])\s*-->\s*<(?:string|plurals)\s+name="([^"]+)"""")

    /**
     * How a marker is found when we do not care where it sits — the same run-of-comments rule
     * [TierGClaimsTest] uses. The gap between this and [extractor] is the whole defect.
     */
    private val lenient =
        Regex("""<!--(.*?)-->|<string name="([^"]+)"|<plurals name="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)

    private val marker = Regex("""SECURITY-COPY:\s*tier\s*[A-G]""")

    private val repoRoot: File
        get() {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
            return requireNotNull(dir) { "could not locate the repository root" }
        }

    /** Base catalogues only: `verify_pack.py:122` reads tiers from `values/`, never from a locale. */
    private fun baseCatalogues(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") && !it.name.endsWith("-out") }
            .filter { it.path.endsWith("/src/main/res/values/strings.xml") }
            .toList()

    private fun moduleOf(root: File, f: File): String =
        f.relativeTo(root).invariantSeparatorsPath.removeSuffix("/src/main/res/values/strings.xml")

    /**
     * Keys carrying a marker anywhere in the comment run above them, whether or not it parses.
     */
    private fun taggedLeniently(text: String): Set<String> {
        val out = mutableSetOf<String>()
        var tagged = false
        lenient.findAll(text).forEach { m ->
            val comment = m.groupValues[1]
            val key = m.groupValues[2].ifEmpty { m.groupValues[3] }
            if (key.isEmpty()) {
                if (marker.containsMatchIn(comment)) tagged = true
            } else {
                if (tagged) out += key
                tagged = false
            }
        }
        return out
    }

    /**
     * Tags the strict extractor cannot see. **Empty, and it stays empty.**
     *
     * This held 112 entries from 2026-09-03 until 2026-09-10, every one a string whose marker was
     * written with its rationale inside the same comment — `<!-- SECURITY-COPY: tier A. Writes to
     * the vault. -->`. `verify_pack.py` requires the marker to be the whole comment with the tagged
     * element directly after it, so all 112 read as protected while entering no verification pack:
     * 183 keys visible against 285 tagged. They were split into a rationale comment above a bare
     * marker below, which changes nothing about which key carries which tier — the loose parser
     * already attributed a marker to the next element in a run of comments — and everything about
     * whether the tag does anything.
     *
     * A shrink-only allowlist that never shrank is how the debt survived a year. Now that it is
     * empty there is nowhere for a new dark marker to hide: the next one fails this test on the
     * commit that adds it, which is the only moment it is cheap to fix.
     */
    private val knownUnparsable: Set<String> = emptySet()

    @Test
    fun everyTierMarkerIsOneTheExtractorCanSee() {
        val root = repoRoot
        val catalogues = baseCatalogues(root)
        assertTrue(
            catalogues.size > 10,
            "found only ${catalogues.size} base catalogues — the walk is wrong, not the code",
        )

        val invisible = catalogues.flatMap { file ->
            val text = file.readText()
            val module = moduleOf(root, file)
            val visible = extractor.findAll(text).map { it.groupValues[2] }.toSet()
            (taggedLeniently(text) - visible).map { "$module:$it" }
        }.sorted()

        val unexpected = invisible - knownUnparsable
        assertTrue(
            unexpected.isEmpty(),
            "these keys carry a SECURITY-COPY tier marker that verify_pack.py cannot parse, so " +
                "they never enter a verification pack: $unexpected\n" +
                "Put the rationale in its own comment, then a second comment holding only the " +
                "marker, directly above the element.",
        )
    }

    @Test
    fun theAllowlistOnlyEverShrinks() {
        // An entry that has been fixed must be deleted, not left behind. A stale allowlist is how
        // a list stops being read: it grows a tail of entries nobody can tell from live debt.
        val root = repoRoot
        val invisible = baseCatalogues(root).flatMap { file ->
            val text = file.readText()
            val module = moduleOf(root, file)
            val visible = extractor.findAll(text).map { it.groupValues[2] }.toSet()
            (taggedLeniently(text) - visible).map { "$module:$it" }
        }.toSet()

        assertEquals(
            emptySet(),
            knownUnparsable - invisible,
            "these allowlist entries are already fixed — delete them from knownUnparsable",
        )
    }

    @Test
    fun theQuotaAndAllowanceCopyIsNotOnTheAllowlist() {
        // The seven keys this test was written alongside. Named explicitly so that re-breaking one
        // fails with a sentence about the free-tier copy rather than a diff of a 105-line set.
        val quotaKeys = listOf(
            "vaultlist_quota_blocked_title",
            "vaultlist_quota_blocked_body_base",
            "vaultlist_quota_blocked_suffix_purchase",
            "vaultlist_card_quota_blocked_title",
            "vaultlist_identity_quota_blocked_title",
            "vaultlist_card_quota_blocked_body_base",
            "vaultlist_identity_quota_blocked_body_base",
        ).map { "feature/vaultlist:$it" }

        val text = File(repoRoot, "feature/vaultlist/src/main/res/values/strings.xml").readText()
        val visible = extractor.findAll(text).map { "feature/vaultlist:${it.groupValues[2]}" }.toSet()
        quotaKeys.forEach {
            assertTrue(it in visible, "$it is no longer extractable by verify_pack.py")
        }
    }

    /**
     * The files and identity-photo quota refusals each split into a count sentence (Tier F) and two
     * purchase clauses (Tier E) when `foss` stopped being told to "unlock Pro for unlimited". The
     * clause is the commercial claim, so it must be a key the verification packs can see, in the
     * tier the claim belongs to; the count sentence keeps its own.
     */
    @Test
    fun theSplitQuotaRefusalsAreVisibleToTheVerificationPacksInTheirTiers() {
        val expected = mapOf(
            "feature/files/src/main/res/values/strings.xml" to listOf(
                "F" to "files_error_quota_exceeded",
                "E" to "files_error_quota_exceeded_suffix_purchase",
                "E" to "files_error_quota_exceeded_suffix_no_purchase",
            ),
            "feature/itemedit/src/main/res/values/strings.xml" to listOf(
                "F" to "itemedit_error_identity_photo_quota",
                "E" to "itemedit_error_identity_photo_quota_suffix_purchase",
                "E" to "itemedit_error_identity_photo_quota_suffix_no_purchase",
            ),
        )
        expected.forEach { (path, tiers) ->
            val seen = extractor.findAll(File(repoRoot, path).readText())
                .map { it.groupValues[1] to it.groupValues[2] }.toSet()
            tiers.forEach {
                assertTrue(it in seen, "$path: ${it.second} is not extractable as tier ${it.first}")
            }
        }
    }

    /**
     * The pre-unlock restore confirmation is the one dialog in the app that overwrites the vault,
     * and the security-copy protocol names "replace vault on restore" as a tier A example by name.
     *
     * The other tests here cannot see this class of defect. They compare markers the loose parser
     * finds against markers the strict one finds, so they catch a marker written in the wrong
     * *shape*. A string with **no marker at all** is invisible to both, and that is what happened:
     * `unlock_restore_confirm_title` sat directly under the marker belonging to the string above it,
     * read as covered on a skim, and entered no pack. `unlock_restore_error_failed` was the same,
     * and it makes a claim about what a failed write left behind.
     *
     * So this test names the keys rather than deriving them: the dialog's title, body and button,
     * and the two failure sentences that answer "what happened to my vault?" after it is confirmed.
     */
    @Test
    fun theVaultReplacingRestoreCopyIsTaggedAndExtractable() {
        val restoreKeys = listOf(
            "unlock_restore_confirm_title",
            "unlock_restore_confirm_body",
            "unlock_restore_confirm_button",
            "unlock_restore_error_not_kept",
            "unlock_restore_error_failed",
        )

        val text = File(repoRoot, "feature/unlock/src/main/res/values/strings.xml").readText()
        val tiers = extractor.findAll(text).associate { it.groupValues[2] to it.groupValues[1] }
        restoreKeys.forEach {
            assertEquals(
                "A",
                tiers[it],
                "$it must carry its own `SECURITY-COPY: tier A` comment directly above it — it is " +
                    "part of the dialog that replaces the vault, or the sentence shown when that " +
                    "replacement fails. Found: ${tiers[it] ?: "no extractable marker"}",
            )
        }
    }
}

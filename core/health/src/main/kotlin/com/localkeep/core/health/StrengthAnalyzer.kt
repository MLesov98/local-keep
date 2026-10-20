package com.localkeep.core.health

import com.nulabinc.zxcvbn.Zxcvbn

/**
 * Offline password-strength scoring via zxcvbn4j (com.nulab-inc:zxcvbn).
 *
 * Score bands (zxcvbn convention, 0-indexed):
 *   0 — too guessable (guessed instantly)
 *   1 — very guessable (guessed in seconds)
 *   2 — somewhat guessable (guessed in minutes-to-hours)
 *   3 — safely unguessable (guessed in days)
 *   4 — very unguessable (guessed in centuries)
 *
 * Never logs the password it scores.
 */
object StrengthAnalyzer {

    /** Scores <= this are bucketed into the dashboard's "weak" category. */
    const val WEAK_SCORE_CEILING = 1

    /**
     * The floor a master-password *change* (Settings) or an export passphrase (Backup) must clear.
     * 3 is zxcvbn's "safely unguessable". Shared rather than inlined at each call site so those
     * gates and the passphrase LocalKeep itself suggests are pinned to the same number — a
     * generator change that dropped below it would otherwise produce a suggestion those gates
     * reject.
     *
     * NOT read by first-run onboarding any more: the create-password step's floor is advisory
     * (deliberate, commit 5e32815b, 2026-07-26) and lives as its own constant,
     * `OnboardingUiState.ADVISED_STRENGTH_SCORE` — currently the same value, but not pinned to this
     * one. That asymmetry (a password can be *created* below this floor, but not *set* later
     * through Settings) is tracked as a follow-up rather than resolved here.
     */
    const val MIN_MASTER_PASSWORD_SCORE = 3

    /**
     * The most characters ever handed to zxcvbn.
     *
     * zxcvbn scores every substring of what it is given against a dozen dictionaries, so its cost
     * grows roughly with the cube of the length: measured on a desktop JVM, 128 characters of
     * ordinary text take ~14 ms, 800 take ~1.8 s and 2,800 take ~37 s — and an Android device is far
     * slower again. That is not a theoretical bound. A vault holding one imported item whose
     * *password* field is a long blob — a Firefox `accounts-data` CSV column is an 837-character
     * JSON document, a Keeper export puts whole SSH private keys in fields — stalled the health scan
     * indefinitely: 77 s of no progress and still climbing, at 86% CPU with the collector freeing
     * ~45 MB of garbage a second, the scan's progress ring frozen at the item it was measuring. The
     * captured stack was `DictionaryMatcher.findMatchesInDictionary` under [score], 44 s of user time
     * in one call.
     *
     * The ~14 ms is for ordinary text. Input dense in the characters zxcvbn reads as l33t
     * substitutions (`4@8({[<3691!|17$5+%2`) is the expensive shape: re-measured on a desktop JVM
     * 2026-10-02, 64 such characters take ~0.3 s and 128 take ~2 s. That worst case is bounded and
     * runs off the main thread, so the cap stays at 128 rather than dropping to 64: a lower one
     * would change the verdict a master-password or export-passphrase gate gives a 65–128 character
     * input, to save time only a deliberately l33t-dense password ever costs.
     *
     * Measuring a bounded prefix keeps the verdict honest in the direction that matters: a password
     * is never weaker than its own prefix, so a prefix that scores above [WEAK_SCORE_CEILING]
     * guarantees the whole password does too. The only error it can make is calling a password weak
     * whose first 128 characters are weak and whose tail is not — which flags it for the user to
     * look at rather than hiding it, and needs a password longer than anything this app generates
     * (the generator's own ceiling is 64) to happen at all.
     */
    const val MEASURED_LENGTH_CAP = 128

    private val zxcvbn = Zxcvbn()

    /**
     * The prefix actually measured — see [MEASURED_LENGTH_CAP].
     *
     * Drops a trailing lone high surrogate rather than handing the matchers half a code point;
     * `take` counts UTF-16 units and an emoji straddling the cut would otherwise be split.
     */
    private fun measurable(password: String): String {
        if (password.length <= MEASURED_LENGTH_CAP) return password
        val cut = password.take(MEASURED_LENGTH_CAP)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    /** 0–4 strength score. Empty passwords score 0. */
    fun score(password: String): Int {
        if (password.isEmpty()) return 0
        return zxcvbn.measure(measurable(password)).score
    }

    /** Human-readable warning + suggestions, joined into one string ("" if zxcvbn has nothing to add). */
    fun feedback(password: String): String {
        if (password.isEmpty()) return ""
        val feedback = zxcvbn.measure(measurable(password)).feedback
        val parts = buildList {
            val warning = feedback.warning
            if (!warning.isNullOrBlank()) add(warning)
            addAll(feedback.suggestions)
        }
        return parts.joinToString(" ")
    }

    /** True if [password] falls in the "weak" bucket (score <= [WEAK_SCORE_CEILING]). */
    fun isWeak(password: String): Boolean = password.isNotEmpty() && score(password) <= WEAK_SCORE_CEILING
}

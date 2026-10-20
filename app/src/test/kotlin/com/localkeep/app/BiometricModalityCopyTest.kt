package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No user-facing string names a biometric modality.
 *
 * The app cannot know which one is enrolled. Every gate authorizes on `BIOMETRIC_STRONG` and
 * nothing else — `KeystoreManager.isStrongBiometricAvailable`, and `setAllowedAuthenticators`
 * in `BiometricUnlock` and `HiddenItemsViewModel` — and that API answers only whether *some*
 * Class-3 authenticator exists. Which one is not exposed, before or after the prompt.
 *
 * So both of the obvious wordings are false on ordinary hardware:
 *
 * - "fingerprint" is false on a phone whose only strong biometric is a face.
 * - "fingerprint or face" is false far more often. On the very common phone with a Class-3 reader
 *   and a Class-2 camera face, `canAuthenticate` succeeds *because of the reader*, so the button
 *   appears promising face, and the prompt it opens cannot offer face at all.
 *
 * Both of those wordings shipped in this repo, in that order, and the second was withdrawn on
 * 2026-08-19 after the reasoning above was checked against the source rather than assumed. This
 * test is what stops the third round: "fingerprint" is a natural word to reach for, it reads
 * warmer than "biometrics", and nothing else in the build would notice it coming back — a false
 * claim about the reader's own device is not a lint error and not a missing translation.
 *
 * Scope is deliberately every language, because the English is the half most likely to be reviewed.
 * `de` said *Fingerabdruck* in a string whose English had already been fixed once.
 *
 * The project glossary's rule is "Never name a biometric modality" (decided 2026-08-19).
 */
class BiometricModalityCopyTest {

    /**
     * The one string that may say "fingerprint", and the only reason a string ever may.
     *
     * `settings_empty_body` is the no-results hint on Settings search, and it lists example words
     * to *type* — "vibration", "fingerprint". That is a claim about the search index, not about the
     * device, and it stays true: `SettingsScreen`'s biometric row carries
     * `keywords = "fingerprint face touch id unlock convenience"`, so typing either modality still
     * finds the setting even though no visible string names one.
     */
    private val allowed = setOf("settings_empty_body")

    /**
     * Word-boundary matched, per language, so that "face" does not fire on *interface* and
     * *facial* does not hide behind *superficial*. The German and Portuguese entries are prefix
     * patterns on purpose: `Fingerabdruckversuche` and `impressões digitais` are the forms that
     * actually appeared, and a bare-lemma list would have missed both.
     */
    private val banned = mapOf(
        "values" to modality("""fingerprints?|face|facial|iris|touch\s?id"""),
        "values-fr" to modality("""empreintes?|visages?|faciales?"""),
        "values-de" to modality("""Fingerabdr\w*|Gesicht\w*"""),
        "values-es" to modality("""huellas?|rostros?|faciales?"""),
        "values-pt" to modality("""impress\w+\s+digit\w+|rostos?|faciais?|facial"""),
    )

    /**
     * `(?U)` is not decoration. Java's `\w` and `\b` are ASCII-only unless UNICODE_CHARACTER_CLASS
     * is on, so without it the Portuguese pattern cannot cross the "ã" and `impress\w+` never
     * matches *impressão digital* — the exact phrase it exists to catch. Measured, not guessed:
     * the first red run of this test flagged 58 strings and reported Portuguese only via *rosto*,
     * silently passing every *impressão digital* in the catalogue. A check that under-reports is
     * indistinguishable from a clean tree, which is the whole failure this file guards against.
     */
    private fun modality(alternatives: String) =
        Regex("""(?U)\b($alternatives)\b""", RegexOption.IGNORE_CASE)

    private val entry = Regex("""<(string|item)\b[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val nameOf = Regex("""name="([^"]+)"""")

    @Test
    fun noUserFacingStringNamesABiometricModality() {
        val catalogues = repoRoot().walkTopDown()
            .filter { it.name == "strings.xml" && it.parentFile.parentFile.name == "res" }
            .filterNot { it.path.contains("/build/") }
            .toList()

        // A scan that finds no files to scan passes for the wrong reason — the failure mode this
        // whole file exists to prevent, one level up.
        assertTrue(catalogues.size >= 20, "expected the module catalogues, found ${catalogues.size}")

        val failures = catalogues.flatMap { file ->
            val pattern = banned[file.parentFile.name] ?: return@flatMap emptyList()
            entry.findAll(file.readText()).mapNotNull { match ->
                // An <item> inside <plurals> carries no name of its own; walk back to the plurals
                // tag so a banned word in one quantity is still attributed to a key.
                val key = nameOf.find(match.value)?.groupValues?.get(1)
                    ?: nameOf.findAll(file.readText().substring(0, match.range.first))
                        .lastOrNull()?.groupValues?.get(1)
                    ?: "?"
                if (key in allowed) return@mapNotNull null
                pattern.find(match.groupValues[2])?.let { hit ->
                    "${file.parentFile.name}/$key names a modality (\"${hit.value}\"): " +
                        match.groupValues[2].trim().take(90)
                }
            }.toList()
        }

        assertTrue(
            failures.isEmpty(),
            "A user-facing string names a biometric modality the app cannot know is enrolled.\n" +
                "Say \"biometrics\", or \"biometric unlock\" where it names the feature.\n" +
                failures.joinToString("\n"),
        )
    }
}

/**
 * The repo root, found by walking up to the settings file rather than assuming a working directory:
 * Gradle runs this test from the module directory, an IDE may not.
 *
 * Deliberately not shared with [TierGClaimsTest]'s identical private helper. That file's is
 * file-private, and hoisting it into something both import would make two independent source
 * scanners share a failure mode — if the root ever resolves wrongly, both go quietly green at once.
 */
private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")

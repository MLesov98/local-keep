package com.localkeep.feature.itemedit

import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every reason on the dimmed phrase Save button must name the **control** before the reason.
 *
 * `Modifier.labelled` sets `contentDescription` on a merged node, which *replaces* the accessible
 * name rather than adding to it. So a reason sentence that opens with its explanation leaves the
 * button with no "Save" in its name at all — a disabled control a screen-reader user cannot
 * identify, which is the "clickable with no named descendant" defect, not a fix for it. F49 added
 * these reasons to solve identity-without-reason; the opposite trade is not an improvement.
 *
 * The second assertion pins the other half: the vault-kit sentence must keep the wording of
 * `itemedit_phrase_banner_vaultkit_title`, the banner a sighted user reads at the same moment, so
 * the two cannot be edited apart. It is deliberately **not** built by concatenating the two strings
 * at runtime — the clauses join with different word order and punctuation in each language, which is
 * what the positional-resource rule exists to prevent — so a test is the only thing holding them
 * together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhraseSaveDisabledReasonNamesTheButtonTest {

    private val locales = listOf("en", "fr", "de", "es", "pt")

    private fun resources(tag: String): Resources {
        val base = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }
        return base.createConfigurationContext(configuration).resources
    }

    @Test
    fun `both blocked reasons open with the Save verb, in every locale`() {
        for (tag in locales) {
            val res = resources(tag)
            val generic = res.getString(R.string.itemedit_phrase_save_disabled_a11y)
            val vaultKit = res.getString(R.string.itemedit_phrase_save_disabled_vaultkit_a11y)

            assertTrue(
                generic.contains(','),
                "$tag: the generic reason must name the button and then its reason, comma-separated",
            )
            val verb = generic.substringBefore(',')
            assertTrue(
                vaultKit.startsWith("$verb,"),
                "$tag: the vault-kit reason must open with the same control name as the generic one" +
                    " — expected it to start with \"$verb,\" but it was \"$vaultKit\"",
            )
        }
    }

    @Test
    fun `the vault-kit reason keeps the banner's own wording, in every locale`() {
        for (tag in locales) {
            val res = resources(tag)
            val banner = res.getString(R.string.itemedit_phrase_banner_vaultkit_title)
            val vaultKit = res.getString(R.string.itemedit_phrase_save_disabled_vaultkit_a11y)

            // From the banner's second word on: only its first word changes case when the clause
            // moves behind a comma, and that differs by language (German keeps its nouns capital).
            val clause = banner.substringAfter(' ')
            assertTrue(
                vaultKit.contains(clause),
                "$tag: the spoken reason and the banner have drifted apart — \"$vaultKit\" does not" +
                    " carry \"$clause\"",
            )
        }
    }

    /**
     * The control, and it must stay green: the two reasons stay two sentences. A change that folded
     * the vault-kit clause into the generic string — the shape this key exists to avoid — would put
     * the kit wording on a phrase that is merely incomplete, and fail here.
     */
    @Test
    fun `the generic reason says nothing about the Recovery Kit, in every locale`() {
        for (tag in locales) {
            val res = resources(tag)
            val generic = res.getString(R.string.itemedit_phrase_save_disabled_a11y)
            val clause = res.getString(R.string.itemedit_phrase_banner_vaultkit_title)
                .substringAfter(' ')
            assertFalse(
                generic.contains(clause),
                "$tag: the generic reason must not claim anything about this vault's Recovery Kit",
            )
        }
    }
}

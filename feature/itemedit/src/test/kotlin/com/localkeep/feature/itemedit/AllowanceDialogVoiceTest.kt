package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The editor's allowance refusal must speak with the vault list's voice.
 *
 * There are two ways to be told the free tier is full. The vault list's `QuotaBlockedDialog` fires
 * when a metered type is selected from the add sheet; this one is the backstop at save, for anything
 * that gets past it — a deep link, a stale route. Until 2026-09-04 they did not look or sound like
 * the same event: the vault list was amber with the refused type's own glyph and led with the free
 * remedy, while this one was brass with a crown and offered only the purchase. Whichever a user met
 * first taught them a different thing about the same cap.
 *
 * Aligned rather than shared, deliberately: `QuotaBlockedDialog` lives in `:feature:vaultlist`,
 * which this module does not depend on, so single-sourcing it means moving it and roughly sixty
 * catalogue entries of already-verified Tier F copy into `:core:ui`. Worth doing on its own; not
 * worth doing inside a fix to the backstop. The cap itself was never duplicated —
 * `ProTypeQuota.FREE_LIMIT` is what both sentences interpolate — so what is pinned here is a voice.
 */
class AllowanceDialogVoiceTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private val bodies = listOf(
        "itemedit_edit_pro_dialog_body_purchasable",
        "itemedit_edit_pro_dialog_body_purchasable_no_price",
        "itemedit_edit_pro_dialog_body_not_purchasable",
    )

    /** The free remedy, per language, as it appears in the shipped string. */
    private val freeRemedy = mapOf(
        "values" to "Remove one",
        "values-fr" to "Supprimez-en une",
        "values-de" to "Entfernen Sie eine",
        "values-es" to "Elimine una",
        // Neither gender serves a list of *cartão* (m) and *identidade* (f), so Portuguese names
        // the pair instead of picking one. Rejected in adversarial review as "Exclua um", which
        // reads as "delete the card" to someone being refused an identity.
        "values-pt" to "Exclua um dos dois",
    )

    private fun xml(dir: String): String =
        File("src/main/res/$dir/strings.xml").takeIf { it.exists() }?.readText()
            ?: fail("$dir/strings.xml missing")

    private fun value(dir: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml(dir))?.groupValues?.get(1)
            ?: fail("$key missing from $dir/strings.xml")

    @Test
    fun `every language offers the free remedy`() {
        languages.forEach { dir ->
            bodies.forEach { key ->
                assertTrue(
                    freeRemedy.getValue(dir) in value(dir, key),
                    "$dir/$key does not tell the user they can delete one instead of paying: " +
                        "\"${value(dir, key)}\"",
                )
            }
        }
    }

    /**
     * And offers it *first*. A remedy mentioned after the purchase reads as a consolation; the
     * vault list's dialog states the free way out before naming a price, and so does this one.
     *
     * Only the two purchasable variants can get this wrong — the third names no purchase at all.
     */
    @Test
    fun `the free remedy comes before the purchase`() {
        val proWord = mapOf(
            "values" to "Pro", "values-fr" to "Pro", "values-de" to "Pro",
            "values-es" to "Pro", "values-pt" to "Pro",
        )
        languages.forEach { dir ->
            bodies.take(2).forEach { key ->
                val text = value(dir, key)
                val remedyAt = text.indexOf(freeRemedy.getValue(dir))
                val proAt = text.indexOf("unlock", ignoreCase = true)
                    .takeIf { it >= 0 } ?: text.indexOf(proWord.getValue(dir))
                assertTrue(
                    remedyAt in 0 until proAt,
                    "$dir/$key names the purchase before the free remedy: \"$text\"",
                )
            }
        }
    }

    /**
     * The build that cannot sell anything must not describe a purchase as something to do here.
     * `foss` has no billing at all (spec §N2), so the third variant points at the Play build and
     * stops.
     */
    @Test
    fun `the unbuyable variant offers no purchase action`() {
        languages.forEach { dir ->
            val text = value(dir, "itemedit_edit_pro_dialog_body_not_purchasable")
            assertTrue(
                "%1\$s" !in text,
                "$dir names a price in the variant shown where nothing can be bought: \"$text\"",
            )
        }
    }

    @Test
    fun `placeholders survive in every language`() {
        languages.forEach { dir ->
            // The priced variant carries the price as %1$s and the cap as %2$d; the other two carry
            // the cap alone as %1$d. A mismatch here is a format crash, not a wording problem.
            val priced = value(dir, "itemedit_edit_pro_dialog_body_purchasable")
            assertTrue("%1\$s" in priced && "%2\$d" in priced, "$dir priced variant: \"$priced\"")
            bodies.drop(1).forEach { key ->
                val text = value(dir, key)
                assertTrue("%1\$d" in text, "$dir/$key must carry the cap as %1\$d: \"$text\"")
            }
        }
    }

    /**
     * Amber and the type's own glyph, matching the vault list. Read from the source because the
     * dialog needs a full `ItemEditViewModel` to compose — the same reason `CardWizardImeNextTest`
     * reads source, recorded there.
     */
    @Test
    fun `the dialog is amber and wears the refused type's icon`() {
        val source = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
            .takeIf { it.isFile } ?: fail("ItemEditScreen.kt not found")
        val text = source.readText()
        // Four spaces, not sixteen: the dialog was hoisted out of the form's column on 2026-09-07
        // so the linear card and identity routes — which `return` before the form is composed —
        // still have a renderer for `showProPrompt`. The old delimiter no longer matched the
        // dialog's own closing brace, and `substringBefore` falls through to a brace 9kB further
        // down rather than failing, which would have left this scan quietly reading most of the file.
        val block = text.substringAfter("if (state.showProPrompt) {", "")
            .takeIf { it.isNotEmpty() }?.substringBefore("\n    }")
            ?: fail("could not find the allowance dialog in ItemEditScreen")
        val code = block.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "accent = c.amber" in code,
            "the allowance dialog is not amber. Brass says \"this costs money\"; a spent allowance " +
                "is not a gate, and the vault list's dialog for the same refusal is amber.",
        )
        assertTrue(
            "LkIcons.Crown" !in code,
            "the allowance dialog wears a crown again. A crown names a tier; this refusal is about " +
                "a slot the user has already filled.",
        )
        assertTrue(
            "LkIcons.Card" in code && "LkIcons.Identity" in code,
            "the allowance dialog no longer shows the refused type's own glyph",
        )
    }
}

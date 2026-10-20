package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every string whose placeholder is filled by a *plural*, and the ruling made on each.
 *
 * This is a defect class no gate in the repo could see. Android lint compares catalogues and
 * checks placeholder counts; it cannot know that `%1$s` will be handed "12 éléments" at runtime,
 * so an adjective sitting next to that placeholder is never checked against the noun it will
 * modify. The result shipped twice on the app's most-viewed screen:
 *
 *     12 éléments · chiffré sur cet appareil      (masc. singular against a plural)
 *     12 éléments · filtré par recherche          (the same, one line below it)
 *
 * English hides it because English adjectives do not inflect, and German is immune because its
 * predicative adjectives do not either — so the two languages anyone here can read are exactly
 * the two that look fine. Pluralising the adjective is not a fix; it only moves the error to
 * one item. The fix is to use a word that agrees with nothing.
 *
 * So the rule this test enforces is not a wording rule — it is that the *set* of these sites is
 * known. Compose a plural into a format string anywhere in the app and the build fails until
 * someone writes down what they concluded about agreement in French, Spanish and Portuguese.
 * That is the whole value: the defect is invisible to tooling, so the human check has to be
 * mandatory rather than remembered.
 */
class ComposedPluralAgreementTest {

    @Test
    fun everyStringFedAPluralHasBeenRuledOnForAgreement() {
        assertEquals(
            RULED_ON.keys.toSortedSet(),
            composedPluralSites(),
            "a format string is being handed a pluralised count. In French, Spanish and " +
                "Portuguese any adjective or participle beside that placeholder will agree with " +
                "the wrong noun at every count but one. Decide, then add the key to RULED_ON " +
                "with what you decided.",
        )
    }

    private companion object {
        val RULED_ON = mapOf(
            "vaultlist_count_device_suffix" to
                "Safe: a noun ('encryption', 'chiffrement', 'criptografia'). Was the participle " +
                    "'encrypted'/'chiffré' until 2026-07-30 and read '12 éléments · chiffré'.",
            "vaultlist_count_filtered_suffix" to
                "Safe: a label ('filter: x'). Was 'filtered by'/'filtré par' until 2026-07-30 — " +
                    "the same defect as its neighbour above, found the same day by looking for it.",
            "vaultlist_count_device_folders_suffix" to
                "Safe, and for the same reason as its plain sibling: a noun, plus a second count " +
                    "that is itself a plural rather than something agreeing with the first. Added " +
                    "2026-08-12 when the root subtitle had to name folders as well as items — the " +
                    "root lists only unfiled items, so counting items alone read as data loss.",
            "vaultlist_count_filtered_folders_suffix" to
                "Safe: a label ('filter: x'), same ruling as vaultlist_count_filtered_suffix.",
            "vaultlist_trash_count_subtitle_with_folders" to
                "Safe, for the same reason as vaultlist_drag_trashed_folder: two counts (folders, " +
                    "items) and a participle written to agree with the pair. fr 'chiffrés', es " +
                    "'cifrados', pt 'criptografados' are masculine plural, which a mixed coordinated " +
                    "subject takes at every combination of the two counts. Added 2026-10-19 when the " +
                    "Trash header started counting folders.",
            "import_done_notice_combined" to
                "Safe: '%1\$s; %2\$s' in all five languages — pure scaffolding, no words at all.",
            "import_preview_items_found_with_format" to
                "Safe: '%1\$s · %2\$s' in all five languages — pure scaffolding.",
            "vaultlist_drag_trashed_folder" to
                "**Not safe by accident — the participle here was chosen to agree.** '%1\$s, %2\$s " +
                    "moved to Trash' puts a past participle after two counts, and in fr/es/pt it has " +
                    "to agree with something. It is written to agree with the *pair* rather than " +
                    "with either count: fr 'mis à la corbeille' and es/pt masculine plural " +
                    "('movidos') are correct for 'N folders and M items' at every combination, " +
                    "because a mixed-gender coordinated subject takes masculine plural in all three. " +
                    "The one thing that would break it is dropping to a single count, which would " +
                    "make the agreement wrong at one item. German and English are immune and prove " +
                    "nothing. Added 2026-08-18 with the drag's Trash dock.",
            "vaultlist_trash_folder_summary" to
                "Safe: '%1\$s, %2\$s' in all five languages — pure scaffolding, and both halves " +
                    "are themselves plurals rather than something agreeing with the other. Added " +
                    "2026-08-18 with the Trash screen's folder rows: a row stands for a subtree, so " +
                    "it has to name two counts, and Android picks a plural variant from exactly one " +
                    "quantity argument. '1 folder, 3 items' is unrepresentable in a single <plurals>.",
            "vaultlist_trash_folder_delete_confirm_body" to
                "Safe: every language puts the verb before the placeholders and agrees it with the " +
                    "folder, not with either count — fr 'sont détruits' agrees with 'Le dossier et " +
                    "tout son contenu', pt 'desfeito' with 'Isso', es has no participle at all. The " +
                    "two counts are passed as separate arguments deliberately: pre-joining them into " +
                    "vaultlist_trash_folder_summary read identically on screen and hid this site from " +
                    "the detector, which is the last thing a tier-A purge confirmation should do.",
            "unlock_kdf_summary" to
                "Safe: 'Argon2id · %1\$s · %2\$d MiB' — the only words are an identifier and a " +
                    "unit, neither of which inflects.",
        )
    }
}

/**
 * Keys passed to `stringResource` with an argument that came from `pluralStringResource`.
 *
 * Read out of the Kotlin rather than listed, for the same reason the Tier G register reads its
 * markers out of the XML: a second list is a list that drifts. Both spellings are matched — the
 * value bound to a local first (which is how every current site is written) and the inline call.
 *
 * `app/build.gradle.kts` declares the sources this walks as task inputs. Without that the task
 * would sit UP-TO-DATE through a newly added call site, which is measured behaviour, not a
 * worry — see the note on TierGClaimsTest.
 */
private fun composedPluralSites(): Set<String> {
    val out = sortedSetOf<String>()
    REPO.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter { it.extension == "kt" && "/src/main/" in it.path }
        .forEach { file ->
            val src = file.readText()
            if ("pluralStringResource" !in src) return@forEach
            Regex("""val\s+(\w+)\s*=\s*pluralStringResource""").findAll(src).forEach { bind ->
                val name = bind.groupValues[1]
                Regex("""stringResource\(\s*R\.string\.(\w+)\s*,[^()]*\b$name\b""")
                    .findAll(src)
                    .forEach { out += it.groupValues[1] }
            }
            Regex("""stringResource\(\s*R\.string\.(\w+)\s*,\s*pluralStringResource""")
                .findAll(src)
                .forEach { out += it.groupValues[1] }
        }
    return out
}

private val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")

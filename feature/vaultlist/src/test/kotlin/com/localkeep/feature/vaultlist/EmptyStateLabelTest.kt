package com.localkeep.feature.vaultlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The empty-state copy names the category the user filtered to.
 *
 * It used to name it by interpolation — `No %1$s yet` with the category name dropped in — and two
 * successive attempts to make that grammatical failed, because the words *around* a noun change
 * with the noun. French elides the preposition before a vowel, Portuguese agrees in gender and
 * number, German capitalises, and English needs "an" before a vowel. Each attempt fixed one
 * language and left the rest wrong.
 *
 * On 2026-07-30 the templates were deleted and every category was given its own finished sentence
 * in every language. What is left to guard is that nobody reintroduces a template — which is what
 * [noEmptyStateSentenceIsBuiltByInterpolation] does, in all five languages at once.
 *
 * A pure JVM test, so it reads the resource files directly rather than resolving ids, the same way
 * `OnboardingCopyTest` does and for the same reason.
 */
class EmptyStateLabelTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private fun strings(dir: String): String =
        java.io.File("src/main/res/$dir/strings.xml").takeIf { it.exists() }?.readText()
            ?: fail("$dir/strings.xml is missing")

    /** The value of a string resource in one language, by name. */
    private fun value(dir: String, name: String): String =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings(dir))
            ?.groupValues
            ?.get(1)
            ?.replace("&amp;", "&")
            ?: fail("$name not found in $dir/strings.xml")

    /** Every empty-state sentence the UI can show, by resource name. */
    private val sentenceNames: List<String> =
        listOf("logins", "totp", "cards", "identities", "notes", "files", "passkeys")
            .map { "vaultlist_empty_no_${it}_yet" } +
            listOf("password", "login", "totp", "note", "file", "card", "identity", "first_item")
                .map { "vaultlist_empty_add_$it" }

    /**
     * The one that would have caught the original defect, and catches its return.
     *
     * A format specifier in any of these means a noun is being dropped into a sentence again, in
     * whichever language it appears — the shape that produced "Ajouter carte", "Nenhum notas ainda"
     * and "Add a identity".
     */
    @Test
    fun noEmptyStateSentenceIsBuiltByInterpolation() {
        languages.forEach { dir ->
            sentenceNames.forEach { name ->
                val text = value(dir, name)
                assertTrue(
                    "%" !in text,
                    "$dir/$name interpolates: \"$text\" — write the sentence out instead",
                )
            }
        }
    }

    /**
     * The plain-empty-vault CTA opens the type chooser, so it must not name one of the nine types.
     *
     * `onAddFirst` is `{ addSheetRestored = false; showAddSheet = true }` (VaultListScreen), so the
     * button raises the add sheet — it has never opened the login form. It said "Add your first
     * login" until 2026-09-08 anyway, which was wrong twice over: it named one type out of nine and
     * it promised a form it does not open. Reported from the phone: "it might not be login? We have
     * 10 different options".
     *
     * The nouns are the four languages' own word for a login, from the project glossary — a translation that
     * reintroduces the type would otherwise pass an English-only check.
     */
    @Test
    fun theFirstItemCtaDoesNotNameALogin() {
        val loginNouns = listOf("login", "identifiant", "zugangsdaten", "inicio de sesión")
        languages.forEach { dir ->
            val text = value(dir, "vaultlist_empty_add_first_item").lowercase()
            loginNouns.forEach { noun ->
                assertTrue(
                    noun !in text,
                    "$dir/vaultlist_empty_add_first_item names a login: \"$text\" — the button opens the type sheet",
                )
            }
            assertTrue(
                """name="vaultlist_empty_add_first_login"""" !in strings(dir),
                "$dir still defines vaultlist_empty_add_first_login — the key named the type too, so it is renamed",
            )
        }
    }

    /** The templates are gone; a reference to either would not compile, but the strings could linger. */
    @Test
    fun theInterpolatedTemplatesAreGone() {
        languages.forEach { dir ->
            val xml = strings(dir)
            listOf("vaultlist_empty_no_type_yet", "vaultlist_empty_add_type").forEach { name ->
                assertTrue(
                    """name="$name"""" !in xml,
                    "$dir still defines $name — it cannot be made grammatical, see this file's kdoc",
                )
            }
        }
    }

    /** Each enum constant must name a sentence that really exists, in every language. */
    @Test
    fun everyCategoryAndTypeHasItsOwnSentenceInEveryLanguage() {
        languages.forEach { dir ->
            sentenceNames.forEach { name ->
                assertTrue(value(dir, name).isNotBlank(), "$dir/$name is blank")
            }
        }
    }

    /**
     * The English article, which the template got wrong for the one type that needs "an".
     *
     * Pinned rather than left to review: it is a single character, it reads as a typo, and it is
     * exactly the class of thing that gets "fixed" back to "a" by someone tidying up.
     */
    @Test
    fun englishUsesTheRightIndefiniteArticle() {
        assertEquals("Add an identity", value("values", "vaultlist_empty_add_identity"))
        assertEquals("Add a card", value("values", "vaultlist_empty_add_card"))
    }

    /**
     * French elides `de` before a vowel. "Pas encore de identifiants" is what the template rendered.
     */
    @Test
    fun frenchElidesBeforeAVowel() {
        assertEquals("Pas encore d\\'identifiants", value("values-fr", "vaultlist_empty_no_logins_yet"))
        assertEquals("Pas encore d\\'identités", value("values-fr", "vaultlist_empty_no_identities_yet"))
    }

    @Test
    fun `every filter that can be created maps to an add-sheet type`() {
        // Passkeys are the exception, and deliberately: they arrive from the credential provider,
        // never from an add flow, so offering "Add a passkey" would lead nowhere.
        assertNull(ItemTypeFilter.PASSKEY.addable())
        ItemTypeFilter.entries.filter { it != ItemTypeFilter.PASSKEY }.forEach {
            requireNotNull(it.addable()) { "${it.name} has no add-sheet type" }
        }
    }

    /** Every category names a title, and every add type names an action. No id is left at zero. */
    @Test
    fun `every enum constant carries a sentence resource`() {
        ItemTypeFilter.entries.forEach { require(it.emptyTitleRes != 0) { "${it.name} has no empty title" } }
        NewItemType.entries.forEach { require(it.addActionRes != 0) { "${it.name} has no add action" } }
    }
}

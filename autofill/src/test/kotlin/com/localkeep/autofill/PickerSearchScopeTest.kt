package com.localkeep.autofill

import com.localkeep.core.model.Login
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The autofill picker's "Search logins" box matches on the vault's definition of a match, not a
 * second one of its own.
 *
 * It used to carry a private matcher over `item.title` and `Login.username` only — no URIs, no
 * notes, no tags, no folder path, no custom fields, and a default-locale `lowercase()` where every
 * shared path pins `Locale.ROOT` and folds diacritics. So a login titled "Shopping" whose only URI
 * is `amazon.co.uk` was found by the in-app search and answered "No logins match that" here, in the
 * one place the user had just been handed the site's own domain to type. Same divergence dropped
 * accents ("cafe" finds "Café" in-app, not here) and mis-cased Turkish dotted-I.
 *
 * `VaultRepository.matchesSearchQuery` and `searchHaystack` are `internal` to `:core:vault`, so
 * this module cannot call the matcher directly — it goes through the public `search`, which is the
 * same entry point the in-app list uses, and intersects the answer with the projected list it was
 * already showing. That intersection is what keeps concealment intact: `search` runs over
 * `getAllItems()`, which includes duress-hidden items, and nothing may appear here that
 * `visibilitySnapshot().liveItems` did not already put on screen.
 *
 * Driven through the pure [pickerSearchResults] rather than the Activity: a real `VaultRepository`
 * needs Argon2, the keystore and an encrypted vault, none of which exist in a JVM unit test. The
 * wiring that connects the two is asserted on the source, below.
 */
class PickerSearchScopeTest {

    private fun login(
        title: String,
        username: String = "",
        uris: List<String> = emptyList(),
    ) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = username, uris = uris.map { UriMatch(uri = it) }),
    )

    private val shopping = login("Shopping", uris = listOf("https://amazon.co.uk"))
    private val cafe = login("Café Rewards")
    private val unrelated = login("Router", username = "admin")

    @Test
    fun `a match only the shared vault matcher can make reaches the picker`() {
        val all = listOf(shopping, unrelated)
        // What VaultRepository.search answers for "amazon": the URI is in the haystack, the title
        // and username are not. The old picker matcher saw neither and returned nothing.
        val visible = pickerSearchResults(all, "amazon") { listOf(shopping) }
        assertEquals(
            "the picker must honour the vault's answer, not re-derive one from title/username",
            listOf(shopping),
            visible,
        )
    }

    @Test
    fun `a diacritic-folded match reaches the picker`() {
        val all = listOf(cafe, unrelated)
        val visible = pickerSearchResults(all, "cafe") { listOf(cafe) }
        assertEquals(listOf(cafe), visible)
    }

    @Test
    fun `an item outside the visibility projection never appears`() {
        // `search` runs over getAllItems(), which still contains concealed and duress-hidden items;
        // `all` is already visibilitySnapshot().liveItems. The intersection is the control.
        val concealed = login("Hidden Bank")
        val all = listOf(shopping, unrelated)
        val visible = pickerSearchResults(all, "bank") { listOf(concealed, shopping) }
        assertFalse(
            "a concealed item must not become reachable through the picker's search box",
            concealed in visible,
        )
        assertEquals(listOf(shopping), visible)
    }

    @Test
    fun `an empty query restores the projected list without consulting the vault`() {
        val all = listOf(shopping, cafe, unrelated)
        var consulted = false
        val visible = pickerSearchResults(all, "   ") {
            consulted = true
            emptyList()
        }
        assertEquals(all, visible)
        assertFalse("an empty query is not a search", consulted)
    }

    @Test
    fun `the projection's order survives the search`() {
        val all = listOf(shopping, cafe, unrelated)
        // Deliberately answered out of order: showSearch sorts by display name and the picker must
        // keep that order rather than adopt the repository's.
        val visible = pickerSearchResults(all, "e") { listOf(unrelated, cafe, shopping) }
        assertEquals(all, visible)
    }

    @Test
    fun `the picker delegates to the repository instead of matching on its own`() {
        val source =
            File("src/main/kotlin/com/localkeep/autofill/PickerActivity.kt").readText()
        // Anchor asserted before it is used. `substringAfter` returns the WHOLE receiver when its
        // delimiter is absent, so renaming this method does not break the helper - it widens it to
        // the entire file, and the two positive assertions below then pass on unrelated lines
        // somewhere else in it. This is the third place in the repo that trap has been found; the
        // other two were `BackupInFlightControlsTest` and `SettingsInFlightControlsTest`.
        val anchor = "fun computeVisible(query: String): List<VaultItem> ="
        assertTrue(
            "anchor '$anchor' is gone from PickerActivity.kt - update it, or this test is " +
                "asserting against the whole file",
            anchor in source,
        )
        val filterBody = source
            .substringAfter(anchor)
            .substringBefore("\n        }")
        assertTrue(
            "computeVisible must route through pickerSearchResults; found: $filterBody",
            "pickerSearchResults(" in filterBody,
        )
        assertFalse(
            "computeVisible must not carry a second matcher over title/username; found: $filterBody",
            "item.title" in filterBody || "username" in filterBody,
        )
        assertTrue(
            "the search lambda must be the repository's own search entry point; found: $filterBody",
            "repository.search(" in filterBody,
        )
    }
}

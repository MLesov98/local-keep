package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.common.UrlHosts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A CSV row with no title is named after its host — the same host the rest of the app derives.
 *
 * `CsvColumnMapper` used to extract the host itself, and its private version carried the exact
 * defect `UrlHosts.normalise` was written to fix: `java.net.URI.host` is null for a registry-based
 * authority (an underscore is enough), and the fallback was the whole raw URL, so the item was
 * titled with a scheme, a path and a query. It also never lowercased or dropped `www.`, while the
 * duplicate detector and the import merger — asking the same question of the same value — did both.
 */
class CsvTitleFromUrlTest {

    private fun titleFor(url: String): String = onlyItemTitle("name,url,username,password\n,$url,me,hunter2")

    private fun onlyItemTitle(csv: String): String =
        when (val parsed = CsvImporter.parse(csv.toByteArray())) {
            is Outcome.Success -> parsed.value.items.single().title
            is Outcome.Failure -> error("expected a successful parse, got: ${parsed.error.message}")
        }

    @Test
    fun `an underscore host is a host, not the entire URL`() {
        assertEquals("my_bank.example.com", titleFor("https://my_bank.example.com/login"))
    }

    @Test
    fun `the title is lowercased and loses www, exactly as the identity derivation does`() {
        assertEquals("bank.example.com", titleFor("https://WWW.Bank.Example.com/login"))
    }

    /** Stated as an equality with the shared function so the two cannot drift apart again. */
    @Test
    fun `the title agrees with UrlHosts for every shape`() {
        listOf(
            "https://bank.example.com/login?next=%2Fhome",
            "bank.example.com",
            "https://my_bank.example.com/login",
            "https://user@bank.example.com:8443/x",
        ).forEach { url ->
            assertEquals(url, UrlHosts.normalise(url), titleFor(url))
        }
    }

    /** A row with a title of its own keeps it, and a row with neither still gets something. */
    @Test
    fun `a blank title with a blank URL falls back rather than being empty`() {
        assertEquals("Imported item", onlyItemTitle("name,url,username,password\n,,me,hunter2"))
    }
}

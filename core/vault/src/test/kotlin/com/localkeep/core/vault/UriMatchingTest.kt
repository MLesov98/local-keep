package com.localkeep.core.vault

import com.localkeep.core.model.MatchStrategy
import com.localkeep.core.model.UriMatch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UriMatchingTest {
    @Test
    fun `domain strategy matches exact host and candidate subdomains`() {
        val rule = UriMatch("https://example.com/login", MatchStrategy.DOMAIN)

        assertTrue(matchesUri("example.com", rule))
        assertTrue(matchesUri("https://accounts.example.com/sign-in", rule))
        assertFalse(matchesUri("example.com.evil.test", rule))
        assertFalse(matchesUri("fakeexample.com", rule))
    }

    @Test
    fun `domain strategy matches a bare apex against its own generic-prefixed save`() {
        assertTrue(matchesUri("example.com", UriMatch("https://www.example.com")))
        assertTrue(matchesUri("example.com", UriMatch("https://accounts.example.com")))
        // Anything else prepended is a different site, not a save-shape artifact — must not match.
        assertFalse(matchesUri("example.com", UriMatch("https://evil.example.com")))
    }

    @Test
    fun `domain strategy does not collapse unrelated public-suffix hosts`() {
        val rule = UriMatch("https://bank.co.uk", MatchStrategy.DOMAIN)

        assertTrue(matchesUri("login.bank.co.uk", rule))
        assertFalse(matchesUri("evil.co.uk", rule))
        // The bare public suffix itself must never match a configured host that is merely one
        // generic label longer than it — "bank.co.uk" is not "a save for the apex co.uk".
        assertFalse(matchesUri("co.uk", UriMatch("https://bank.co.uk")))
    }

    @Test
    fun `host strategy requires exact normalized host`() {
        val rule = UriMatch("HTTPS://EXAMPLE.COM/path", MatchStrategy.HOST)

        assertTrue(matchesUri("example.com", rule))
        assertFalse(matchesUri("login.example.com", rule))
    }

    @Test
    fun `starts-with strategy cannot cross host boundary`() {
        val rule = UriMatch("https://example.com/account", MatchStrategy.STARTS_WITH)

        assertTrue(matchesUri("https://example.com/account/login", rule))
        assertFalse(matchesUri("https://example.com.evil.test/account", rule))
        assertFalse(matchesUri("https://example.com/other", rule))
        assertFalse(matchesUri("https://example.com/accounting", rule))
    }

    @Test
    fun `exact regex and never strategies are bounded`() {
        assertTrue(
            matchesUri(
                "https://example.com/Case",
                UriMatch("https://example.com/Case/", MatchStrategy.EXACT),
            ),
        )
        assertFalse(
            matchesUri(
                "https://example.com/case",
                UriMatch("https://example.com/Case", MatchStrategy.EXACT),
            ),
        )
        assertTrue(
            matchesUri(
                "login.example.com",
                UriMatch("""login\.example\.com""", MatchStrategy.REGEX),
            ),
        )
        assertFalse(matchesUri("anything", UriMatch("[", MatchStrategy.REGEX)))
        assertFalse(matchesUri("example.com", UriMatch("example.com", MatchStrategy.NEVER)))
    }
}

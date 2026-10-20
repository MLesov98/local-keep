package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pulling a site name out of whatever the user typed into a website row.
 *
 * The bar is deliberately "stay quiet unless sure": this only ever fills a *blank* title, and a
 * wrong guess is worse than no guess, because the user then has to notice and undo it rather than
 * simply type what they wanted. Half-typed hosts must return null so the suggestion does not
 * flicker into the title field letter by letter while someone is still typing.
 */
class HostOfTest {

    @Test
    fun `a bare hostname is its own title`() {
        assertEquals("bank.example.com", hostOf("bank.example.com"))
    }

    @Test
    fun `scheme, path and query are all discarded`() {
        assertEquals("bank.example.com", hostOf("https://bank.example.com/login?next=%2Fhome#top"))
    }

    @Test
    fun `www is dropped because nobody calls the site that`() {
        assertEquals("example.com", hostOf("https://www.example.com"))
    }

    @Test
    fun `a port is not part of the name`() {
        assertEquals("example.com", hostOf("http://example.com:8443/x"))
    }

    @Test
    fun `userinfo is stripped rather than becoming the title`() {
        // "ada@example.com" as a title would be a credential in the one field shown unmasked in
        // every list, search result and picker in the app.
        assertEquals("example.com", hostOf("https://ada@example.com/"))
    }

    @Test
    fun `case is normalised`() {
        assertEquals("example.com", hostOf("HTTPS://Example.COM"))
    }

    @Test
    fun `a half-typed host offers nothing`() {
        assertNull(hostOf(""))
        assertNull(hostOf("ban"))
        assertNull(hostOf("bank."))
        assertNull(hostOf("bank.c"), "a one-letter TLD is still mid-word")
    }

    @Test
    fun `a subdomain is kept, because it is how the user distinguishes two logins`() {
        assertEquals("admin.example.com", hostOf("https://admin.example.com"))
    }
}

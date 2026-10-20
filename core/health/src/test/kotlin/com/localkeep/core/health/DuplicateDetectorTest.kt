package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.MatchStrategy
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateDetectorTest {
    private val t: Instant = Instant.parse("2026-07-26T00:00:00Z")

    private fun login(
        title: String = "",
        username: String = "u",
        password: String = "p",
        vararg uris: String,
        deleted: Boolean = false,
    ): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = t,
        updatedAt = t,
        deletedAt = if (deleted) t else null,
        content = Login(
            username = username,
            password = password,
            uris = uris.map { UriMatch(uri = it) },
        ),
    )

    @Test
    fun `same host and username is a duplicate`() {
        val a = login(username = "ada@example.com", uris = arrayOf("https://github.com/login"))
        val b = login(username = "ada@example.com", uris = arrayOf("github.com"))

        val groups = DuplicateDetector.findDuplicates(listOf(a, b))

        assertEquals(1, groups.size)
        assertEquals(setOf(a.id, b.id), groups.values.single().map { it.id }.toSet())
    }

    @Test
    fun `same host with different usernames is two accounts, not a duplicate`() {
        val a = login(username = "ada", uris = arrayOf("github.com"))
        val b = login(username = "grace", uris = arrayOf("github.com"))

        assertTrue(DuplicateDetector.findDuplicates(listOf(a, b)).isEmpty())
    }

    @Test
    fun `same username at different hosts is not a duplicate`() {
        val a = login(username = "ada", uris = arrayOf("github.com"))
        val b = login(username = "ada", uris = arrayOf("gitlab.com"))

        assertTrue(DuplicateDetector.findDuplicates(listOf(a, b)).isEmpty())
    }

    @Test
    fun `www and a scheme do not stop two entries matching`() {
        val a = login(username = "ada", uris = arrayOf("https://www.example.com/signin?next=/"))
        val b = login(username = "ADA", uris = arrayOf("example.com"))

        assertEquals(1, DuplicateDetector.findDuplicates(listOf(a, b)).size)
    }

    @Test
    fun `a subdomain is left alone rather than guessed at`() {
        // No Public Suffix List here on purpose — see DuplicateDetector.normaliseHost. Missing this
        // pair is the deliberate direction to be wrong in.
        val a = login(username = "ada", uris = arrayOf("mail.example.com"))
        val b = login(username = "ada", uris = arrayOf("example.com"))

        assertTrue(DuplicateDetector.findDuplicates(listOf(a, b)).isEmpty())
    }

    @Test
    fun `entries with no uri fall back to their title`() {
        val a = login(title = "Netflix", username = "ada")
        val b = login(title = "netflix ", username = "ada")

        assertEquals(1, DuplicateDetector.findDuplicates(listOf(a, b)).size)
    }

    @Test
    fun `a blank username never groups`() {
        // Otherwise every URL-only entry on a shared host collides into one huge false group.
        val a = login(username = "", uris = arrayOf("example.com"))
        val b = login(username = "  ", uris = arrayOf("example.com"))

        assertTrue(DuplicateDetector.findDuplicates(listOf(a, b)).isEmpty())
    }

    @Test
    fun `trashed items are not duplicates of anything`() {
        val a = login(username = "ada", uris = arrayOf("example.com"))
        val b = login(username = "ada", uris = arrayOf("example.com"), deleted = true)

        assertTrue(DuplicateDetector.findDuplicates(listOf(a, b)).isEmpty())
    }

    @Test
    fun `one item listing a host twice is not a duplicate of itself`() {
        val solo = VaultItem(
            id = UUID.randomUUID(),
            createdAt = t,
            updatedAt = t,
            content = Login(
                username = "ada",
                password = "p",
                uris = listOf(
                    UriMatch(uri = "example.com", strategy = MatchStrategy.DOMAIN),
                    UriMatch(uri = "https://example.com/login", strategy = MatchStrategy.EXACT),
                ),
            ),
        )

        assertTrue(DuplicateDetector.findDuplicates(listOf(solo)).isEmpty())
    }

    // java.net.URI treats an underscore host as registry-based and returns null for getHost(). The
    // fallback used to substitute the whole raw URL, so the same account saved with and without a
    // path produced two different keys and never grouped.
    @Test
    fun `a host java_net_URI cannot parse still groups`() {
        val a = login(username = "ada", uris = arrayOf("https://my_bank.example.com/login"))
        val b = login(username = "ada", uris = arrayOf("https://my_bank.example.com"))

        val ids = DuplicateDetector.findDuplicates(listOf(a, b)).values.flatten().map { it.id }.toSet()

        assertEquals(setOf(a.id, b.id), ids)
    }

    @Test
    fun `a port does not split an unparseable host`() {
        val a = login(username = "ada", uris = arrayOf("https://my_host.example.com:8443/app"))
        val b = login(username = "ada", uris = arrayOf("https://my_host.example.com"))

        val ids = DuplicateDetector.findDuplicates(listOf(a, b)).values.flatten().map { it.id }.toSet()

        assertEquals(setOf(a.id, b.id), ids)
    }

    @Test
    fun `an item matching on one of several hosts is still caught`() {
        val a = login(username = "ada", uris = arrayOf("example.com", "example.net"))
        val b = login(username = "ada", uris = arrayOf("example.net"))

        val ids = DuplicateDetector.findDuplicates(listOf(a, b)).values.flatten().map { it.id }.toSet()

        assertEquals(setOf(a.id, b.id), ids)
    }

    // Two services on one box - a NAS, a router, a dev machine - share a host and often a username.
    // java.net.URI.getHost() never carries the port, so these two were one account saved twice.
    @Test
    fun `the same admin on two ports of one host is two accounts`() {
        val portainer = login(username = "admin", uris = arrayOf("https://192.168.1.10:8080"))
        val other = login(username = "admin", uris = arrayOf("https://192.168.1.10:9000/login"))

        assertTrue(DuplicateDetector.findDuplicates(listOf(portainer, other)).isEmpty())
    }

    @Test
    fun `the same admin on the same port is still a duplicate`() {
        val a = login(username = "admin", uris = arrayOf("https://192.168.1.10:8080"))
        val b = login(username = "admin", uris = arrayOf("192.168.1.10:8080/login"))

        assertEquals(1, DuplicateDetector.findDuplicates(listOf(a, b)).size)
    }

    @Test
    fun `different ports on a host java_net_URI cannot parse are still different`() {
        val a = login(username = "ada", uris = arrayOf("https://my_host.example.com:8443"))
        val b = login(username = "ada", uris = arrayOf("https://my_host.example.com:9443"))

        assertTrue(DuplicateDetector.findDuplicates(listOf(a, b)).isEmpty())
    }

    @Test
    fun `the ordinary web ports read as no port`() {
        val a = login(username = "ada", uris = arrayOf("https://example.com:443/login"))
        val b = login(username = "ada", uris = arrayOf("http://example.com:80"))

        assertEquals(1, DuplicateDetector.findDuplicates(listOf(a, b)).size)
    }

    // The bare host is as plausibly either port typed short, so it matches both - but being bridged
    // by it must not make the two ported entries a pair.
    @Test
    fun `a bare host matches each port without joining the ports to each other`() {
        val bare = login(username = "admin", uris = arrayOf("192.168.1.10"))
        val a = login(username = "admin", uris = arrayOf("192.168.1.10:8080"))
        val b = login(username = "admin", uris = arrayOf("192.168.1.10:9000"))

        val groups = DuplicateDetector.findDuplicates(listOf(bare, a, b)).values

        assertEquals(2, groups.size)
        assertTrue(groups.none { a in it && b in it }, "the two ports must never share a group")
        assertTrue(groups.all { bare in it })
    }
}
